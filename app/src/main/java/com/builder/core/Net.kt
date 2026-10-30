package com.builder.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class HttpError(val code: Int, url: String) : IOException("HTTP $code · ${URL(url).host}")

/**
 * Загрузчик: докачка по Range, повторы с нарастающей паузой, проверка длины и SHA-256,
 * атомарная запись через .part → переименование.
 */
class Downloader(private val cancelled: () -> Boolean) {

    fun fetch(
        url: String, dest: File, sha256: String? = null, attempts: Int = 4,
        onRetry: (Int, String) -> Unit = { _, _ -> },
        onProgress: (done: Long, total: Long, bytesPerSec: Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var last: Exception? = null
        for (attempt in 1..attempts) {
            if (cancelled()) throw Cancelled()
            try {
                attemptOnce(url, part, onProgress)
                if (sha256 != null) {
                    val got = sha256Of(part)
                    if (!got.equals(sha256, true)) { part.delete(); throw IOException("SHA-256 не совпал: $got") }
                }
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("не удалось сохранить ${dest.name}")
                return
            } catch (e: Cancelled) {
                throw e
            } catch (e: HttpError) {
                // 4xx — повтор бессмыслен (кроме «слишком часто» и «таймаут»)
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < attempts) {
                onRetry(attempt, last?.message ?: "ошибка сети")
                var wait = 1200L * attempt
                while (wait > 0 && !cancelled()) { Thread.sleep(100); wait -= 100 }
            }
        }
        throw last ?: IOException("не удалось скачать")
    }

    private fun attemptOnce(url: String, part: File, onProgress: (Long, Long, Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "ApkBuilder/2.0")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 416) { part.delete(); throw IOException("докачка отклонена сервером — начинаю заново") }
            if (code != 200 && code != 206) throw HttpError(code, url)
            if (code == 200) have = 0L // сервер игнорирует Range — качаем с нуля
            val len = c.contentLengthLong
            val total = if (len > 0) have + len else -1L

            val started = System.nanoTime(); var got = 0L
            var winStart = started; var winBytes = 0L; var speed = 0L
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(have); raf.seek(have)
                c.inputStream.use { inp ->
                    val buf = ByteArray(65536)
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = inp.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); got += n; winBytes += n
                        val now = System.nanoTime()
                        if (now - winStart >= 500_000_000L) {
                            speed = winBytes * 1_000_000_000L / (now - winStart); winStart = now; winBytes = 0
                        }
                        onProgress(have + got, total, speed)
                    }
                }
            }
            if (total > 0 && part.length() != total) throw IOException("загрузка оборвалась на ${part.length()} из $total байт")
            onProgress(part.length(), part.length(), speed)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun human(n: Long): String = when {
            n >= 1L shl 20 -> "%.1f МБ".format(n / 1048576.0)
            n >= 1L shl 10 -> "%d КБ".format(n shr 10)
            else -> "$n Б"
        }
    }
}

/** Запуск shell-команды с потоком вывода, остановкой (вместе с дочерними процессами) и вводом в stdin. */
class Shell {
    @Volatile private var proc: Process? = null
    @Volatile private var pid = 0
    @Volatile private var killed = false
    val running get() = proc != null

    fun run(cmd: String, cwd: File, env: Map<String, String>, onLine: (String) -> Unit): Int {
        // первой строкой оболочка сообщает свой PID — он нужен, чтобы при отмене убить всё дерево процессов
        val full = "echo \"$PID_TAG\$\$\"\n$cmd"
        val pb = ProcessBuilder("/system/bin/sh", "-c", full).directory(cwd).redirectErrorStream(true)
        if (!File("/system/bin/sh").exists()) pb.command("sh", "-c", full) // обычная JVM (проверка на ПК)
        pb.environment().putAll(env)
        killed = false; pid = 0
        val p = pb.start()
        proc = p
        try {
            val r = p.inputStream.bufferedReader()
            val sb = StringBuilder()
            fun emit() {
                if (sb.isEmpty()) return
                val line = sb.toString(); sb.setLength(0)
                if (pid == 0 && line.startsWith(PID_TAG)) pid = line.removePrefix(PID_TAG).trim().toIntOrNull() ?: -1 else onLine(line)
            }
            try {
                while (true) {
                    val ch = r.read(); if (ch < 0) break
                    if (ch == '\n'.code || ch == '\r'.code) emit() else sb.append(ch.toChar())
                }
                emit()
            } catch (e: IOException) {
                if (!killed) throw e // канал закрыт нашей же остановкой — не ошибка
            }
            return p.waitFor()
        } finally {
            proc = null
        }
    }

    fun write(line: String) {
        try { proc?.outputStream?.let { it.write((line + "\n").toByteArray()); it.flush() } } catch (_: IOException) {}
    }

    fun kill() {
        val p = proc ?: return
        killed = true
        val root = pid
        if (root > 0) {
            val victims = descendants(root)
            for (v in victims.asReversed() + root) {
                try { Runtime.getRuntime().exec(arrayOf("kill", "-9", v.toString())).waitFor() } catch (_: Exception) {}
            }
        }
        p.destroy()
        try { p.inputStream.close() } catch (_: IOException) {} // освобождаем читающий поток
    }

    /** Потомки процесса по /proc: родитель берётся из поля ppid файла stat. */
    private fun descendants(root: Int): List<Int> {
        val parent = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { d ->
            val id = d.name.toIntOrNull() ?: return@forEach
            try {
                val st = File(d, "stat").readText()
                parent[id] = st.substring(st.lastIndexOf(')') + 2).split(' ')[1].toInt()
            } catch (_: Exception) {}
        }
        val out = ArrayList<Int>(); val queue = ArrayDeque<Int>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((id, pp) in parent) if (pp == cur && id !in out && id != root) { out.add(id); queue.add(id) }
        }
        return out
    }

    private companion object { const val PID_TAG = "@@apkbuilder-pid:" }
}

/** Лог, общий для сборки и терминала. */
class Console {
    private val buf = ArrayList<String>()
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var onClear: (() -> Unit)? = null
    /** Журнал последней сборки дублируется в файл: его можно прочитать и отправить, даже если приложение закрыто. */
    @Volatile var file: File? = null

    fun add(s: String) {
        synchronized(this) {
            buf.add(s); if (buf.size > 4000) buf.subList(0, 1000).clear()
            try { file?.appendText(Ansi.parse(s, 0).text + "\n") } catch (_: Exception) {}
        }
        listener?.invoke(s)
    }

    @Synchronized fun snapshot(): List<String> = ArrayList(buf)
    fun clear() { synchronized(this) { buf.clear() }; onClear?.invoke() }
}

/** Поиск инструментов внутри тулчейна (структура архивов у разных авторов разная). */
object Tools {
    val WANTED = listOf("aapt2", "aapt", "zipalign", "d8", "dalvikvm", "java", "javac", "ecj", "apksigner", "dexdump")

    /** Компиляторы на Java живут в dex-jar и запускаются через app_process: имя файла → имя инструмента. */
    val JARS = mapOf("kotlinc.jar" to "kotlinc", "kpatch.jar" to "kpatch", "composec.jar" to "composec", "d8.jar" to "d8dex", "ecj.jar" to "ecjdex")

    fun locate(root: File): Map<String, File> {
        val found = LinkedHashMap<String, File>()
        val depthOf = HashMap<String, Int>()
        fun walk(dir: File, depth: Int) {
            if (depth > 6) return
            val kids = dir.listFiles() ?: return
            for (k in kids) {
                if (k.isDirectory) walk(k, depth + 1)
                else {
                    val key = if (k.name in WANTED) k.name else JARS[k.name]
                    if (key != null && (depthOf[key] ?: Int.MAX_VALUE) > depth) { found[key] = k; depthOf[key] = depth }
                }
            }
        }
        walk(root, 0)
        for ((n, f) in found) if (n !in JARS.values && !f.canExecute()) f.setExecutable(true, false)
        // запасной вариант — системный PATH (на обычной JVM)
        for (n in WANTED) if (n !in found) {
            System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, n) }?.firstOrNull { it.isFile && it.canExecute() }?.let { found[n] = it }
        }
        return found
    }

    fun androidJar(root: File): File? {
        File(root, "platforms/android.jar").takeIf { it.isFile }?.let { return it }
        var res: File? = null
        fun walk(d: File, depth: Int) {
            if (res != null || depth > 5) return
            d.listFiles()?.forEach { if (it.isDirectory) walk(it, depth + 1) else if (it.name == "android.jar" && res == null) res = it }
        }
        walk(root, 0)
        return res
    }
}
