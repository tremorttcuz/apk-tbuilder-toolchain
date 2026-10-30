package com.builder.core

import java.io.File

enum class St { IDLE, RUNNING, DONE, ERROR, SKIPPED, CANCELLED }
enum class Group { TOOLCHAIN, BUILD }

/** Один шаг цепочки. Поля меняет движок из рабочего потока, интерфейс только читает. */
class Stage(val id: String, val group: Group, val title: String, val hint: String, val spec: JObj) {
    @Volatile var state = St.IDLE
    @Volatile var detail = ""
    @Volatile var progress = -1f // -1 — неопределённый (бегущая линия), 0..1 — известный
    @Volatile var startedAt = 0L
    @Volatile var elapsed = 0L
    @Volatile var error: String? = null
    @Volatile var tip: String? = null
    @Volatile var note = ""
    /** Сколько обычно длится шаг (по прошлым сборкам), мс; 0 — неизвестно. Нужен для оценки прогресса и остатка времени. */
    @Volatile var expectedMs = 0L

    /** Прогресс для показа: настоящий, а если шаг не умеет его сообщать (dex, Kotlin…) — оценка по времени прошлых сборок. -1 — неизвестно. */
    fun shownProgress(): Float {
        if (progress >= 0f) return progress
        if (state != St.RUNNING || expectedMs <= 0L || startedAt == 0L) return -1f
        return ((System.currentTimeMillis() - startedAt).toFloat() / expectedMs).coerceIn(0f, 0.97f)
    }

    val op: String get() = spec.str("op", if (spec.containsKey("url") || spec.containsKey("urls") || spec.containsKey("asset") || spec.containsKey("maven")) "download" else "shell")

    fun reset() { state = St.IDLE; detail = ""; progress = -1f; elapsed = 0; error = null; tip = null; note = "" }
}

enum class FixState { OFFERED, RUNNING, DONE, ERROR }

/** Предложение «докачать»: описание файла в формате assets.json, причина и ход загрузки. */
class Fix(val id: String, val title: String, val why: String, val spec: JObj) {
    @Volatile var state = FixState.OFFERED
    @Volatile var bytes = -1L        // размер, если сервер сообщил
    @Volatile var progress = -1f
    @Volatile var detail = ""
    @Volatile var error: String? = null
}

class BuildError(msg: String, val tip: String? = null) : Exception(msg)
private class Skip(val reason: String) : Exception(reason)

class Result(val apk: File, val size: Long, val sha: String, val ms: Long, val info: String)

/** Всё, что ядру нужно от платформы: так оно не зависит от Android и проверяется на обычной JVM. */
interface Platform {
    val files: File
    val abis: List<String>
    fun asset(path: String): ByteArray?
    fun assetFiles(dir: String): List<String>
    /** Потоковое чтение крупных встроенных файлов (компиляторы) без загрузки целиком в память. */
    fun assetStream(path: String): java.io.InputStream? = asset(path)?.inputStream()
}

interface EngineListener {
    fun onStage(i: Int) {}
    fun onRun(running: Boolean) {}
    fun onFinish(result: Result?, error: String?, cancelled: Boolean) {}
    fun onProject() {}
    fun onFixes() {}
}

class Engine(private val pf: Platform, var rhythm: Boolean = true, private val autoVersion: () -> Boolean = { false }, private val freshLibs: () -> Boolean = { false }) {
    val home: File = pf.files.absoluteFile
    val console = Console()
    val root = File(home, "toolchain")
    val projectsDir = File(home, "projects")
    val outDir = File(home, "out")
    val buildDir = File(home, "work/build")

    @Volatile var listener: EngineListener? = null
    @Volatile var stages: List<Stage> = emptyList(); private set
    @Volatile var running = false; private set
    @Volatile var result: Result? = null; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var tools: Map<String, File> = emptyMap(); private set
    @Volatile var runStartedAt = 0L; private set
    var project: String = "sample"; private set
    @Volatile var fixes: List<Fix> = emptyList(); private set
    @Volatile var fixing = false; private set
    /** Что качается сейчас (для уведомления и экранов): список текущей докачки. */
    @Volatile var fixTodo: List<Fix> = emptyList(); private set
    @Volatile var lastFixFailed = false; private set
    private var fixStage: Stage? = null
    private var fixCur: Fix? = null

    private val shell = Shell()
    @Volatile private var cancel = false
    private var worker: Thread? = null
    private val lastTick = HashMap<Int, Long>()

    val projectDir get() = File(projectsDir, project)
    val firstBuild get() = stages.indexOfFirst { it.group == Group.BUILD }.let { if (it < 0) stages.size else it }

    // ---------------------------------------------------------------- загрузка конфигурации

    fun load() {
        root.mkdirs(); projectsDir.mkdirs(); outDir.mkdirs()
        installSampleIfEmpty()
        val saved = File(home, "project.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val list = projects()
        project = if (saved in list) saved else list.firstOrNull() ?: "sample"

        val out = ArrayList<Stage>()
        // 1) тулчейн: встроенный assets.json + пользовательский toolchain/extra.json
        val items = ArrayList<JObj>()
        for (src in listOf(pf.asset("assets.json")?.toString(Charsets.UTF_8), File(root, "extra.json").takeIf { it.exists() }?.readText())) {
            if (src == null) continue
            try {
                (Json.parse(src) as? List<*>)?.forEach { o ->
                    val j = o as? JObj ?: return@forEach
                    // временные туннели (trycloudflare) давно не работают: такие записи из старого extra.json пропускаем
                    if (j.str("url").contains("trycloudflare.com")) return@forEach
                    items.add(j)
                }
            }
            catch (e: Exception) { console.add(c("31", "assets: ${e.message}")) }
        }
        items.forEachIndexed { k, o ->
            out.add(Stage("dl$k", Group.TOOLCHAIN, o.str("name", "Загрузка ${k + 1}"), o.str("hint"), o))
        }
        // 2) сборка: pipeline.json из рабочей папки (обновляется вместе с приложением)
        val bundled = pf.asset("pipeline.json")?.toString(Charsets.UTF_8)
        val file = File(home, "pipeline.json")
        val bundledVer = bundled?.let { runCatching { (Json.parse(it) as JObj).int("version") }.getOrDefault(0) } ?: 0
        val localVer = if (file.exists()) runCatching { (Json.parse(file.readText()) as JObj).int("version") }.getOrDefault(-1) else -1
        if (bundled != null && localVer < bundledVer) {
            if (file.exists()) file.copyTo(File(home, "pipeline.json.bak"), overwrite = true)
            file.writeText(bundled)
        }
        val text = if (file.exists()) file.readText() else bundled
        try {
            val list = (Json.parse(text ?: "{}") as? JObj)?.get("stages") as? List<*> ?: emptyList<Any?>()
            list.forEachIndexed { k, v ->
                val o = v as? JObj ?: return@forEachIndexed
                out.add(Stage("b$k", Group.BUILD, o.str("name", o.str("op", "шаг ${k + 1}")), o.str("hint"), o))
            }
        } catch (e: Exception) {
            console.add(c("31", "pipeline.json: ${e.message}"))
            if (bundled != null && text != bundled) { file.writeText(bundled); load(); return }
        }
        stages = out
        syncCached()
        tools = Tools.locate(root)
    }

    private fun installSampleIfEmpty() {
        if (projects().isNotEmpty()) return
        val dst = File(projectsDir, "sample")
        for (rel in pf.assetFiles("sample")) {
            val bytes = pf.asset("sample/$rel") ?: continue
            File(dst, rel).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
        }
    }

    /** Уже скачанное помечаем готовым — как только приложение открылось. */
    private fun syncCached() {
        for (s in stages) if (s.group == Group.TOOLCHAIN) {
            val d = try { dl(s) } catch (_: Exception) { null }
            if (d != null && isDone(d)) { s.state = St.DONE; s.progress = 1f; s.note = "в кэше" } else s.reset()
        } else s.reset()
    }

    fun projects(): List<String> = projectsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.sorted() ?: emptyList()

    fun useProject(name: String): Boolean {
        if (running || name !in projects()) return false
        project = name
        File(home, "project.txt").writeText(name)
        stages.filter { it.group == Group.BUILD }.forEach { it.reset() }
        result = null; lastError = null
        listener?.onProject()
        return true
    }

    // ---------------------------------------------------------------- управление запуском

    /** Первый шаг, который ещё нужно выполнить: недокачанный тулчейн или начало сборки. */
    fun nextIndex(): Int {
        val t = stages.indexOfFirst { it.group == Group.TOOLCHAIN && it.state != St.DONE }
        return if (t >= 0) t else firstBuild
    }

    val toolchainReady get() = stages.filter { it.group == Group.TOOLCHAIN }.all { it.state == St.DONE }

    fun start() {
        if (running || fixing) return
        fixes = emptyList()
        if (stages.isEmpty()) load()
        val from = nextIndex()
        for (i in from until stages.size) stages[i].reset()
        for (s in stages) if (s.group == Group.BUILD) s.reset()
        result = null; lastError = null; cancel = false; running = true; runStartedAt = System.currentTimeMillis()
        try { File(home, "work").mkdirs(); console.file = File(home, "work/last-build.log").also { it.writeText("") } } catch (_: Exception) {}
        listener?.onRun(true)
        worker = Thread({ runAll(from) }, "build").apply { isDaemon = true; start() }
    }

    fun cancel() {
        if (!running && !fixing) return
        cancel = true
        shell.kill()
    }

    fun join(ms: Long = 0) { worker?.join(ms) }

    private fun runAll(from: Int) {
        val t0 = System.currentTimeMillis()
        var err: String? = null
        var wasCancelled = false
        console.add(c("1;34", "━━ Сборка · проект $project"))
        try {
            for (i in from until stages.size) {
                if (cancel) throw Cancelled()
                val s = stages[i]
                s.state = St.RUNNING; s.startedAt = System.currentTimeMillis(); s.progress = -1f; s.detail = ""
                s.expectedMs = expectedFor(s)
                if (i == firstBuild) tools = Tools.locate(root)
                notifyStage(i, true)
                console.add(c("1;34", "▸ [${i + 1}/${stages.size}] ${s.title}"))
                try {
                    conditionFailure(s)?.let { throw Skip(it) }
                    checkRequires(s)
                    runStage(s, i)
                    s.state = St.DONE
                    if (s.progress < 0f) s.progress = 1f
                    if (s.group == Group.TOOLCHAIN) tools = Tools.locate(root)
                } catch (e: Skip) {
                    s.state = St.SKIPPED; s.note = e.reason
                    console.add(c("90", "↷ пропущено: ${e.reason}"))
                }
                s.elapsed = System.currentTimeMillis() - s.startedAt
                if (s.state == St.DONE && s.group == Group.BUILD) rememberTime(s)
                if (s.state == St.DONE) console.add(c("32", "✓ ${s.title}") + c("90", "  ${fmtMs(s.elapsed)}${if (s.note.isNotEmpty()) " · " + s.note else ""}"))
                notifyStage(i, true)
                if (rhythm) Thread.sleep(if (s.state == St.SKIPPED) 50 else 170)
            }
        } catch (e: Cancelled) {
            wasCancelled = true
            stages.firstOrNull { it.state == St.RUNNING }?.let { it.state = St.CANCELLED; it.detail = "отменено"; it.note = "отменено"; notifyStage(stages.indexOf(it), true) }
            console.add(c("33", "■ Остановлено пользователем"))
        } catch (e: Throwable) {
            val i = stages.indexOfFirst { it.state == St.RUNNING }
            val msg = e.message ?: e.javaClass.simpleName
            if (i >= 0) {
                val s = stages[i]
                s.state = St.ERROR; s.error = msg; s.tip = (e as? BuildError)?.tip
                s.elapsed = System.currentTimeMillis() - s.startedAt
                notifyStage(i, true)
            }
            err = msg
            console.add(c("1;31", "✗ ${msg.lineSequence().first()}"))
            (e as? BuildError)?.tip?.let { console.add(c("33", "  ↳ $it")) }
        }
        val ms = System.currentTimeMillis() - t0
        lastError = err
        if (err == null && !wasCancelled) {
            console.add(c("1;32", "━━ Готово за ${fmtMs(ms)}") + (result?.let { c("90", "  ${it.apk.name} · ${Downloader.human(it.size)}") } ?: ""))
        }
        if (!wasCancelled) recordHistory(err == null, ms)
        if (err != null && !wasCancelled) try { suggest(stages.indexOfFirst { it.state == St.ERROR }, err) } catch (_: Exception) {}
        running = false
        listener?.onRun(false)
        listener?.onFinish(result, err, wasCancelled)
    }

    private fun notifyStage(i: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - (lastTick[i] ?: 0L) < 60) return
        lastTick[i] = now
        if (i < 0) { // ход докачки: переносим прогресс шага в предложение
            val f = fixCur; val st = fixStage
            if (f != null && st != null) { f.progress = st.progress; f.detail = st.detail }
            listener?.onFixes(); return
        }
        listener?.onStage(i)
    }

    // ---------------------------------------------------------------- условия и зависимости

    private fun conditionFailure(s: Stage): String? {
        for (cond in s.spec.strList("when")) {
            val k = cond.substringBefore(':'); val v = cond.substringAfter(':', "")
            when (k) {
                "tool" -> if (tools[v] == null) return "нет инструмента $v"
                "exists" -> if (!File(projectDir, v).exists()) return "в проекте нет $v"
                "missing" -> if (File(projectDir, v).exists()) return "в проекте есть $v"
                "code" -> when (v) {
                    "kt" -> if (!hasCode("kt")) return "нет Kotlin-кода"
                    "java" -> if (!hasCode("java")) return "нет Java-кода"
                }
                "jvm" -> if (!hasCode("kt") && !hasCode("java") && gradleInfo()?.let { it.deps.isNotEmpty() || it.compileOnly.isNotEmpty() } != true) return "в проекте нет кода и зависимостей"
            }
        }
        return null
    }

    private fun checkRequires(s: Stage) {
        val miss = s.spec.strList("requires").filter { tools[it] == null && !(it == "android.jar" && Tools.androidJar(root) != null) }
        if (miss.isEmpty()) return
        if (s.spec.bool("optional")) throw Skip("нет ${miss.joinToString()}")
        throw BuildError("Нет инструментов: ${miss.joinToString()}", TIP_TOOLS)
    }

    // ---------------------------------------------------------------- исполнение шагов

    private fun runStage(s: Stage, i: Int) {
        when (s.op) {
            "download" -> doDownload(s, i)
            "shell" -> doShell(s, i)
            "doctor" -> doDoctor(s)
            "clean" -> doClean(s)
            "deps" -> doDeps(s, i)
            "manifest" -> doManifest(s)
            "aapt-compile" -> doAaptCompile(s, i)
            "aapt-link" -> doAaptLink(s, i)
            "kotlinc" -> doKotlin(s, i)
            "javac" -> doJava(s, i)
            "dex" -> doDex(s, i)
            "pack" -> doPack(s, i)
            "sign" -> doSign(s, i)
            "verify" -> doVerify(s)
            "publish" -> doPublish(s)
            else -> throw BuildError("неизвестный тип шага «${s.op}»", "Допустимо: download, shell, doctor, clean, deps, manifest, aapt-compile, aapt-link, kotlinc, javac, dex, pack, sign, verify, publish")
        }
    }

    private class Dl(val url: String, val dest: File, val into: File, val unzip: Boolean, val exec: Boolean, val marker: File, val stamp: File, val sha: String?)

    private fun dl(s: Stage): Dl = dlOf(s.title, s.spec)

    private fun urlOf(sp: JObj): String {
        val urls = sp.obj("urls")
        return urls?.let { u -> pf.abis.firstNotNullOfOrNull { u[it] as? String } } ?: sp.str("url")
    }

    private fun dlOf(title: String, sp: JObj): Dl {
        val path = sp.str("path")
        if (path.isEmpty()) throw BuildError("в описании «$title» нет поля path")
        val url = urlOf(sp)
        val dest = File(root, path)
        val unzip = sp.bool("unzip")
        val stamp = File(root, "$path.done")
        val marker = when {
            sp.str("check").isNotEmpty() -> File(root, sp.str("check"))
            unzip -> stamp
            else -> dest
        }
        val into = if (sp.str("into").isNotEmpty()) File(root, sp.str("into")) else dest.parentFile ?: root
        return Dl(url, dest, into, unzip, sp.bool("exec"), marker, stamp, sp.str("sha256").ifEmpty { null })
    }

    private fun isDone(d: Dl) = d.marker.exists() && (!d.unzip || d.stamp.exists())

    private fun doDownload(s: Stage, i: Int) {
        val d = dl(s)
        if (s.spec.bool("force")) { d.marker.delete(); d.stamp.delete(); d.dest.delete() }
        if (isDone(d)) { s.note = "в кэше"; s.progress = 1f; return }
        if (d.url.isEmpty() && s.spec.str("asset").isEmpty() && s.spec.strList("maven").isEmpty()) throw BuildError("для ABI ${pf.abis.joinToString()} нет ссылки", "Добавь в «urls» ключ ${pf.abis.firstOrNull() ?: "arm64-v8a"}")
        val bundled = s.spec.str("asset")
        if (s.spec.strList("maven").isNotEmpty()) {
            prefetchMaven(s, i)
            d.dest.parentFile?.mkdirs(); d.dest.writeText("ok")
            return
        }
        if (bundled.isNotEmpty()) {
            console.add(c("90", "▣ встроено в приложение: $bundled"))
            val inp = pf.assetStream(bundled) ?: throw BuildError("в приложении нет встроенного файла $bundled", "Переустанови приложение из свежей сборки")
            d.dest.parentFile?.mkdirs()
            val total = s.spec.int("size").toLong()
            inp.use { ins -> d.dest.outputStream().use { o ->
                val buf = ByteArray(65536); var done = 0L
                while (true) {
                    if (cancel) throw Cancelled()
                    val n = ins.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) s.progress = (done.toFloat() / total).coerceAtMost(1f)
                    s.detail = "копирую · ${Downloader.human(done)}"; notifyStage(i)
                }
            } }
        } else {
        val host = runCatching { java.net.URL(d.url).host }.getOrDefault(d.url)
        console.add(c("90", "↓ ${d.url}"))
        s.detail = host
        var lastLog = -10
        Downloader { cancel }.fetch(d.url, d.dest, d.sha,
            onRetry = { n, msg -> console.add(c("33", "  повтор $n: $msg")); s.detail = "повтор $n · $msg"; notifyStage(i, true) },
            onProgress = { done, total, bps ->
                s.progress = if (total > 0) done.toFloat() / total else -1f
                s.detail = if (total > 0) "${Downloader.human(done)} / ${Downloader.human(total)}" + (if (bps > 0) " · ${Downloader.human(bps)}/с" else "")
                else "${Downloader.human(done)}" + (if (bps > 0) " · ${Downloader.human(bps)}/с" else "")
                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                if (pct >= 0 && pct >= lastLog + 25) { lastLog = pct - pct % 25; console.add(c("90", "  $pct% · ${s.detail}")) }
                notifyStage(i)
            })
        }
        if (d.unzip) {
            s.progress = 0f
            Unzip.extract(d.dest, d.into, d.exec, { cancel }) { f, name -> s.progress = f; s.detail = "распаковка · $name"; notifyStage(i) }
            d.dest.delete()
            // dex-jar обязаны быть «только для чтения»: Android 14+ отказывается грузить записываемый dex
            d.into.walkTopDown().filter { it.isFile && it.extension == "jar" }.forEach { it.setWritable(false, false) }
            d.stamp.parentFile?.mkdirs(); d.stamp.createNewFile()
        } else if (d.exec) d.dest.setExecutable(true, false)
        if (!d.marker.exists())
            throw BuildError("после загрузки не найден ${d.marker.relativeTo(root).path}", "Проверь поля check/into в assets.json — пути считаются от папки toolchain")
        s.note = if (d.dest.exists()) Downloader.human(d.dest.length()) else "распаковано"
        s.spec.int("api").takeIf { it > 0 }?.let { runCatching { File(d.dest.path + ".api").writeText(it.toString()) } }
    }

    private fun shellEnv(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["TOOLCHAIN"] = root.path; m["PROJECT"] = projectDir.path; m["BUILD"] = buildDir.path
        m["OUT"] = outDir.path; m["APP"] = project; m["HOME"] = home.path
        m["MANIFEST"] = (File(buildDir, "AndroidManifest.xml").takeIf { it.isFile } ?: File(projectDir, "AndroidManifest.xml")).path
        m["LINK_FLAGS"] = File(buildDir, "link.flags").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        Tools.androidJar(root)?.let { m["ANDROID_JAR"] = it.path }
        tools.forEach { (n, f) -> m[n.uppercase()] = f.path }
        val dirs = (listOf(File(root, "bin")) + tools.values.mapNotNull { it.parentFile }).map { it.path }.distinct()
        m["PATH"] = (dirs + listOfNotNull(System.getenv("PATH"))).joinToString(":")
        return m
    }

    /** Окружение для терминала — те же переменные, что видит сборка. */
    fun env(): Map<String, String> { tools = Tools.locate(root); return shellEnv() }

    private fun doShell(s: Stage, i: Int) {
        val cmd = s.spec.strList("cmd").joinToString("\n")
        if (cmd.isBlank()) throw BuildError("в шаге «${s.title}» нет команды cmd")
        buildDir.mkdirs()
        console.add(c("90", "$ " + cmd.lines().first { it.isNotBlank() }.trim() + if (cmd.lines().count { it.isNotBlank() } > 1) "  …" else ""))
        val tail = ArrayDeque<String>()
        val code = shell.run(cmd, if (projectDir.isDirectory) projectDir else root, shellEnv()) { line ->
            tail.addLast(line); if (tail.size > 12) tail.removeFirst()
            console.add(line)
            s.detail = Ansi.parse(line, 0).text.trim()
            notifyStage(i)
        }
        if (cancel) throw Cancelled()
        if (code != 0) {
            val last = tail.map { Ansi.parse(it, 0).text.trim() }.filter { it.isNotEmpty() }.takeLast(4).joinToString("\n")
            throw BuildError("команда завершилась с кодом $code" + if (last.isNotEmpty()) "\n$last" else "", s.spec.str("tip").ifEmpty { null })
        }
    }

    private fun rel(f: File) = if (f.path.startsWith(root.path)) f.relativeTo(root).path else f.path

    private fun doDoctor(s: Stage) { s.note = "${diagnose()} инструментов" }

    /** Проверка окружения: инструменты, проект, место. Бросает BuildError с подсказкой. Возвращает число нужных инструментов. */
    fun diagnose(): Int {
        tools = Tools.locate(root)
        val required = stages.filter { it.group == Group.BUILD && !it.spec.bool("optional") && conditionFailure(it) == null }
            .flatMap { it.spec.strList("requires") }.distinct()
        console.add(c("1", "Инструменты") + c("90", "  (ABI: ${pf.abis.joinToString()})"))
        val missing = ArrayList<String>()
        for (n in required) {
            if (n == "android.jar") {
                val j = Tools.androidJar(root)
                if (j == null) { missing.add(n); console.add(c("31", "  ✗ android.jar")) } else console.add(c("32", "  ✓ android.jar") + c("90", "  ${rel(j)} · ${Downloader.human(j.length())}"))
                continue
            }
            val f = tools[n]
            if (f == null) { missing.add(n); console.add(c("31", "  ✗ $n")); continue }
            // проверяем, что бинарник реально запускается на этом устройстве
            val probe = when (n) { "aapt2" -> "\"$f\" version"; "aapt" -> "\"$f\" v"; "zipalign" -> null; else -> null }
            var ver = ""
            if (probe != null) {
                val out = ArrayList<String>()
                val code = try { Shell().run("$probe 2>&1", root, shellEnv()) { out.add(it) } } catch (e: Exception) { out.add(e.message ?: ""); -1 }
                if (code != 0 && out.none { it.contains("ersion") }) {
                    throw BuildError("$n найден, но не запускается: ${out.firstOrNull() ?: "код $code"}",
                        "Проверь, что скачана сборка под ${pf.abis.firstOrNull()}, и что targetSdk приложения не выше 28 (иначе Android запрещает запуск из своей папки)")
                }
                ver = out.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            }
            console.add(c("32", "  ✓ $n") + c("90", "  ${rel(f)}${if (ver.isNotEmpty()) " · $ver" else ""}"))
        }
        val opt = Tools.WANTED.filter { it !in required && tools.containsKey(it) }
        if (opt.isNotEmpty()) console.add(c("90", "  также найдено: ${opt.joinToString()}"))
        if (missing.isNotEmpty()) throw BuildError("Не хватает: ${missing.joinToString()}", TIP_TOOLS)

        console.add(c("1", "Проект") + c("90", "  ${projectDir.path}"))
        if (!File(projectDir, "AndroidManifest.xml").isFile)
            throw BuildError("в проекте «$project» нет AndroidManifest.xml", "Загрузи проект командой: pull <ссылка-на-zip> [имя] — или выбери другой: projects, use <имя>")
        classifyProject()
        val free = home.usableSpace
        console.add(c("90", "  свободно ${Downloader.human(free)}"))
        if (free < 64L shl 20) throw BuildError("мало места: ${Downloader.human(free)}", "Освободи хотя бы 64 МБ")
        return required.size
    }

    private fun gradleInfo(): GradleInfo? {
        // свежий разбор исходного build.gradle (с версионным каталогом) точнее, чем сохранённый при импорте json
        File(projectDir, ".build.gradle").takeIf { it.isFile }?.let { gf ->
            val cat = File(projectDir, ".libs.versions.toml").takeIf { it.isFile }?.let { VersionCatalog(it.readText()) }
            val extra = listOf(".build.root.gradle", ".gradle.properties").mapNotNull { File(projectDir, it).takeIf { f -> f.isFile }?.readText() }.joinToString("\n")
            try { return Gradle.parse(gf.readText(), cat, extra) } catch (_: Exception) {}
        }
        return File(projectDir, ".gradle.json").takeIf { it.isFile }?.let { GradleInfo.fromJson(it.readText()) }
    }

    /**
     * Готовит манифест для aapt2: Gradle-проекты хранят package в build.gradle (namespace), а aapt2 требует его в манифесте;
     * подставляет ${applicationId} и manifestPlaceholders, версии и SDK передаёт флагами линковки.
     */
    private fun doManifest(s: Stage) {
        val src = File(projectDir, "AndroidManifest.xml")
        if (!src.isFile) throw BuildError("в проекте «$project» нет AndroidManifest.xml", "Выбери другой проект или импортируй zip заново")
        var text = src.readText()
        val gi = gradleInfo()
        val tag = Regex("<manifest\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).find(text) ?: throw BuildError("в AndroidManifest.xml нет тега <manifest>")
        val pkg = Regex("\\bpackage\\s*=\\s*\"([^\"]*)\"").find(tag.value)?.groupValues?.get(1)
        val guessed = if (pkg == null && gi?.namespace == null && gi?.applicationId == null) guessPackage() else null
        val ns = pkg ?: gi?.namespace ?: gi?.applicationId ?: guessed!!
        if (pkg == null) {
            text = text.replaceRange(tag.range, tag.value.replaceFirst("<manifest", "<manifest package=\"$ns\""))
            console.add(c(if (guessed != null) "33" else "90", if (guessed != null) "  package не задан — выбран автоматически: $ns" else "  package из Gradle: $ns"))
            if (guessed != null) s.tip = "package выбран автоматически ($ns); чтобы задать свой, добавь его в <manifest>"
        }
        val appId = gi?.applicationId ?: ns
        var autoCode = -1
        if (autoVersion()) {
            // секунды с 2023-11-14: помещается в Int и всегда больше предыдущей сборки
            autoCode = ((System.currentTimeMillis() / 1000) - 1_700_000_000L).toInt()
            val t = Regex("<manifest\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).find(text)!!
            val newTag = if (Regex("android:versionCode\\s*=").containsMatchIn(t.value))
                t.value.replace(Regex("android:versionCode\\s*=\\s*\"[^\"]*\""), "android:versionCode=\"$autoCode\"")
            else t.value.replaceFirst("<manifest", "<manifest android:versionCode=\"$autoCode\"")
            text = text.replaceRange(t.range, newTag)
        }
        val ph = LinkedHashMap<String, String>(); ph["applicationId"] = appId; ph["packageName"] = appId; gi?.placeholders?.let { ph.putAll(it) }
        var replaced = 0
        for ((k, v) in ph) { val key = "\${$k}"; if (text.contains(key)) { text = text.replace(key, v); replaced++ } }
        val aars = libs.filter { it.aar && !it.provided }.mapNotNull { l -> l.manifest?.let { m -> val x = m.readText(); (Jvm.packageOf(x) ?: l.group) to x } }
        if (aars.isNotEmpty()) try {
            val (merged, added) = Jvm.mergeManifests(text, appId, aars)
            text = merged
            console.add(c("90", "  манифесты библиотек: ${aars.size} · добавлено элементов: $added"))
        } catch (e: Exception) { console.add(c("33", "  ⚠ слияние манифестов не удалось: ${e.message}")) }
        val left = Regex("\\$\\{(\\w+)\\}").findAll(text).map { it.groupValues[1] }.toSet()
        if (left.isNotEmpty()) console.add(c("33", "  ⚠ подстановки без значения остались как есть: ${left.joinToString { "\${$it}" }}"))
        buildDir.mkdirs()
        File(buildDir, "AndroidManifest.xml").writeText(text)

        val flags = ArrayList<String>()
        fun declared(attr: String) = Regex("android:$attr\\s*=").containsMatchIn(text)
        gi?.minSdk?.let { if (!Regex("minSdkVersion").containsMatchIn(text)) flags += listOf("--min-sdk-version", it.toString()) }
        gi?.targetSdk?.let { if (!Regex("targetSdkVersion").containsMatchIn(text)) flags += listOf("--target-sdk-version", it.toString()) }
        gi?.versionCode?.let { if (!declared("versionCode")) flags += listOf("--version-code", it.toString()) }
        gi?.versionName?.let { if (!declared("versionName")) flags += listOf("--version-name", it.replace(Regex("[^A-Za-z0-9._+-]"), "_")) }
        if (appId != ns) flags += listOf("--rename-manifest-package", appId, "--rename-instrumentation-target-package", appId)
        File(buildDir, "link.flags").writeText(flags.joinToString(" "))
        s.note = (if (appId != ns) "$ns → $appId" else ns) + (if (autoCode > 0) " · версия $autoCode" else "") + if (replaced > 0) " · подстановок: $replaced" else ""
    }

    private val codeExt = setOf("kt", "java")

    /** Пакет для манифеста без package: из объявлений `package …` в коде, иначе из имени проекта. */
    private fun guessPackage(): String {
        val seen = HashMap<String, Int>()
        var n = 0
        for (f in projectDir.walkTopDown().onEnter { it.name != "build" && !it.name.startsWith(".") }) {
            if (!f.isFile || f.extension !in codeExt) continue
            if (++n > 300) break
            val head = try { f.inputStream().use { it -> val buf = ByteArray(4096); val k = it.read(buf); String(buf, 0, maxOf(k, 0), Charsets.UTF_8) } } catch (e: Exception) { continue }
            Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)").find(head)?.groupValues?.get(1)?.let { seen.merge(it, 1, Int::plus) }
        }
        if (seen.isNotEmpty()) {
            // общий префикс самых частых пакетов (не короче двух сегментов)
            val top = seen.entries.sortedByDescending { it.value }.map { it.key.split('.') }
            var pre = top.first()
            for (t in top.drop(1)) { val k = pre.zip(t).takeWhile { it.first == it.second }.size; if (k >= 2) pre = pre.take(k) }
            if (pre.size >= 2) return pre.joinToString(".")
            return top.first().joinToString(".")
        }
        val slug = project.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').let { if (it.isEmpty() || it[0].isDigit()) "app_$it".trimEnd('_') else it }
        return "com.example.$slug"
    }

    /** Сколько файлов кода в проекте и есть ли готовый dex. */
    private fun codeFiles(): Int = File(projectDir, "src").takeIf { it.isDirectory }?.walkTopDown()?.count { it.isFile && it.extension in codeExt } ?: 0

    private fun hasCode(ext: String) = Jvm.files(File(projectDir, "src"), ext).isNotEmpty()

    private fun classifyProject() {
        val gi = gradleInfo()
        val kt = hasCode("kt"); val jv = hasCode("java")
        val kind = when {
            kt || jv -> "приложение с кодом (" + listOfNotNull(if (kt) "Kotlin ${Jvm.KOTLIN}" else null, if (jv) "Java" else null).joinToString(" + ") + ", ${codeFiles()} файлов)"
            dexFiles().isNotEmpty() -> "готовый dex + ресурсы"
            else -> "только ресурсы"
        }
        console.add(c("90", "  тип: $kind" + (gi?.let { " · Gradle, namespace ${it.namespace ?: "—"}" } ?: "")))
        if (kt && tools["kotlinc"] == null) throw BuildError("в проекте есть Kotlin, а компилятор не установлен", TIP_TOOLS)
        if (jv && tools["ecjdex"] == null) throw BuildError("в проекте есть Java, а компилятор не установлен", TIP_TOOLS)
        if ((kt || jv || (gi?.deps?.isNotEmpty() == true)) && tools["d8dex"] == null) throw BuildError("не установлен d8 (превращает код в classes.dex)", TIP_TOOLS)
        gi?.deps?.filter { it.startsWith("libs.") }?.let { if (it.isNotEmpty()) console.add(c("33", "  ⚠ зависимости из версионного каталога не прочитаны (${it.size}): добавь gradle/libs.versions.toml в архив")) }
        if (gi?.processors?.isNotEmpty() == true) console.add(c("33", "  ⚠ kapt/ksp/annotationProcessor (${gi.processors.size}) на телефоне не выполняются — сгенерированный ими код не появится"))
        if (File(projectDir, "cpp").isDirectory) console.add(c("90", "  cpp/: нативный код не собирается (нет NDK), берутся готовые .so из jniLibs/"))
    }

    // ---------------------------------------------------------------- JVM-часть: зависимости, ресурсы, Kotlin, Java, dex

    private var libs: List<Lib> = emptyList()
    private var compose = false

    private fun compileJars(): List<File> = libs.flatMap { it.classJars }
    private fun androidJarOrFail(): File = Tools.androidJar(root) ?: throw BuildError("нет android.jar", TIP_TOOLS)
    private fun heap(cap: Int) = Jvm.heapMb(pf.abis, cap)

    /** Выбирает способ запуска компиляторов: dalvikvm (куча любого размера), иначе app_process (до 256 МБ). Проверяется один раз. */
    private fun probeVm() {
        if (Jvm.dalvik != null || vmProbed) return
        vmProbed = true
        val d8 = tools["d8dex"] ?: return
        for (bin in listOf("/apex/com.android.art/bin/dalvikvm64", "/apex/com.android.art/bin/dalvikvm")) {
            if (!File(bin).exists()) continue
            val out = ArrayList<String>()
            val code = try { Shell().run("$bin -Xmx512m -cp ${Jvm.q(d8.path)} com.android.tools.r8.D8 --version 2>&1", root, shellEnv()) { out.add(it) } } catch (_: Exception) { -1 }
            if (code == 0 && out.any { it.contains("D8") }) { Jvm.dalvik = bin; console.add(c("90", "  JVM: ${bin.substringAfterLast('/')} (куча без ограничения)")); return }
        }
        console.add(c("33", "  ⚠ dalvikvm недоступен — компиляторы идут через app_process (куча до 256 МБ, большие проекты могут не поместиться)"))
    }
    private var vmProbed = false

    private fun jvmProps(): List<String> {
        val tmp = File(buildDir, "tmp").also { it.mkdirs() }
        return listOf(
            "-Djava.io.tmpdir=${tmp.path}", "-Duser.home=${home.path}",
            "-Djavax.xml.stream.XMLInputFactory=com.ctc.wstx.stax.WstxInputFactory",
            "-Djavax.xml.stream.XMLOutputFactory=com.ctc.wstx.stax.WstxOutputFactory",
            "-Djavax.xml.stream.XMLEventFactory=com.ctc.wstx.stax.WstxEventFactory",
        )
    }

    /** Запускает команду, собирает хвост вывода; при отмене бросает Cancelled, при ненулевом коде — BuildError. */
    private fun runChecked(cmd: String, s: Stage, i: Int, what: String, tip: String?, cwd: File = projectDir): List<String> {
        buildDir.mkdirs()
        val tail = ArrayDeque<String>()
        console.add(c("90", "$ " + cmd.removePrefix("exec ").take(160)))
        val code = shell.run(cmd, if (cwd.isDirectory) cwd else root, shellEnv()) { line ->
            tail.addLast(line); if (tail.size > 80) tail.removeFirst()
            console.add(line)
            val t = Ansi.parse(line, 0).text.trim()
            if (t.isNotEmpty()) { s.detail = t; notifyStage(i) }
        }
        if (cancel) throw Cancelled()
        if (code != 0) {
            val last = Jvm.errorLines(tail.toList()).joinToString("\n")
            throw BuildError("$what: код $code" + if (last.isNotEmpty()) "\n$last" else "", tip)
        }
        return tail.toList()
    }

    /** Предзагрузка библиотек (Compose, AndroidX…) в общий кэш, чтобы сборки дальше шли без интернета. */
    private fun prefetchMaven(s: Stage, i: Int) {
        val mv = Maven(File(home, "m2"), { cancel }, { console.add(c("90", it)) }, { f, d -> s.progress = f * 0.9f; s.detail = d; notifyStage(i) })
        val direct = s.spec.strList("maven").mapNotNull { mv.coord(it) }
        val boms = s.spec.strList("boms").mapNotNull { mv.coord(it) }
        s.progress = 0f
        val all = mv.resolve(direct, boms, emptyList(), Jvm.DEFAULT_BOMS)
        // ресурсы библиотек компилируем сразу: первая сборка тоже будет быстрой
        val aapt = tools["aapt2"] ?: Tools.locate(root)["aapt2"]
        if (aapt != null) {
            val todo = all.filter { it.res != null && !it.rCompiled.isFile }
            for ((k, l) in todo.withIndex()) {
                if (cancel) throw Cancelled()
                s.progress = 0.9f + 0.1f * k / todo.size.coerceAtLeast(1); s.detail = "ресурсы · ${l.artifact}"; notifyStage(i)
                val tmp = File(l.rCompiled.path + ".tmp"); tmp.delete()
                val code = Shell().run("exec ${Jvm.q(aapt.path)} compile --dir ${Jvm.q(l.res!!.path)} -o ${Jvm.q(tmp.path)}", root, shellEnv()) { }
                if (code == 0) { l.rCompiled.delete(); tmp.renameTo(l.rCompiled) } else tmp.delete()
            }
        }
        s.progress = 1f
        s.note = "${all.size} библиотек · ${Downloader.human(all.sumOf { it.file?.length() ?: 0L })}"
    }

    private fun doDeps(s: Stage, i: Int) {
        probeVm()
        val gi = gradleInfo()
        val kt = hasCode("kt")
        val mv = Maven(File(home, "m2"), { cancel }, { console.add(c("90", it)) }, { f, d -> s.progress = f; s.detail = d; notifyStage(i) }, fresh = freshLibs())
        val direct = ArrayList<Maven.Coord>()
        for (d in gi?.deps.orEmpty()) {
            var co = mv.coord(d)
            if (co?.v?.contains('$') == true) { console.add(c("33", "  ⚠ версия не определена: $d — беру из BOM или последнюю")); co = Maven.Coord(co.g, co.a, null) }
            if (co != null && !d.startsWith("libs.")) direct.add(co)
        }
        if (kt || gi?.kotlin == true) direct.add(Maven.Coord("org.jetbrains.kotlin", "kotlin-stdlib", Jvm.KOTLIN))
        val boms = gi?.boms.orEmpty().mapNotNull { if (it.startsWith("libs.")) null else mv.coord(it) }
        val prov = gi?.compileOnly.orEmpty().mapNotNull { if (it.startsWith("libs.")) null else mv.coord(it) }
        if (direct.isEmpty() && prov.isEmpty()) { libs = emptyList(); compose = false; throw Skip("зависимостей нет") }
        console.add(c("90", "  зависимости: ${direct.size} · BOM: ${boms.size}"))
        s.progress = 0f
        libs = mv.resolve(direct, boms, prov, Jvm.DEFAULT_BOMS)
        compose = libs.any { it.group == "androidx.compose.runtime" }
        s.progress = 1f
        val mb = libs.sumOf { it.file?.length() ?: 0L }
        s.note = "${libs.size} библиотек · ${Downloader.human(mb)}" + if (compose) " · Compose" else ""
    }

    private fun doAaptCompile(s: Stage, i: Int) {
        val aapt = tools["aapt2"] ?: throw BuildError("нет aapt2", TIP_TOOLS)
        val out = File(buildDir, "compiled").also { it.mkdirs() }
        val jobs = ArrayList<Triple<String, File, File>>() // подпись, каталог res, куда класть zip
        File(projectDir, "res").takeIf { it.isDirectory }?.let { jobs.add(Triple("приложение", it, File(out, "app.zip"))) }
        for (l in libs) { val r = l.res ?: continue; if (!l.provided && !l.rCompiled.isFile) jobs.add(Triple(l.artifact + " " + l.version, r, l.rCompiled)) }
        if (jobs.isEmpty()) { s.note = "ресурсов нет"; return }
        for ((k, j) in jobs.withIndex()) {
            if (cancel) throw Cancelled()
            s.progress = k.toFloat() / jobs.size; s.detail = "${j.first} · ${k + 1}/${jobs.size}"; notifyStage(i, true)
            val tmp = File(j.third.path + ".tmp"); tmp.delete()
            runChecked("exec ${Jvm.q(aapt.path)} compile --dir ${Jvm.q(j.second.path)} -o ${Jvm.q(tmp.path)}", s, i, "aapt2 compile (${j.first})",
                "Ошибка в res/: aapt2 назвал файл и строку выше — исправь XML и запусти снова")
            j.third.delete(); tmp.renameTo(j.third)
        }
        s.note = "${jobs.size} " + if (jobs.size == 1) "набор" else "наборов"
    }

    private fun doAaptLink(s: Stage, i: Int) {
        val aapt = tools["aapt2"] ?: throw BuildError("нет aapt2", TIP_TOOLS)
        val aj = androidJarOrFail()
        val manifest = File(buildDir, "AndroidManifest.xml").takeIf { it.isFile } ?: File(projectDir, "AndroidManifest.xml")
        val base = File(buildDir, "base.apk")
        val gen = File(buildDir, "gen").also { it.mkdirs() }
        val args = ArrayList<String>()
        args += listOf("link", "-I", aj.path, "--manifest", manifest.path, "--auto-add-overlay", "-o", base.path, "--java", gen.path)
        val appPkg = Jvm.packageOf(manifest.readText())
        val extra = LinkedHashSet<String>()
        for (l in libs) {
            if (l.provided) continue
            if (l.rCompiled.isFile) args += listOf("-R", l.rCompiled.path)
            l.manifest?.let { m -> Jvm.packageOf(m.readText())?.let { p -> if (p != appPkg) extra.add(p) } }
        }
        File(buildDir, "compiled/app.zip").takeIf { it.isFile }?.let { args += listOf("-R", it.path) }
        File(projectDir, "assets").takeIf { it.isDirectory }?.let { args += listOf("-A", it.path) }
        for (l in libs) if (!l.provided) l.assets?.let { args += listOf("-A", it.path) }
        if (extra.isNotEmpty()) args += listOf("--extra-packages", extra.joinToString(":"))
        File(buildDir, "link.flags").takeIf { it.isFile }?.readText()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.let { args += it }
        runChecked("exec " + Jvm.q(aapt.path) + " " + args.joinToString(" ") { Jvm.q(it) }, s, i, "aapt2 link",
            "Проверь AndroidManifest.xml: нужен package (или namespace в build.gradle) и корректные ссылки @string/…, @drawable/…")
        s.note = "base.apk " + Downloader.human(base.length()) + if (extra.isNotEmpty()) " · R: ${extra.size + 1} пакетов" else ""
    }

    private fun doKotlin(s: Stage, i: Int) {
        val kc = tools["kotlinc"] ?: throw BuildError("не установлен компилятор Kotlin", TIP_TOOLS)
        val aj = androidJarOrFail()
        val src = File(projectDir, "src"); val gen = File(buildDir, "gen")
        val out = File(buildDir, "kclasses").also { it.mkdirs() }
        val plugin = tools["composec"]
        val cp = listOf(aj) + compileJars()
        val args = ArrayList<String>()
        args += listOf("-kotlin-home", File(root, "jvm/home").also { it.mkdirs() }.path, "-no-jdk", "-no-stdlib", "-no-reflect", "-nowarn",
            "-jvm-target", "1.8", "-Xskip-metadata-version-check", "-Xskip-prerelease-check", "-module-name", project.replace(Regex("[^A-Za-z0-9_]"), "_"),
            "-cp", cp.joinToString(":") { it.path }, "-d", out.path)
        if (compose && plugin != null) args += "-Xplugin=${plugin.path}"
        else if (compose) console.add(c("33", "  ⚠ нет плагина Compose — @Composable не скомпилируются"))
        args += src.path
        if (gen.isDirectory && gen.walkTopDown().any { it.isFile }) args += gen.path
        val argFile = File(buildDir, "kotlinc.args"); argFile.writeText(args.joinToString("\n"))
        s.detail = "анализ ${Jvm.files(src, "kt").size} файлов" + if (compose) " · Compose" else ""; notifyStage(i, true)
        val classpath = listOfNotNull(tools["kpatch"], kc) + listOfNotNull(if (compose) plugin else null)
        val tail = runChecked(Jvm.appProcess(heap(2048), classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", listOf("@" + argFile.path), jvmProps()), s, i,
            "Kotlin: ошибки компиляции", "Исправь ошибки в коде (файл и строка указаны выше). Нехватка памяти? Закрой другие приложения")
        val n = out.walkTopDown().count { it.isFile && it.extension == "class" }
        s.note = "$n классов"
    }

    private fun doJava(s: Stage, i: Int) {
        val src = File(projectDir, "src"); val gen = File(buildDir, "gen")
        if (!hasCode("java") && !hasCode("kt")) throw Skip("кода нет")
        val files = Jvm.files(src, "java") + Jvm.files(gen, "java")
        if (files.isEmpty()) throw Skip("Java-файлов нет")
        val ecj = tools["ecjdex"] ?: throw BuildError("не установлен компилятор Java (ecj)", TIP_TOOLS)
        val out = File(buildDir, "classes").also { it.mkdirs() }
        val aj = androidJarOrFail()
        val cp = compileJars() + listOfNotNull(File(buildDir, "kclasses").takeIf { it.isDirectory })
        val argFile = File(buildDir, "ecj.args")
        // -bootclasspath обязателен: без него ecj ищет классы JDK (javax.lang.model…), которых в Android нет
        argFile.writeText((listOf("-proc:none", "-source", "1.8", "-target", "1.8", "-nowarn", "-encoding", "UTF-8", "-bootclasspath", aj.path,
            "-cp", cp.joinToString(":") { it.path }, "-d", out.path) + files.map { it.path }).joinToString("\n"))
        s.detail = "${files.size} файлов"; notifyStage(i, true)
        runChecked(Jvm.appProcess(heap(1024), listOf(ecj), "org.eclipse.jdt.internal.compiler.batch.Main", listOf("@" + argFile.path), jvmProps()), s, i,
            "Java: ошибки компиляции", "Исправь ошибки в коде (файл и строка указаны выше)")
        s.note = "${out.walkTopDown().count { it.isFile && it.extension == "class" }} классов"
    }

    private fun doDex(s: Stage, i: Int) {
        val dirs = listOf(File(buildDir, "kclasses"), File(buildDir, "classes")).filter { it.isDirectory && it.walkTopDown().any { f -> f.isFile } }
        val appJar = File(buildDir, "app.jar")
        val n = if (dirs.isEmpty()) 0 else Jvm.jarOf(dirs, appJar)
        val libJars = libs.filter { !it.provided }.flatMap { it.classJars }
        if (n == 0 && libJars.isEmpty()) throw Skip("кода нет")
        val d8 = tools["d8dex"] ?: throw BuildError("не установлен d8", TIP_TOOLS)
        val aj = androidJarOrFail()
        s.detail = "подготовка входов"; notifyStage(i, true)
        val inputs = Jvm.dedupe((if (n > 0) listOf(appJar) else emptyList()) + libJars, File(buildDir, "dedupe")) { console.add(c("90", it)) }
        val out = File(buildDir, "dex").also { it.deleteRecursively(); it.mkdirs() }
        val minApi = (gradleInfo()?.minSdk ?: 21).coerceIn(21, 36)
        val args = ArrayList<String>()
        args += listOf("--release", "--min-api", minApi.toString(), "--lib", aj.path, "--output", out.path)
        for (l in libs) if (l.provided) for (j in l.classJars) args += listOf("--classpath", j.path)
        args += inputs.map { it.path }
        s.detail = "${inputs.size} модулей → dex"; notifyStage(i, true)
        runChecked(Jvm.appProcess(heap(1536), listOf(d8), "com.android.tools.r8.D8", args, jvmProps()), s, i, "d8",
            "d8 не смог собрать dex (см. сообщение выше). Нехватка памяти? Закрой другие приложения и повтори")
        val dex = out.listFiles { f -> f.name.endsWith(".dex") }.orEmpty()
        if (dex.isEmpty()) throw BuildError("d8 не создал classes.dex")
        s.note = "${dex.size} dex · ${Downloader.human(dex.sumOf { it.length() })}"
    }

    /** Нативные библиотеки: файлы .so из jniLibs/<abi> проекта и jni/ из AAR → lib/<abi>/ в APK (без сжатия, по границе страницы). */
    private fun addNative(z: ZipOut): Int {
        var n = 0
        fun put(abiDir: File) {
            for (abi in abiDir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) for (so in abi.listFiles { f -> f.isFile && f.extension == "so" }.orEmpty().sortedBy { it.name }) {
                val name = "lib/${abi.name}/${so.name}"
                if (z.has(name)) continue
                z.addStored(name, so.length(), ZipOut.crcOf(so), 4096) { so.inputStream() }; n++
            }
        }
        File(projectDir, "jniLibs").takeIf { it.isDirectory }?.let(::put)
        for (l in libs) if (!l.provided) l.jni?.let(::put)
        return n
    }

    private fun doClean(s: Stage) {
        buildDir.deleteRecursively(); buildDir.mkdirs(); outDir.mkdirs()
        s.note = "build очищен"
    }

    private fun dexFiles(): List<File> {
        val built = File(buildDir, "dex").listFiles { f -> f.name.endsWith(".dex") }?.sortedBy { dexIndex(it.name) }.orEmpty()
        if (built.isNotEmpty()) return built
        return projectDir.listFiles { f -> f.isFile && f.name.matches(Regex("classes\\d*\\.dex")) }?.sortedBy { dexIndex(it.name) }.orEmpty()
    }

    private fun dexIndex(n: String) = n.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1

    private fun doPack(s: Stage, i: Int) {
        val base = File(buildDir, "base.apk")
        if (!base.isFile) throw BuildError("нет build/base.apk", "Шаг линковки ресурсов должен создать base.apk")
        val unsigned = File(buildDir, "unsigned.apk")
        val dex = dexFiles()
        if (codeFiles() > 0 && dex.isEmpty())
            throw BuildError("полный APK не собран: исходный код проекта не попал в classes.dex", "Проверь шаги компиляции Java/Kotlin. APK только с ресурсами не будет запускать приложение.")
        ZipOut(unsigned).use { z ->
            z.copyFrom(base, forceStored = { it == "resources.arsc" })
            dex.forEachIndexed { k, f ->
                val n = if (k == 0) "classes.dex" else "classes${k + 1}.dex"
                if (!z.has(n)) {
                    // крупный dex кладём без сжатия потоком: сжатие в памяти для десятков МБ слишком тяжело для телефона
                    if (f.length() > 8L shl 20) z.addStored(n, f.length(), ZipOut.crcOf(f)) { f.inputStream() } else z.add(n, f.readBytes(), false)
                }
                console.add(c("90", "  + $n  ${Downloader.human(f.length())}"))
            }
            val so = addNative(z)
            if (so > 0) console.add(c("90", "  + нативные библиотеки: $so"))
            val res = Jvm.addJavaResources(z, libs.filter { !it.provided }.flatMap { it.classJars })
            if (res > 0) console.add(c("90", "  + ресурсы из библиотек: $res"))
            s.note = "${z.count} файлов"
        }
        console.add(c("90", "  unsigned.apk · ${Downloader.human(unsigned.length())} · dex: ${dex.size}"))
        if (dex.isEmpty()) console.add(c("33", "  в APK нет кода (classes.dex) — это пакет только с ресурсами"))
    }

    private fun doSign(s: Stage, i: Int) {
        val unsigned = File(buildDir, "unsigned.apk")
        if (!unsigned.isFile) throw BuildError("нет build/unsigned.apk")
        val id = ApkSigner.loadOrCreate(File(home, "keys"))
        console.add(c("90", "  ключ RSA-2048 · SHA-256 ${id.fingerprint.take(16)}…"))
        val v1 = File(buildDir, "signed-v1.apk")
        val n = ApkSigner.sign(unsigned, v1, id) { name -> s.detail = "подпись v1 · $name"; notifyStage(i) }
        s.detail = "подпись v2 · весь файл"; notifyStage(i, true)
        ApkSigner.addV2(v1, File(buildDir, "signed.apk"), id)
        v1.delete()
        s.note = "$n записей · v1 + v2"
    }

    private fun doVerify(s: Stage) {
        val signed = File(buildDir, "signed.apk")
        if (!signed.isFile) throw BuildError("нет build/signed.apk")
        val v = try { ApkSigner.verify(signed) } catch (e: BuildError) { throw e } catch (e: Exception) { throw BuildError("подпись не прошла проверку: ${e.message}") }
        val v2 = try { ApkSigner.verifyV2(signed) } catch (e: Exception) { throw BuildError("подпись v2 не прошла проверку: ${e.message}") }
        if (v2.fingerprint != v.fingerprint) throw BuildError("сертификаты v1 и v2 различаются")
        console.add(c("90", "  v1: ${v.files} файлов · v2: весь файл · сертификат ${v.fingerprint.take(16)}…"))
        s.note = "v1 + v2 · ${v.files} файлов"
    }

    private fun doPublish(s: Stage) {
        val signed = File(buildDir, "signed.apk")
        if (!signed.isFile) throw BuildError("нет build/signed.apk")
        outDir.mkdirs()
        val safe = project.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dst = File(outDir, "$safe-debug.apk")
        val tmp = File(outDir, "$safe-debug.apk.tmp")
        signed.copyTo(tmp, overwrite = true)
        dst.delete(); if (!tmp.renameTo(dst)) throw BuildError("не удалось сохранить ${dst.name}")
        val sha = Downloader.sha256Of(dst)
        var info = ""
        tools["aapt2"]?.let { aapt ->
            val out = ArrayList<String>()
            try { Shell().run("\"$aapt\" dump badging \"$dst\" 2>&1", root, shellEnv()) { out.add(it) } } catch (_: Exception) {}
            out.firstOrNull { it.startsWith("package:") }?.let { line ->
                val name = Regex("name='([^']*)'").find(line)?.groupValues?.get(1)
                val vn = Regex("versionName='([^']*)'").find(line)?.groupValues?.get(1)
                val vc = Regex("versionCode='([^']*)'").find(line)?.groupValues?.get(1)
                info = listOfNotNull(name, vn?.let { "v$it" + (vc?.let { n -> " ($n)" } ?: "") }).joinToString(" · ")
            }
        }
        val code = codeFiles()
        if (code > 0 && dexFiles().isEmpty()) {
            val w = "⚠ без кода: $code файлов .kt/.java не попали в APK — приложение не запустится"
            console.add(c("33", "  $w")); info = if (info.isEmpty()) w else "$info\n$w"
        }
        if (info.isNotEmpty()) console.add(c("90", "  ${info.lines().first()}"))
        console.add(c("90", "  sha256 $sha"))
        result = Result(dst, dst.length(), sha, System.currentTimeMillis() - runStartedAt, info)
        s.note = Downloader.human(dst.length())
    }

    // ---------------------------------------------------------------- проекты и история

    /** Скачивает zip с проектом и раскладывает в projects/<имя>. Вызывать из рабочего потока. */
    fun pull(url: String, name: String?, log: (String) -> Unit): String {
        val n = (name ?: url.substringBefore('?').substringAfterLast('/').removeSuffix(".zip").ifEmpty { "project" })
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmpZip = File(home, "work/pull.zip")
        val tmpDir = File(home, "work/pull")
        tmpDir.deleteRecursively(); tmpDir.mkdirs()
        var last = -25
        Downloader({ false }).fetch(url, tmpZip, null, onRetry = { a, m -> log("повтор $a: $m") }) { d, t, _ ->
            val pct = if (t > 0) (d * 100 / t).toInt() else -1
            if (pct >= last + 25) { last = pct - pct % 25; log("  $pct% · ${Downloader.human(d)}") }
        }
        Unzip.extract(tmpZip, tmpDir, false, { false }) { _, _ -> }
        Unzip.stripSingleRoot(tmpDir)
        tmpZip.delete()
        val dst = File(projectsDir, n)
        dst.deleteRecursively()
        if (!tmpDir.renameTo(dst)) { tmpDir.copyRecursively(dst, true); tmpDir.deleteRecursively() }
        return n
    }

    // ---------------------------------------------------------------- «что докачать»

    /** Размер файла по HEAD-запросу (для подсказки «≈ 28 МБ»); -1, если сервер не ответил. */
    private fun probeSize(url: String): Long = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ApkBuilder/2.0"); c.setRequestProperty("Accept-Encoding", "identity")
        val n = if (c.responseCode in 200..299) c.contentLengthLong else -1L
        c.disconnect(); n
    } catch (_: Exception) { -1L }

    /** API установленного android.jar: метка рядом с файлом, а если её нет — значение из описания загрузки. */
    private fun installedApi(): Int =
        File(root, "platforms/android.jar.api").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?: stages.firstOrNull { it.group == Group.TOOLCHAIN && "android.jar" in it.spec.strList("provides") }?.spec?.int("api") ?: 0

    private fun apiSpec(api: Int): JObj = mapOf(
        "name" to "android.jar · API $api", "url" to platformUrl.format(api), "path" to "platforms/android.jar",
        "api" to api.toDouble(), "provides" to listOf("android.jar"), "force" to true,
    )

    /**
     * Разбирает ошибку сборки и решает, что можно докачать: недостающие инструменты, повреждённые или
     * не запускающиеся файлы, android.jar под API проекта, оборванную загрузку. Ничего не качает —
     * только формирует предложение, подтверждает пользователь.
     */
    fun suggest(failed: Int, msg: String): List<Fix> {
        val out = LinkedHashMap<String, Fix>()
        val low = msg.lowercase()
        val st = stages.getOrNull(failed)
        val items = stages.filter { it.group == Group.TOOLCHAIN }
        fun provider(name: String) = items.firstOrNull { name in it.spec.strList("provides") }
        fun add(title: String, spec: JObj, why: String, force: Boolean) {
            val d = try { dlOf(title, spec) } catch (_: Exception) { return }
            val id = d.dest.relativeTo(root).path
            out.putIfAbsent(id, Fix(id, title, why, if (force) spec + ("force" to true) else spec))
        }
        fun addItem(s: Stage, why: String, force: Boolean) = add(s.title, s.spec, why, force)

        // 1) шаг требует инструмент, которого нет
        Regex("(?:Не хватает|Нет инструментов): (.+)").find(msg)?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.forEach { name ->
            provider(name)?.let { addItem(it, "нужен $name", false) }
        }
        // 2) оборвалась загрузка самого тулчейна
        if (st != null && st.group == Group.TOOLCHAIN) {
            val code = Regex("HTTP (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
            if (code == null || code !in 400..499 || code == 403 || code == 408 || code == 429)
                addItem(st, "загрузка оборвалась: ${msg.lineSequence().first()}", true)
        }
        // 3) бинарник есть, но не запускается
        if ("не запускается" in low || "exec format" in low || "cannot execute" in low || "bad system call" in low) {
            val name = Regex("^(\\S+) найден").find(msg)?.groupValues?.get(1) ?: "aapt2"
            (provider(name) ?: provider("aapt2"))?.let { addItem(it, "$name не запускается на этом устройстве — скачаю сборку заново", true) }
        }
        // 4) android.jar повреждён
        if ("include path" in low || "failed to open apk" in low || "end of central directory" in low || "zip file" in low && "invalid" in low || "android.jar" in low && ("corrupt" in low || "invalid" in low)) {
            provider("android.jar")?.let { addItem(it, "android.jar повреждён или загружен не до конца", true) }
        }
        // 5) нет ресурса фреймворка — возможно, проекту нужен более новый android.jar
        if (st?.group == Group.BUILD && "not found" in low && ("android:" in low || "attr/" in low || "style/" in low)) {
            val man = File(projectDir, "AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty()
            val need = listOf("targetSdkVersion", "compileSdkVersion", "platformBuildVersionCode")
                .mapNotNull { Regex(it + "\\s*=\\s*\"(\\d+)\"").find(man)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
            val have = installedApi()
            if (need > have && need in 3..37 && need != 20)
                add("android.jar · API $need", apiSpec(need), "проект собирается под API $need, а установлен " + (if (have > 0) "API $have" else "другой android.jar"), true)
        }

        val list = out.values.toList()
        fixes = list
        if (list.isNotEmpty()) {
            console.add(c("1;33", "⬇ Можно докачать:"))
            list.forEach { console.add(c("33", "  • ${it.title}") + c("90", "  ${it.why}")) }
            console.add(c("90", "  команда fix yes — скачать и собрать снова"))
            Thread {
                for (f in list) { f.bytes = probeSize(urlOf(f.spec)); listener?.onFixes() }
            }.apply { isDaemon = true; start() }
        }
        listener?.onFixes()
        return list
    }

    // ---- оценка времени: длительности шагов прошлых сборок этого проекта

    private val timesFile get() = File(home, "stage-times.json")
    private val times: MutableMap<String, Long> by lazy {
        val m = LinkedHashMap<String, Long>()
        try { (Json.parse(timesFile.readText()) as? JObj)?.forEach { (k, v) -> (v as? Number)?.let { m[k] = it.toLong() } } } catch (_: Exception) {}
        m
    }

    private fun timeKey(s: Stage) = "$project|${s.title}"

    /** Типичная длительность шага: из прошлых сборок, а для новых проектов — разумные значения по типу шага. */
    private fun expectedFor(s: Stage): Long {
        if (s.group != Group.BUILD) return 0L
        times[timeKey(s)]?.let { return it }
        return when (s.op) {
            "deps" -> 20_000L; "kotlinc" -> 35_000L; "javac" -> 4_000L; "dex" -> 70_000L
            "aapt-compile" -> 3_000L; "aapt-link" -> 1_500L; "pack" -> 3_000L; "sign" -> 2_000L
            else -> 300L
        }
    }

    private fun rememberTime(s: Stage) {
        if (s.elapsed < 150L) return
        synchronized(times) {
            val old = times[timeKey(s)]
            times[timeKey(s)] = if (old == null) s.elapsed else (old * 0.4 + s.elapsed * 0.6).toLong() // свежие замеры весят больше
            try { timesFile.writeText(Json.stringify(times.mapValues { it.value.toDouble() })) } catch (_: Exception) {}
        }
    }

    private fun weight(s: Stage): Long = if (s.expectedMs > 0L) s.expectedMs else if (s.group == Group.BUILD) expectedFor(s) else 2_000L

    /** Доля выполненной работы шага 0..1. */
    private fun fraction(s: Stage): Float = when (s.state) {
        St.DONE, St.SKIPPED -> 1f
        St.RUNNING -> s.shownProgress().let { if (it >= 0f) it else 0.3f }
        else -> 0f
    }

    /** Общий прогресс сборки 0..1: шаги взвешены по ожидаемой длительности, поэтому долгий dex весит больше, чем быстрая подпись. */
    fun overall(): Float {
        val st = stages
        if (st.isEmpty()) return 0f
        var total = 0.0; var done = 0.0
        for (s in st) { val w = weight(s).toDouble(); total += w; done += w * fraction(s) }
        return if (total <= 0.0) 0f else (done / total).toFloat().coerceIn(0f, 1f)
    }

    /** Сколько ещё ждать, мс; -1, если оценить нельзя. */
    fun etaMs(): Long {
        if (!running) return -1L
        var left = 0.0
        for (s in stages) {
            if (s.state == St.DONE || s.state == St.SKIPPED) continue
            val w = weight(s).toDouble()
            val rem = if (s.state == St.RUNNING) {
                val el = (System.currentTimeMillis() - s.startedAt).toDouble()
                val p = s.progress
                if (p > 0.02f) el * (1 - p) / p else maxOf(w - el, w * 0.1)
            } else w
            left += maxOf(rem, 0.0)
        }
        return left.toLong()
    }

    /** Все компоненты тулчейна для экрана первого запуска; уже установленные помечены как готовые. */
    fun components(): List<Fix> = stages.filter { it.group == Group.TOOLCHAIN }.mapNotNull { s ->
        val d = try { dl(s) } catch (_: Exception) { return@mapNotNull null }
        Fix(d.dest.relativeTo(root).path, s.title, s.hint, s.spec).also { if (isDone(d)) { it.state = FixState.DONE; it.progress = 1f } }
    }

    /** Размер загрузки по заголовкам ответа, -1 если сервер не сообщил. */
    fun probe(f: Fix): Long = f.spec.int("size").takeIf { it > 0 }?.toLong() ?: probeSize(urlOf(f.spec))

    /** Скачивает выбранные компоненты без последующей сборки. */
    fun install(sel: List<Fix>) = applyFixes(sel, false)

    /** Помечает готовыми те загрузки тулчейна, чьи файлы теперь на месте. */
    private fun refreshToolchain() {
        for (s in stages) if (s.group == Group.TOOLCHAIN) {
            val d = try { dl(s) } catch (_: Exception) { null }
            if (d != null && isDone(d)) { s.state = St.DONE; s.progress = 1f; s.error = null; s.tip = null; if (s.note.isEmpty()) s.note = "в кэше" }
            else if (s.state == St.ERROR || s.state == St.CANCELLED) s.reset()
        }
    }

    /** Качает выбранное (по умолчанию всё предложенное) и, если всё удалось, сразу запускает сборку заново. */
    fun applyFixes(sel: List<Fix> = fixes, rebuild: Boolean = true) {
        if (running || fixing) return
        val todo = sel.filter { it.state != FixState.DONE }
        if (todo.isEmpty()) return
        cancel = false; fixing = true; fixTodo = todo
        worker = Thread({
            for (f in todo) {
                if (cancel) break
                f.state = FixState.RUNNING; f.error = null; f.progress = -1f; f.detail = "подключаюсь…"
                listener?.onFixes()
                val st = Stage("fix", Group.TOOLCHAIN, f.title, "", f.spec)
                fixStage = st; fixCur = f
                console.add(c("1;33", "⬇ ${f.title}"))
                try {
                    doDownload(st, -1)
                    f.state = FixState.DONE; f.progress = 1f; f.detail = "готово"
                    console.add(c("32", "✓ ${f.title}"))
                } catch (e: Cancelled) {
                    f.state = FixState.OFFERED; f.detail = ""; console.add(c("33", "■ загрузка отменена")); break
                } catch (e: Throwable) {
                    f.state = FixState.ERROR; f.error = e.message ?: e.javaClass.simpleName
                    console.add(c("31", "✗ ${f.title}: ${f.error}"))
                }
                listener?.onFixes()
            }
            fixStage = null; fixCur = null
            tools = Tools.locate(root); refreshToolchain()
            val ok = todo.all { it.state == FixState.DONE }
            if (ok) fixes = fixes.filter { it !in todo }
            lastFixFailed = !ok
            fixing = false; fixTodo = emptyList()
            listener?.onFixes()
            if (ok && rebuild && !cancel) start()
        }, "fix").apply { isDaemon = true; start() }
    }

    fun dismissFixes() { if (!fixing) { fixes = emptyList(); listener?.onFixes() } }

    /**
     * Разбирает zip с проектом (в т. ч. из репозитория или Gradle-раскладки) и кладёт в projects/<имя>.
     * Ищет AndroidManifest.xml (самый неглубокий, не из build/) и делает его папку корнем проекта.
     * Вызывать из рабочего потока.
     */
    fun importZip(zip: File, wanted: String?, log: (String) -> Unit): String {
        if (running) throw BuildError("Идёт сборка — дождись конца", null)
        val n = (wanted ?: zip.name).removeSuffix(".zip").ifBlank { "project" }.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifEmpty { "project" }
        val tmp = File(home, "work/import")
        tmp.deleteRecursively(); tmp.mkdirs()
        try {
            try { Unzip.extract(zip, tmp, false, { false }) { _, _ -> } }
            catch (e: java.util.zip.ZipException) { throw BuildError("Это не zip-архив или он повреждён", "Выбери файл .zip с проектом") }
            Unzip.stripSingleRoot(tmp)

            // манифест: ближайший к корню, build/ и скрытые папки не считаем
            var best: File? = null; var bestDepth = Int.MAX_VALUE
            fun walk(d: File, depth: Int) {
                if (depth > 6) return
                for (k in d.listFiles().orEmpty().sortedBy { it.name }) {
                    if (k.isDirectory) { if (k.name != "build" && !k.name.startsWith(".") && k.name != "__MACOSX") walk(k, depth + 1) }
                    else if (k.name == "AndroidManifest.xml" && depth < bestDepth) { best = k; bestDepth = depth }
                }
            }
            walk(tmp, 0)
            val manifest = best ?: throw BuildError("В архиве нет AndroidManifest.xml", "Нужен проект с AndroidManifest.xml и папкой res/")
            val rootDir = manifest.parentFile!!
            if (rootDir != tmp) log("корень проекта: " + rootDir.relativeTo(tmp).path)

            // раскладка Gradle: java/ и kotlin/ → src/
            val src = File(rootDir, "src")
            for (alt in listOf("java", "kotlin")) File(rootDir, alt).takeIf { it.isDirectory }?.let { d ->
                if (!src.exists()) { d.renameTo(src); log("$alt/ → src/") }
                else if (src.canonicalPath != d.canonicalPath) { d.copyRecursively(src, true); d.deleteRecursively(); log("$alt/ → src/ (объединено)") }
            }

            // Gradle-описание модуля (namespace, applicationId, версии) лежит выше папки манифеста — забираем до переноса
            val gf = Gradle.findModuleFile(rootDir, tmp)
            val catFile = Gradle.findCatalog(rootDir, tmp)
            val catalog = catFile?.let { VersionCatalog(it.readText()) }
            // корневой build.gradle и gradle.properties: там часто лежат версии (ext, переменные)
            val rootText = listOf("build.gradle.kts", "build.gradle").map { File(tmp, it) }.firstOrNull { it.isFile && it != gf }?.readText()
            val propsText = File(tmp, "gradle.properties").takeIf { it.isFile }?.readText()
            val extraVars = listOfNotNull(rootText, propsText).joinToString("\n")
            val gi = gf?.let { Gradle.parse(it.readText(), catalog, extraVars) }
            val gradleText = gf?.readText(); val catalogText = catFile?.readText()
            val dst = File(projectsDir, n)
            if (dst.exists()) { dst.deleteRecursively(); log("проект $n заменён") }
            projectsDir.mkdirs()
            if (!rootDir.renameTo(dst)) { rootDir.copyRecursively(dst, true) }
            if (gi != null) {
                File(dst, ".gradle.json").writeText(gi.toJson())
                gradleText?.let { File(dst, ".build.gradle").writeText(it) }
                catalogText?.let { File(dst, ".libs.versions.toml").writeText(it) }
                if (extraVars.isNotBlank()) File(dst, ".build.root.gradle").writeText(extraVars)
                log("Gradle: ${gf.name} · namespace ${gi.namespace ?: "—"} · applicationId ${gi.applicationId ?: "—"} · ${gi.deps.size} зависимостей")
            }

            val res = File(dst, "res").isDirectory; val assets = File(dst, "assets").isDirectory
            val code = File(dst, "src").isDirectory
            val files = dst.walkTopDown().count { it.isFile }
            log("файлов: $files · манифест ✓ · res ${if (res) "✓" else "—"} · assets ${if (assets) "✓" else "—"} · код ${if (code) "есть" else "нет"}")
            if (code) log("код (src/) соберётся на телефоне: Kotlin, Java и зависимости Maven")
            return n
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun recordHistory(ok: Boolean, ms: Long) {
        try {
            val r = result
            val rec = LinkedHashMap<String, Any?>()
            rec["t"] = System.currentTimeMillis(); rec["project"] = project; rec["ok"] = ok; rec["ms"] = ms
            if (r != null) { rec["apk"] = r.apk.name; rec["size"] = r.size }
            File(home, "builds.jsonl").appendText(Json.stringify(rec) + "\n")
        } catch (_: Exception) {}
    }

    fun allRecords(): List<Rec> = Stats.parse(history(10000))

    fun history(n: Int = 8): List<JObj> =
        File(home, "builds.jsonl").takeIf { it.exists() }?.readLines()?.takeLast(n)
            ?.mapNotNull { runCatching { Json.parse(it) as? JObj }.getOrNull() }.orEmpty()

    val keysDir get() = File(home, "keys")
    fun keyFingerprint(): String? = ApkSigner.fingerprint(keysDir)
    fun importKey(src: File, password: String, alias: String?) = ApkSigner.importKey(keysDir, src, password, alias)
    fun exportKey(): File = File(home, "work/apk-tbuilder-key.zip").also { it.parentFile?.mkdirs(); it.delete(); ApkSigner.exportKey(keysDir, it) }
    fun resetKey() = ApkSigner.resetKey(keysDir)

    fun clearHistory() { if (!running) runCatching { File(home, "builds.jsonl").delete() } }

    fun resetToolchain() {
        if (running) return
        root.deleteRecursively(); root.mkdirs()
        syncCached(); tools = emptyMap()
    }

    // ---------------------------------------------------------------- мелочи

    private fun c(code: String, s: String) = "\u001B[${code}m$s\u001B[0m"

    companion object {
        /** Шаблон ссылки на android.jar нужного API (Sable/android-platforms: API 3–37, кроме 20). */
        @JvmField var platformUrl = "https://github.com/Sable/android-platforms/raw/master/android-%d/android.jar"
        const val TIP_TOOLS = "Запусти сборку — первые шаги докачают тулчейн. Свои инструменты добавляются в toolchain/extra.json (см. README)"

        fun fmtMs(ms: Long): String = if (ms < 1000) "$ms мс" else if (ms < 60_000) "%.1f с".format(ms / 1000.0) else "%d:%02d".format(ms / 60000, ms / 1000 % 60)
    }
}
