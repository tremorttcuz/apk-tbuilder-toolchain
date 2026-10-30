package com.builder.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Распаковка с защитой от zip-slip, правами на запуск и прогрессом. */
object Unzip {
    fun extract(zip: File, into: File, execAll: Boolean, cancelled: () -> Boolean, onProgress: (Float, String) -> Unit) {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        ZipFile(zip).use { z ->
            val all = z.entries().toList()
            val total = all.sumOf { maxOf(it.size, 0L) }.coerceAtLeast(1L)
            var done = 0L
            for (e in all) {
                if (cancelled()) throw Cancelled()
                val f = File(into, e.name)
                if (!f.canonicalPath.startsWith(base)) continue // zip-slip
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                if (f.exists()) f.delete() // старые jar в тулчейне «только для чтения»
                z.getInputStream(e).use { inp -> f.outputStream().use { inp.copyTo(it) } }
                if (execAll) f.setExecutable(true, false)
                done += maxOf(e.size, 0L)
                onProgress(done.toFloat() / total, e.name)
            }
        }
    }

    /** GitHub-архивы лежат в одной корневой папке (repo-main/): поднимаем содержимое на уровень выше. */
    fun stripSingleRoot(dir: File) {
        val kids = dir.listFiles() ?: return
        if (kids.size != 1 || !kids[0].isDirectory) return
        val root = kids[0]
        val tmp = File(dir, ".strip-" + System.nanoTime())
        if (!root.renameTo(tmp)) return
        tmp.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
        tmp.deleteRecursively()
    }
}

class Cancelled : RuntimeException("отменено")

/**
 * Собственный ZIP-писатель. Умеет то, что нужно APK:
 *  - записи STORED выравниваются по 4 байта (как zipalign), поэтому выравнивание
 *    переживает подпись и не нужен отдельный шаг zipalign;
 *  - детерминированное время — одинаковый вход даёт одинаковый APK.
 */
class ZipOut(file: File) : Closeable {
    private class Rec(val name: ByteArray, val method: Int, val crc: Long, val csize: Int, val size: Int, val offset: Long)

    private val out = Counting(BufferedOutputStream(FileOutputStream(file)))
    private val recs = ArrayList<Rec>()
    private val seen = HashSet<String>()
    val count get() = recs.size

    private class Counting(val o: OutputStream) : OutputStream() {
        var n = 0L
        override fun write(b: Int) { o.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); n += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    fun has(name: String) = name in seen

    fun add(name: String, data: ByteArray, stored: Boolean, align: Int = 4) {
        require(seen.add(name)) { "повторяющаяся запись в архиве: $name" }
        val nb = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().also { it.update(data) }.value
        val body = if (stored) data else deflate(data)
        val method = if (stored) 0 else 8
        val offset = out.n

        var extra = ByteArray(0)
        if (stored) {
            // как zipalign: данные записи начинаются с адреса, кратного align (4 байта; 4096 для .so)
            var need = ((align - ((offset + 30 + nb.size) % align)) % align).toInt()
            if (need != 0) {
                while (need < 6) need += align
                extra = ByteArray(need)
                extra[0] = 0x35; extra[1] = 0xD9.toByte() // id 0xD935 — как у zipalign
                extra[2] = (need - 4).toByte(); extra[3] = ((need - 4) shr 8).toByte()
                extra[4] = align.toByte(); extra[5] = (align shr 8).toByte()
            }
        }

        le32(0x04034b50); le16(20); le16(0x0800); le16(method); le16(DOS_TIME); le16(DOS_DATE)
        le32(crc); le32(body.size.toLong()); le32(data.size.toLong()); le16(nb.size); le16(extra.size)
        out.write(nb); out.write(extra); out.write(body)
        recs.add(Rec(nb, method, crc, body.size, data.size, offset))
    }

    /** Запись без сжатия потоком: CRC и размер известны заранее, поэтому файл целиком в память не читается. */
    fun addStored(name: String, size: Long, crc: Long, align: Int = 4, input: () -> java.io.InputStream) {
        require(seen.add(name)) { "повторяющаяся запись в архиве: $name" }
        val nb = name.toByteArray(Charsets.UTF_8)
        val offset = out.n
        var extra = ByteArray(0)
        var need = ((align - ((offset + 30 + nb.size) % align)) % align).toInt()
        if (need != 0) {
            while (need < 6) need += align
            extra = ByteArray(need)
            extra[0] = 0x35; extra[1] = 0xD9.toByte()
            extra[2] = (need - 4).toByte(); extra[3] = ((need - 4) shr 8).toByte()
            extra[4] = align.toByte(); extra[5] = (align shr 8).toByte()
        }
        le32(0x04034b50); le16(20); le16(0x0800); le16(0); le16(DOS_TIME); le16(DOS_DATE)
        le32(crc); le32(size); le32(size); le16(nb.size); le16(extra.size)
        out.write(nb); out.write(extra)
        var copied = 0L
        input().use { i -> val buf = ByteArray(65536); while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n); copied += n } }
        check(copied == size) { "размер $name изменился при записи" }
        recs.add(Rec(nb, 0, crc, size.toInt(), size.toInt(), offset))
    }

    /** Копирует записи из готового архива, сохраняя способ сжатия. */
    fun copyFrom(zip: File, skip: (String) -> Boolean = { false }, forceStored: (String) -> Boolean = { false }) {
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || skip(e.name)) continue
                val data = z.getInputStream(e).use { it.readBytes() }
                add(e.name, data, e.method == ZipEntry.STORED || forceStored(e.name))
            }
        }
    }

    override fun close() {
        val cdStart = out.n
        for (r in recs) {
            le32(0x02014b50); le16(20); le16(20); le16(0x0800); le16(r.method); le16(DOS_TIME); le16(DOS_DATE)
            le32(r.crc); le32(r.csize.toLong()); le32(r.size.toLong())
            le16(r.name.size); le16(0); le16(0); le16(0); le16(0); le32(0); le32(r.offset)
            out.write(r.name)
        }
        val cdSize = out.n - cdStart
        le32(0x06054b50); le16(0); le16(0); le16(recs.size); le16(recs.size); le32(cdSize); le32(cdStart); le16(0)
        out.flush(); out.close()
    }

    private fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    private fun le32(v: Long) { for (s in 0..24 step 8) out.write(((v shr s) and 0xFF).toInt()) }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(9, true)
        d.setInput(data); d.finish()
        val bo = ByteArrayOutputStream(maxOf(32, data.size / 2))
        val buf = ByteArray(32768)
        while (!d.finished()) bo.write(buf, 0, d.deflate(buf))
        d.end()
        return bo.toByteArray()
    }

    companion object {
        fun crcOf(f: File): Long {
            val c = CRC32()
            f.inputStream().use { i -> val buf = ByteArray(65536); while (true) { val n = i.read(buf); if (n < 0) break; c.update(buf, 0, n) } }
            return c.value
        }

        private const val DOS_TIME = 0
        private const val DOS_DATE = (40 shl 9) or (1 shl 5) or 1 // 2020-01-01
    }
}
