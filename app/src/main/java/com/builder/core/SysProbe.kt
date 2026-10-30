package com.builder.core

import java.io.File

/**
 * Замеры нагрузки без Android API: оперативная память системы (/proc/meminfo) и процессор / память
 * дерева процессов приложения (само приложение + всё, что оно запустило: aapt2, sh и т. п.) по /proc/<pid>.
 * Чужие процессы на новых Android недоступны — их мы и не считаем.
 */
class SysProbe(private val procDir: File = File("/proc"), private val cores: Int = Runtime.getRuntime().availableProcessors(), private val pageKb: Int = 4) {
    class Snap(val cpu: Float, val ram: Float, val ramUsedKb: Long, val ramTotalKb: Long, val procRamKb: Long, val procFrac: Float, val procs: Int)

    private var lastTicks = -1L
    private var lastAt = 0L
    private val self: Int = try { File(procDir, "self/stat").readText().substringBefore(' ').trim().toInt() } catch (_: Exception) { -1 }

    private fun meminfo(): Pair<Long, Long> {
        var total = 0L; var avail = -1L; var free = 0L; var cached = 0L
        try {
            File(procDir, "meminfo").forEachLine { l ->
                val k = l.substringBefore(':'); val v = l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: return@forEachLine
                when (k) { "MemTotal" -> total = v; "MemAvailable" -> avail = v; "MemFree" -> free = v; "Cached" -> cached = v }
            }
        } catch (_: Exception) {}
        if (avail < 0) avail = free + cached
        return total to avail
    }

    /** pid → (родитель, тики процессора, страницы в памяти) по всем доступным /proc/<pid>. */
    private fun scan(): Map<Int, Triple<Int, Long, Long>> {
        val out = HashMap<Int, Triple<Int, Long, Long>>()
        val dirs = procDir.listFiles() ?: return out
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            try {
                val st = File(d, "stat").readText()
                val f = st.substring(st.lastIndexOf(')') + 2).split(' ') // с 3-го поля: state ppid ...
                val ppid = f[1].toInt(); val ticks = f[11].toLong() + f[12].toLong()
                val rss = try { File(d, "statm").readText().split(' ')[1].toLong() } catch (_: Exception) { 0L }
                out[pid] = Triple(ppid, ticks, rss)
            } catch (_: Exception) {}
        }
        return out
    }

    fun sample(now: Long = System.currentTimeMillis()): Snap {
        val (total, avail) = meminfo()
        val all = scan()
        val tree = HashSet<Int>()
        if (self > 0) tree.add(self)
        var grew = true
        while (grew) { grew = false; for ((pid, v) in all) if (pid !in tree && v.first in tree) { tree.add(pid); grew = true } }
        var ticks = 0L; var pages = 0L
        for (p in tree) all[p]?.let { ticks += it.second; pages += it.third }
        var cpu = 0f
        if (lastTicks >= 0 && now > lastAt) {
            val dt = (now - lastAt) / 1000.0
            cpu = (((ticks - lastTicks).coerceAtLeast(0) / 100.0) / (dt * cores) * 100.0).toFloat().coerceIn(0f, 100f)
        }
        lastTicks = ticks; lastAt = now
        val used = (total - avail).coerceAtLeast(0)
        val procKb = pages * pageKb
        return Snap(cpu, if (total > 0) used.toFloat() / total else 0f, used, total, procKb, if (total > 0) (procKb.toFloat() / total).coerceIn(0f, 1f) else 0f, tree.size)
    }
}
