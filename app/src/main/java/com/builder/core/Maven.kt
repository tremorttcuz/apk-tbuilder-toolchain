package com.builder.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Библиотека после разрешения зависимостей: распакованный AAR или обычный jar. */
class Lib(val group: String, val artifact: String, val version: String, val aar: Boolean, val dir: File, val file: File?) {
    /** Не попадает в APK (compileOnly). */
    var provided = false
    val id get() = "$group:$artifact:$version"
    val classJars: List<File>
        get() {
            if (!aar) return listOfNotNull(file?.takeIf { it.isFile })
            val out = ArrayList<File>()
            File(dir, "classes.jar").takeIf { it.isFile && it.length() > 22 }?.let(out::add)
            File(dir, "libs").listFiles { f -> f.name.endsWith(".jar") }?.sortedBy { it.name }?.let(out::addAll)
            return out
        }
    val res: File? get() = File(dir, "res").takeIf { it.isDirectory && !it.listFiles().isNullOrEmpty() }
    val manifest: File? get() = File(dir, "AndroidManifest.xml").takeIf { it.isFile }
    val jni: File? get() = File(dir, "jni").takeIf { it.isDirectory }
    val assets: File? get() = File(dir, "assets").takeIf { it.isDirectory }
    val rCompiled: File get() = File(dir, "res.compiled.zip")
}

private class PDep(val g: String, val a: String, val v: String?, val scope: String, val optional: Boolean, val type: String, val excl: List<String>)

private class Pom(
    val g: String, val a: String, val v: String, val packaging: String,
    val deps: List<PDep>, val managed: Map<String, String>,
)

/**
 * Небольшой разрешитель зависимостей в духе Gradle: читает POM (родитель, свойства, BOM), берёт наибольшую версию
 * при конфликте, скачивает jar/aar в общий кэш и распаковывает AAR. Репозитории — Google Maven и Maven Central.
 */
class Maven(
    private val cache: File,
    private val cancelled: () -> Boolean,
    private val log: (String) -> Unit,
    private val progress: (Float, String) -> Unit,
    /** Google Maven: androidx, Material, Firebase. */
    private val google: String = "https://dl.google.com/dl/android/maven2",
    /**
     * Maven Central: сначала быстрое зеркало Google Cloud (без ограничений по частоте запросов), затем сам Central.
     * Если репозиторий ответил ошибкой (429, 5xx, обрыв), берётся следующий — раньше сборка падала на первом сбое.
     */
    private val central: List<String> = listOf(MIRROR, "https://repo1.maven.org/maven2"),
    /** Явный список репозиториев (для проверок без сети): перекрывает google/central. */
    private val repos: List<String>? = null,
    /** Всегда спрашивать у репозитория свежие версии (для зависимостей без версии и BOM по умолчанию); без сети берётся кэш. */
    private val fresh: Boolean = false,
) {
    private val poms = ConcurrentHashMap<String, Optional<Pom>>()
    private val pomLocks = ConcurrentHashMap<String, Any>()
    private val latestCache = HashMap<String, String>()
    private val versionCache = HashMap<String, List<String>>()
    private var fetched = 0

    private fun key(g: String, a: String) = "$g:$a"

    companion object {
        const val MIRROR = "https://maven-central.storage-download.googleapis.com/maven2"
        /** Параллельных загрузок и разборов POM. */
        const val THREADS = 8
        const val OFFLINE_TIP = "Библиотеки скачиваются один раз. Подключись к интернету и нажми «Повторить» — или поставь компонент «Библиотеки» в настройках, дальше сборка работает без сети"
    }
    private fun dirOf(g: String, a: String, v: String) = File(cache, "${g.replace('.', '/')}/$a/$v")

    // ------------------------------------------------------------------ сеть

    private fun download(rel: String, dest: File): Boolean {
        if (dest.isFile && dest.length() > 0) return true
        val miss = File(dest.path + ".404")
        if (miss.exists()) return false
        // сначала репозиторий, где такая группа обычно живёт; список версий (metadata) — только из первоисточников: зеркало отстаёт
        val googleFirst = rel.startsWith("androidx/") || rel.startsWith("com/android/") || rel.startsWith("com/google/android/") || rel.startsWith("com/google/ar/")
        var order = repos ?: if (googleFirst) listOf(google) + central else central + google
        if (rel.endsWith("maven-metadata.xml")) order = order.filter { it != MIRROR }
        var notFound = 0
        var http: HttpError? = null
        var io: java.io.IOException? = null
        for (r in order) {
            if (cancelled()) throw Cancelled()
            try {
                Downloader { cancelled() }.fetch("$r/$rel", dest, null, attempts = 2) { _, _, _ -> }
                return true
            } catch (e: HttpError) {
                // у зеркала на отсутствующий файл бывает и 403
                if (e.code == 404 || (r == MIRROR && e.code == 403)) notFound++ else http = e
            } catch (e: java.io.IOException) {
                io = e
            }
        }
        if (notFound == order.size) { miss.parentFile?.mkdirs(); miss.createNewFile(); return false }
        http?.let { throw BuildError("не удалось скачать $rel: ${it.message}", OFFLINE_TIP) }
        throw BuildError("нет доступа к сети: ${io?.message ?: io?.javaClass?.simpleName ?: "ошибка"}\n$rel", OFFLINE_TIP)
    }

    private fun text(rel: String, dest: File): String? = if (download(rel, dest)) dest.readText() else null

    // ------------------------------------------------------------------ версии

    private fun segs(v: String): List<Any> = v.split('.', '-', '_', '+').map { it.toIntOrNull() ?: it.lowercase() }

    private fun rank(q: String): Int = when {
        q.startsWith("snapshot") || q.startsWith("dev") -> 0
        q.startsWith("alpha") || q == "a" -> 1
        q.startsWith("beta") || q == "b" -> 2
        q.startsWith("m") && q.drop(1).all { it.isDigit() } -> 2
        q.startsWith("rc") || q.startsWith("cr") -> 3
        q == "final" || q == "ga" || q == "release" -> 5
        else -> 4
    }

    fun compare(a: String, b: String): Int {
        val x = segs(a); val y = segs(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i); val q = y.getOrNull(i)
            if (p == null) return if (q is Int) -1 else if (q is String) (if (rank(q) >= 5) -1 else 1) else 0
            if (q == null) return if (p is Int) 1 else if (p is String) (if (rank(p) >= 5) 1 else -1) else 0
            val c = when {
                p is Int && q is Int -> p.compareTo(q)
                p is Int -> 1
                q is Int -> -1
                else -> { val rp = rank(p as String); val rq = rank(q as String); if (rp != rq) rp.compareTo(rq) else p.compareTo(q) }
            }
            if (c != 0) return c
        }
        return 0
    }

    private fun pick(spec: String): String {
        // диапазоны [1.0,2.0) → нижняя граница; «1.2.+» → префикс без «+»
        val t = spec.trim()
        if (t.startsWith("[") || t.startsWith("(")) return t.trim('[', ']', '(', ')').split(',')[0].trim().ifEmpty { t }
        return t.removeSuffix(".+").removeSuffix("+")
    }

    /** Все версии из maven-metadata.xml (кэш обновляется раз в неделю; без сети берётся устаревший). */
    private fun versionsOf(g: String, a: String): List<String> {
        return versionCache.getOrPut(key(g, a)) {
            val rel = "${g.replace('.', '/')}/$a/maven-metadata.xml"
            val f = File(cache, rel)
            // список версий обновляем раз в неделю; без сети берём устаревший — сборка не должна ломаться офлайн
            val old = if (f.isFile) f.readText() else null
            if (old != null && (fresh || System.currentTimeMillis() - f.lastModified() > 7 * 86400_000L)) {
                f.delete()
                val newer = try { text(rel, f) } catch (e: BuildError) { null }
                if (newer == null) { f.writeText(old) }
            }
            val xml = text(rel, f) ?: throw BuildError("нет такой библиотеки: $g:$a", "Укажи версию явно: $g:$a:<версия>")
            Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
        }
    }

    private fun stableMax(vers: List<String>): String? {
        val stable = vers.filter { rank(segs(it).filterIsInstance<String>().firstOrNull() ?: "final") >= 4 }.ifEmpty { vers }
        return stable.maxWithOrNull { x, y -> compare(x, y) }
    }

    private fun latest(g: String, a: String): String =
        latestCache.getOrPut(key(g, a)) { stableMax(versionsOf(g, a)) ?: throw BuildError("у $g:$a нет версий") }

    /** Динамическая версия Gradle: «4.3.+», «1.+», «+» — самая новая стабильная из подходящих. */
    private fun isDynamic(spec: String) = spec.trim().endsWith("+") && !spec.trim().startsWith("[") && !spec.trim().startsWith("(")

    private fun concrete(g: String, a: String, spec: String): String {
        if (!isDynamic(spec)) return pick(spec)
        val prefix = spec.trim().removeSuffix("+")
        val ok = versionsOf(g, a).filter { it.startsWith(prefix) }
        return stableMax(ok) ?: throw BuildError("у $g:$a нет версии «$spec»", "Версии: " + versionsOf(g, a).takeLast(6).joinToString(", "))
    }

    // ------------------------------------------------------------------ POM

    private fun parsePom(g: String, a: String, v: String): Pom? {
        val k = "$g:$a:$v"
        poms[k]?.let { return it.orElse(null) }
        // один и тот же POM не качаем двумя потоками сразу: иначе они пишут в один и тот же .part
        synchronized(pomLocks.computeIfAbsent(k) { Any() }) {
            poms[k]?.let { return it.orElse(null) }
            val rel = "${g.replace('.', '/')}/$a/$v/$a-$v.pom"
            val xml = text(rel, File(dirOf(g, a, v), "$a-$v.pom"))
            val pom = if (xml == null) null else try { readPom(xml, g, a, v) } catch (e: BuildError) { throw e } catch (e: Exception) { log("  ⚠ POM $k: ${e.message}"); null }
            poms[k] = Optional.ofNullable(pom)
            return pom
        }
    }

    private fun child(e: Element, name: String): Element? { var n = e.firstChild; while (n != null) { if (n is Element && n.tagName == name) return n; n = n.nextSibling }; return null }
    private fun children(e: Element?, name: String): List<Element> { val out = ArrayList<Element>(); var n = e?.firstChild; while (n != null) { if (n is Element && n.tagName == name) out.add(n); n = n.nextSibling }; return out }
    private fun txt(e: Element?, name: String): String? = e?.let { child(it, name)?.textContent?.trim() }?.ifEmpty { null }

    private fun readPom(xml: String, g0: String, a0: String, v0: String): Pom {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
        val root = f.newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        val parentEl = child(root, "parent")
        val pg = txt(parentEl, "groupId"); val pa = txt(parentEl, "artifactId"); val pv = txt(parentEl, "version")
        val parent = if (pg != null && pa != null && pv != null) parsePom(pg, pa, pv) else null
        val g = txt(root, "groupId") ?: pg ?: g0
        val a = txt(root, "artifactId") ?: a0
        val v = txt(root, "version") ?: pv ?: v0
        val all = HashMap<String, String>()
        parent?.let { p -> parentProps["${p.g}:${p.a}:${p.v}"]?.let(all::putAll); all["parent.version"] = p.v; all["project.parent.version"] = p.v }
        var pn = child(root, "properties")?.firstChild
        while (pn != null) { if (pn is Element) all[pn.tagName] = pn.textContent.trim(); pn = pn.nextSibling }
        for (n in listOf("project.", "pom.", "")) { all["${n}groupId"] = g; all["${n}artifactId"] = a; all["${n}version"] = v }
        parentProps["$g:$a:$v"] = all
        fun sub(s: String?): String? {
            if (s == null) return null
            var r = s
            repeat(6) { val n = Regex("\\$\\{([^}]+)\\}").replace(r!!) { m -> all[m.groupValues[1]] ?: m.value }; if (n == r) return r; r = n }
            return r
        }
        val managed = HashMap<String, String>()
        parent?.managed?.let(managed::putAll)
        val depManagement = child(root, "dependencyManagement")?.let { child(it, "dependencies") }
        for (d in children(depManagement, "dependency")) {
            val dg = sub(txt(d, "groupId")) ?: continue; val da = sub(txt(d, "artifactId")) ?: continue; val dv = sub(txt(d, "version")) ?: continue
            if (txt(d, "scope") == "import" && txt(d, "type") == "pom") {
                parsePom(dg, da, pick(dv))?.managed?.forEach { (k, vv) -> managed.putIfAbsent(k, vv) }
            } else managed[key(dg, da)] = dv
        }
        val deps = ArrayList<PDep>()
        parent?.deps?.let(deps::addAll)
        for (d in children(child(root, "dependencies"), "dependency")) {
            val dg = sub(txt(d, "groupId")) ?: continue; val da = sub(txt(d, "artifactId")) ?: continue
            val excl = children(child(d, "exclusions"), "exclusion").mapNotNull { e -> key(txt(e, "groupId") ?: "*", txt(e, "artifactId") ?: "*") }
            deps.add(PDep(dg, da, sub(txt(d, "version")), txt(d, "scope") ?: "compile", txt(d, "optional") == "true", txt(d, "type") ?: "jar", excl))
        }
        return Pom(g, a, v, txt(root, "packaging") ?: "jar", deps, managed)
    }

    private val parentProps = ConcurrentHashMap<String, Map<String, String>>()

    // ------------------------------------------------------------------ разрешение

    class Coord(val g: String, val a: String, val v: String?)

    fun coord(s: String): Coord? {
        val p = s.trim().split(':')
        return if (p.size >= 2 && p[0].isNotBlank() && p[1].isNotBlank()) Coord(p[0], p[1], p.getOrNull(2)?.ifBlank { null }?.substringBefore('@')) else null
    }

    /** Разрешает дерево зависимостей, скачивает и распаковывает. Возвращает библиотеки в порядке обхода. */
    fun resolve(direct: List<Coord>, boms: List<Coord>, provided: List<Coord>, defaultBoms: Map<String, String> = emptyMap()): List<Lib> {
        val managed = LinkedHashMap<String, String>()
        for (b in boms) {
            val v = b.v?.let(::pick) ?: latest(b.g, b.a)
            val pom = parsePom(b.g, b.a, v) ?: throw BuildError("не найден BOM ${b.g}:${b.a}:$v")
            pom.managed.forEach { (k, vv) -> managed.putIfAbsent(k, vv) }
        }
        // запасной BOM для проектов, у которых его не удалось прочитать из Gradle (например androidx.compose.*)
        val needFallback = direct.any { it.v == null && key(it.g, it.a) !in managed }
        if (needFallback) for ((prefix, bomCoord) in defaultBoms) {
            if (direct.none { it.v == null && it.g.startsWith(prefix) && key(it.g, it.a) !in managed }) continue
            val c = coord(bomCoord) ?: continue
            val ver = if (fresh) try { latest(c.g, c.a) } catch (_: Exception) { c.v!! } else c.v!!
            val pom = parsePom(c.g, c.a, ver) ?: parsePom(c.g, c.a, c.v!!) ?: continue
            log("  версии без BOM — использую $bomCoord")
            pom.managed.forEach { (k, vv) -> managed.putIfAbsent(k, vv) }
        }
        warmUp(direct + provided, managed)
        val versions = LinkedHashMap<String, String>()
        val rootProvided = provided.map { key(it.g, it.a) }.toSet()
        val order = LinkedHashMap<String, Pair<String, Boolean>>() // ga → (версия, aar?)

        fun want(g: String, a: String, v: String?): String =
            v?.let { concrete(g, a, it) } ?: managed[key(g, a)]?.let(::pick) ?: versions[key(g, a)] ?: latest(g, a)

        var pass = 0
        while (true) {
            if (++pass > 10) throw BuildError("не удалось согласовать версии зависимостей")
            var changed = false
            order.clear()
            val seen = HashSet<String>()
            fun visit(g: String, a: String, v0: String?, excl: Set<String>, depth: Int) {
                if (cancelled()) throw Cancelled()
                val k = key(g, a)
                if (excl.any { it == k || it == "$g:*" || it == "*:$a" || it == "*:*" }) return
                val w = want(g, a, v0)
                val cur = versions[k]
                val chosen = if (cur == null || compare(w, cur) > 0) w else cur
                if (chosen != cur) { versions[k] = chosen; changed = true }
                val ver = versions[k]!!
                if (!seen.add("$k:$ver")) return
                val pom = parsePom(g, a, ver) ?: throw BuildError("не найден $g:$a:$ver", "Проверь координаты и версию в build.gradle")
                order[k] = ver to (pom.packaging == "aar")
                if (depth > 40) return
                for (d in pom.deps) {
                    if (d.scope == "test" || d.scope == "provided" || d.scope == "system" || d.optional) continue
                    if (d.type != "jar" && d.type != "aar" && d.type != "pom") continue
                    visit(d.g, d.a, d.v ?: pom.managed[key(d.g, d.a)], excl + d.excl, depth + 1)
                }
            }
            for (d in direct) visit(d.g, d.a, d.v, emptySet(), 0)
            for (d in provided) visit(d.g, d.a, d.v, emptySet(), 0)
            if (!changed) break
        }

        // скачивание и распаковка
        val libs = ArrayList<Lib>()
        val items = order.entries.toList()
        val pool = Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "maven").apply { isDaemon = true } }
        try {
            val futures = ArrayList<Future<Lib?>>()
            var done = 0
            for ((k, p) in items) {
                val (ver, _) = p
                val g = k.substringBefore(':'); val a = k.substringAfter(':')
                futures.add(pool.submit<Lib?> { fetchLib(g, a, ver) })
            }
            for ((i, f) in futures.withIndex()) {
                val lib = try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                done++
                progress(done.toFloat() / futures.size, "${items[i].key.substringAfter(':')} ${items[i].value.first}")
                if (lib != null) { lib.provided = key(lib.group, lib.artifact) in rootProvided && direct.none { key(it.g, it.a) == key(lib.group, lib.artifact) }; libs.add(lib) }
            }
        } finally { pool.shutdownNow() }
        log("  библиотек: ${libs.size} (aar ${libs.count { it.aar }}, jar ${libs.count { !it.aar }})")
        return libs
    }

    /**
     * Заранее, параллельно, скачивает и разбирает POM всего дерева зависимостей (по уровням в ширину). Дальше разрешение версий
     * идёт по готовому кэшу, а не качает сотню мелких файлов по одному. Версии берутся так же, как при разрешении;
     * если потом версия поменяется из-за конфликта, нужный POM докачается как обычно. Ошибки здесь не важны — их повторит основной проход.
     */
    private fun warmUp(roots: List<Coord>, managed: Map<String, String>) {
        class Node(val g: String, val a: String, val v: String)
        val seen = ConcurrentHashMap.newKeySet<String>()
        var level = roots.mapNotNull { c ->
            val v = c.v?.takeUnless { isDynamic(it) }?.let(::pick) ?: managed[key(c.g, c.a)]?.let(::pick) ?: return@mapNotNull null
            Node(c.g, c.a, v)
        }.filter { seen.add("${it.g}:${it.a}:${it.v}") }
        if (level.isEmpty()) return
        val pool = Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "maven-pom").apply { isDaemon = true } }
        try {
            var depth = 0
            while (level.isNotEmpty() && depth++ < 16 && seen.size < 4000) {
                if (cancelled()) throw Cancelled()
                progress(0f, "описания библиотек · ${seen.size}")
                val next = pool.invokeAll(level.map { n ->
                    Callable<List<Node>> {
                        try {
                            val pom = if (cancelled()) null else parsePom(n.g, n.a, n.v)
                            pom?.deps.orEmpty().filter { d ->
                                d.scope != "test" && d.scope != "provided" && d.scope != "system" && !d.optional && (d.type == "jar" || d.type == "aar" || d.type == "pom")
                            }.mapNotNull { d ->
                                val v = (d.v ?: pom!!.managed[key(d.g, d.a)] ?: managed[key(d.g, d.a)])?.takeUnless { isDynamic(it) }?.let(::pick) ?: return@mapNotNull null
                                Node(d.g, d.a, v)
                            }
                        } catch (_: Exception) { emptyList() }
                    }
                }).flatMap { f -> try { f.get() } catch (_: Exception) { emptyList() } }
                level = next.filter { seen.add("${it.g}:${it.a}:${it.v}") }
            }
        } finally { pool.shutdownNow() }
        if (cancelled()) throw Cancelled()
    }

    private fun fetchLib(g: String, a: String, v: String): Lib? {
        val dir = dirOf(g, a, v)
        val pom = parsePom(g, a, v) ?: return null
        val rel = "${g.replace('.', '/')}/$a/$v/$a-$v"
        return when (pom.packaging) {
            "aar" -> {
                val aarFile = File(dir, "$a-$v.aar")
                if (!download("$rel.aar", aarFile)) throw BuildError("нет файла $a-$v.aar", "Проверь версию $g:$a:$v")
                val out = File(dir, "x")
                val stamp = File(dir, "x.done")
                if (!stamp.exists()) {
                    out.deleteRecursively()
                    Unzip.extract(aarFile, out, false, { cancelled() }) { _, _ -> }
                    stamp.createNewFile()
                }
                Lib(g, a, v, true, out, aarFile)
            }
            "pom" -> {
                // корень KMP-библиотеки без собственного артефакта: берём Android-вариант
                null
            }
            else -> {
                val jar = File(dir, "$a-$v.jar")
                if (!download("$rel.jar", jar)) {
                    if (pom.deps.isNotEmpty()) null else throw BuildError("нет файла $a-$v.jar", "Проверь версию $g:$a:$v")
                } else Lib(g, a, v, false, dir, jar)
            }
        }
    }
}

/** Заголовок jar: имена классов (для поиска дубликатов в разных библиотеках). */
object JarIndex {
    fun classes(jar: File): List<String> = try {
        java.util.zip.ZipFile(jar).use { z -> z.entries().asSequence().filter { it.name.endsWith(".class") }.map { it.name }.toList() }
    } catch (_: Exception) { emptyList() }
}
