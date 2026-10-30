package com.builder.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64 as JBase64
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipFile

/** Минимальный DER-кодировщик — хватает на сертификат и PKCS#7. */
object Der {
    fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()
        val o = ByteArrayOutputStream()
        o.write(tag)
        when {
            body.size < 128 -> o.write(body.size)
            body.size < 256 -> { o.write(0x81); o.write(body.size) }
            body.size < 65536 -> { o.write(0x82); o.write(body.size shr 8); o.write(body.size and 0xFF) }
            else -> { o.write(0x83); o.write(body.size shr 16); o.write((body.size shr 8) and 0xFF); o.write(body.size and 0xFF) }
        }
        o.write(body)
        return o.toByteArray()
    }

    fun seq(vararg p: ByteArray) = tlv(0x30, *p)
    fun set(vararg p: ByteArray) = tlv(0x31, *p)
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun int(v: Int) = int(BigInteger.valueOf(v.toLong()))
    fun nul() = byteArrayOf(0x05, 0x00)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun bits(b: ByteArray) = tlv(0x03, byteArrayOf(0), b)

    fun oid(dotted: String): ByteArray {
        val a = dotted.split('.').map { it.toLong() }
        val o = ByteArrayOutputStream()
        o.write((a[0] * 40 + a[1]).toInt())
        for (n in a.drop(2)) {
            val stack = ArrayList<Int>()
            var v = n
            stack.add((v and 0x7F).toInt()); v = v shr 7
            while (v > 0) { stack.add(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
            for (k in stack.indices.reversed()) o.write(stack[k])
        }
        return tlv(0x06, o.toByteArray())
    }

    fun time(cal: Calendar): ByteArray {
        val y = cal.get(Calendar.YEAR)
        val s = "%02d%02d%02d%02d%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)) + "Z"
        return if (y < 2050) tlv(0x17, ("%02d".format(y % 100) + s).toByteArray())
        else tlv(0x18, ("%04d".format(y) + s).toByteArray())
    }

    // --- чтение: ровно столько, сколько нужно для проверки подписи ---
    class Node(val tag: Int, val buf: ByteArray, val start: Int, val end: Int) {
        val content get() = buf.copyOfRange(start, end)
        fun children(): List<Node> {
            val out = ArrayList<Node>()
            var p = start
            while (p < end) { val n = read(buf, p); out.add(n.first); p = n.second }
            return out
        }
    }

    fun read(b: ByteArray, at: Int): Pair<Node, Int> {
        val tag = b[at].toInt() and 0xFF
        var p = at + 1
        var len = b[p++].toInt() and 0xFF
        if (len >= 0x80) {
            val n = len and 0x7F; len = 0
            repeat(n) { len = (len shl 8) or (b[p++].toInt() and 0xFF) }
        }
        require(p + len <= b.size) { "битый DER" }
        return Node(tag, b, p, p + len) to (p + len)
    }
}

class Identity(val key: PrivateKey, val certDer: ByteArray) {
    val cert: X509Certificate get() =
        CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate
    val fingerprint: String get() =
        MessageDigest.getInstance("SHA-256").digest(certDer).joinToString("") { "%02x".format(it) }
}

/**
 * Подпись APK схемами v1 (JAR) и v2 (весь файл) полностью на Java-классах: без apksigner и JVM-инструментов.
 * v1 нужна старым Android (до 7.0), v2 — обязательна для targetSdk ≥ 30.
 */
object ApkSigner {
    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
    private const val OID_RSA = "1.2.840.113549.1.1.1"
    private const val OID_SHA256 = "2.16.840.1.101.3.4.2.1"
    private const val OID_SIGNED_DATA = "1.2.840.113549.1.7.2"
    private const val OID_DATA = "1.2.840.113549.1.7.1"
    private const val MF = "META-INF/MANIFEST.MF"
    private const val SF = "META-INF/CERT.SF"
    private const val RSA = "META-INF/CERT.RSA"

    // ---------- ключ ----------

    fun loadOrCreate(dir: File): Identity {
        dir.mkdirs()
        val kf = File(dir, "debug.p8"); val cf = File(dir, "debug.crt")
        if (kf.exists() && cf.exists()) {
            try {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                return Identity(key, cf.readBytes())
            } catch (_: Exception) { /* битый файл — создадим заново */ }
        }
        val id = create()
        kf.writeBytes(id.key.encoded); cf.writeBytes(id.certDer)
        return id
    }

    /** Короткий отпечаток сертификата для показа в настройках; null, если ключа ещё нет. */
    fun fingerprint(dir: File): String? =
        File(dir, "debug.crt").takeIf { it.isFile }?.let { sha256(it.readBytes()).joinToString("") { b -> "%02x".format(b) } }

    /**
     * Подставляет чужой ключ: контейнер PKCS12 (.p12/.pfx/.keystore в формате PKCS12) или zip, который сделал «Экспорт ключа»
     * (debug.p8 + debug.crt). Нужен RSA-ключ. Возвращает отпечаток SHA-256 сертификата.
     */
    fun importKey(dir: File, src: File, password: String, alias: String?): String {
        dir.mkdirs()
        val head = src.inputStream().use { i -> ByteArray(4).also { i.read(it) } }
        val key: ByteArray; val cert: ByteArray
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            java.util.zip.ZipFile(src).use { z ->
                fun rd(n: String) = (z.getEntry(n) ?: error("в архиве нет $n")).let { e -> z.getInputStream(e).use { it.readBytes() } }
                key = rd("debug.p8"); cert = rd("debug.crt")
            }
        } else {
            val ks = try { java.security.KeyStore.getInstance("PKCS12").also { k -> src.inputStream().use { k.load(it, password.toCharArray()) } } }
                catch (e: Exception) { error("не удалось открыть хранилище (нужен PKCS12 и верный пароль): ${e.message}") }
            val al = alias?.takeIf { it.isNotBlank() && ks.containsAlias(it) } ?: ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: error("в хранилище нет ключа")
            val pk = ks.getKey(al, password.toCharArray()) as? java.security.PrivateKey ?: error("не удалось достать ключ «$al» (неверный пароль?)")
            if (pk.algorithm != "RSA") error("ключ ${pk.algorithm}: поддерживается только RSA")
            key = pk.encoded; cert = ks.getCertificate(al).encoded
        }
        // проверяем, что ключ и сертификат читаются, и только потом заменяем
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(key))
        CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream())
        File(dir, "debug.p8").writeBytes(key); File(dir, "debug.crt").writeBytes(cert)
        return sha256(cert).joinToString("") { "%02x".format(it) }
    }

    /** Кладёт ключ в zip (для переноса и резервной копии). Хранить как пароль: с ним можно подписывать от вашего имени. */
    fun exportKey(dir: File, out: File) {
        loadOrCreate(dir)
        ZipOut(out).use { z -> z.add("debug.p8", File(dir, "debug.p8").readBytes(), false); z.add("debug.crt", File(dir, "debug.crt").readBytes(), false) }
    }

    fun resetKey(dir: File) { File(dir, "debug.p8").delete(); File(dir, "debug.crt").delete() }

    fun create(): Identity {
        val kpg = KeyPairGenerator.getInstance("RSA"); kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val name = Der.seq(
            Der.set(Der.seq(Der.oid("2.5.4.10"), Der.utf8("APK TBuilder"))),
            Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8("APK TBuilder Debug"))),
        )
        val alg = Der.seq(Der.oid(OID_SHA256_RSA), Der.nul())
        val from = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val to = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { add(Calendar.YEAR, 30) }
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = Der.seq(
            Der.tlv(0xA0, Der.int(2)), Der.int(serial), alg, name,
            Der.seq(Der.time(from), Der.time(to)), name, kp.public.encoded,
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(tbs); sign() }
        return Identity(kp.private, Der.seq(tbs, alg, Der.bits(sig)))
    }

    // ---------- подпись ----------

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun b64(b: ByteArray) = JBase64.getEncoder().encodeToString(b)

    /** Строки манифеста не длиннее 72 байт; хвост переносится с ведущим пробелом. */
    private fun attr(line: String): String {
        val sb = StringBuilder()
        var bytes = 0; var limit = 72
        for (ch in line) {
            val n = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + n > limit) { sb.append("\r\n "); bytes = 1; limit = 72 }
            sb.append(ch); bytes += n
        }
        return sb.append("\r\n").toString()
    }

    private fun isSignatureFile(n: String): Boolean {
        val u = n.uppercase()
        return u == MF || (u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC")))
    }

    fun sign(unsigned: File, signed: File, id: Identity, onFile: (String) -> Unit = {}): Int {
        // проход 1 — дайджесты всех файлов
        val sections = LinkedHashMap<String, ByteArray>()
        ZipFile(unsigned).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || isSignatureFile(e.name)) continue
                val md = MessageDigest.getInstance("SHA-256")
                z.getInputStream(e).use { inp ->
                    val buf = ByteArray(65536)
                    while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                }
                sections[e.name] = (attr("Name: ${e.name}") + attr("SHA-256-Digest: ${b64(md.digest())}") + "\r\n").toByteArray(Charsets.UTF_8)
            }
        }
        val mf = ByteArrayOutputStream()
        mf.write("Manifest-Version: 1.0\r\nCreated-By: APK TBuilder\r\n\r\n".toByteArray())
        sections.values.forEach { mf.write(it) }
        val mfBytes = mf.toByteArray()

        val sf = ByteArrayOutputStream()
        sf.write(("Signature-Version: 1.0\r\nCreated-By: APK TBuilder\r\n" + attr("SHA-256-Digest-Manifest: ${b64(sha256(mfBytes))}") + "\r\n").toByteArray())
        for ((name, sec) in sections) {
            sf.write((attr("Name: $name") + attr("SHA-256-Digest: ${b64(sha256(sec))}") + "\r\n").toByteArray(Charsets.UTF_8))
        }
        val sfBytes = sf.toByteArray()

        val sigBytes = Signature.getInstance("SHA256withRSA").run { initSign(id.key); update(sfBytes); sign() }
        val rsa = pkcs7(id, sigBytes)

        // проход 2 — собираем архив: сначала META-INF, затем остальное
        ZipOut(signed).use { zo ->
            zo.add(MF, mfBytes, false); zo.add(SF, sfBytes, false); zo.add(RSA, rsa, false)
            ZipFile(unsigned).use { z ->
                for (e in z.entries()) {
                    if (e.isDirectory || isSignatureFile(e.name)) continue
                    if (e.method == 0 && e.size > 1_000_000) {
                        // крупные записи без сжатия (.so, dex) копируем потоком, а не читаем в память
                        zo.addStored(e.name, e.size, e.crc, if (e.name.endsWith(".so")) 4096 else 4) { z.getInputStream(e) }
                    } else {
                        val data = z.getInputStream(e).use { it.readBytes() }
                        zo.add(e.name, data, e.method == 0, if (e.name.endsWith(".so")) 4096 else 4)
                    }
                    onFile(e.name)
                }
            }
            return zo.count
        }
    }

    private fun pkcs7(id: Identity, signature: ByteArray): ByteArray {
        // Certificate ::= SEQ { tbs, alg, sig };  tbs ::= SEQ { [0] ver, serial, alg, issuer, validity, subject, spki }
        val cert = Der.read(id.certDer, 0).first
        val tbs = cert.children()[0]
        val tc = tbs.children()
        val serial = tc[1]; val issuer = tc[3]
        val serialDer = Der.tlv(0x02, serial.content)
        val issuerDer = Der.tlv(0x30, issuer.content)
        val sha256Alg = Der.seq(Der.oid(OID_SHA256), Der.nul())
        val signer = Der.seq(
            Der.int(1), Der.seq(issuerDer, serialDer), sha256Alg,
            Der.seq(Der.oid(OID_RSA), Der.nul()), Der.octets(signature),
        )
        val signedData = Der.seq(
            Der.int(1), Der.set(sha256Alg), Der.seq(Der.oid(OID_DATA)),
            Der.tlv(0xA0, id.certDer), Der.set(signer),
        )
        return Der.seq(Der.oid(OID_SIGNED_DATA), Der.tlv(0xA0, signedData))
    }


    // ---------- схема v2 (подпись всего файла) ----------
    // Блок подписи лежит между записями и центральным каталогом. Без неё Android 11+ не ставит приложения с targetSdk ≥ 30.

    private const val V2_ID = 0x7109871a
    private const val V2_ALGO_RSA_SHA256 = 0x0103
    private val V2_MAGIC = "APK Sig Block 42".toByteArray()

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le64(v: Long) = ByteArray(8) { (v shr (8 * it)).toByte() }
    private fun lenp(b: ByteArray) = le32(b.size) + b
    private fun u32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    private fun u64(b: ByteArray, at: Int): Long { var r = 0L; for (k in 7 downTo 0) r = (r shl 8) or (b[at + k].toLong() and 0xFF); return r }

    /** Хвост архива (центральный каталог + EOCD) целиком в памяти, а тело файла читается потоком — APK может весить сотни МБ. */
    private class Tail(val cdOffset: Long, val cd: ByteArray, val eocd: ByteArray)

    private fun readTail(raf: java.io.RandomAccessFile): Tail {
        val len = raf.length()
        val scan = minOf(len, 65557L).toInt()
        val t = ByteArray(scan)
        raf.seek(len - scan); raf.readFully(t)
        var e = scan - 22
        while (e >= 0 && u32(t, e) != 0x06054b50) e--
        require(e >= 0) { "нет конца архива (EOCD)" }
        val cdOffset = u32(t, e + 16).toLong() and 0xFFFFFFFFL
        val cdSize = (u32(t, e + 12).toLong() and 0xFFFFFFFFL).toInt()
        val cd = ByteArray(cdSize)
        raf.seek(cdOffset); raf.readFully(cd)
        return Tail(cdOffset, cd, t.copyOfRange(e, scan))
    }

    /** Корневой дайджест по трём частям файла: записи [0, entriesEnd), каталог, EOCD (с указателем на начало блока подписи). */
    private fun rootDigest(raf: java.io.RandomAccessFile, entriesEnd: Long, cd: ByteArray, eocd: ByteArray): ByteArray {
        val chunks = java.io.ByteArrayOutputStream()
        var count = 0
        fun chunk(buf: ByteArray, n: Int) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(0xa5.toByte()); md.update(le32(n)); md.update(buf, 0, n)
            chunks.write(md.digest()); count++
        }
        val buf = ByteArray(1 shl 20)
        raf.seek(0); var left = entriesEnd
        while (left > 0) {
            val n = minOf(buf.size.toLong(), left).toInt()
            raf.readFully(buf, 0, n); chunk(buf, n); left -= n
        }
        for (p in listOf(cd, eocd)) {
            var o = 0
            while (o < p.size) { val n = minOf(buf.size, p.size - o); System.arraycopy(p, o, buf, 0, n); chunk(buf, n); o += n }
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(0x5a.toByte()); md.update(le32(count)); md.update(chunks.toByteArray())
        return md.digest()
    }

    /** Добавляет к уже подписанному по v1 архиву блок подписи v2. Файл читается и пишется потоком. */
    fun addV2(src: File, dst: File, id: Identity) {
        java.io.RandomAccessFile(src, "r").use { raf ->
            val t = readTail(raf)
            val digest = rootDigest(raf, t.cdOffset, t.cd, t.eocd)

            val signedData = lenp(lenp(le32(V2_ALGO_RSA_SHA256) + lenp(digest))) + lenp(lenp(id.certDer)) + lenp(ByteArray(0))
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(id.key); update(signedData); sign() }
            val spki = id.cert.publicKey.encoded
            val signer = lenp(signedData) + lenp(lenp(le32(V2_ALGO_RSA_SHA256) + lenp(sig))) + lenp(spki)
            val value = lenp(lenp(signer))
            val pair = le64(4L + value.size) + le32(V2_ID) + value
            val block = le64(pair.size + 8L + 16L) + pair + le64(pair.size + 8L + 16L) + V2_MAGIC

            dst.outputStream().buffered(1 shl 16).use { out ->
                raf.seek(0)
                val buf = ByteArray(1 shl 16); var left = t.cdOffset
                while (left > 0) { val n = minOf(buf.size.toLong(), left).toInt(); raf.readFully(buf, 0, n); out.write(buf, 0, n); left -= n }
                out.write(block); out.write(t.cd)
                val e = t.eocd.copyOf(); val newCd = t.cdOffset + block.size
                for (k in 0..3) e[16 + k] = (newCd shr (8 * k)).toByte()
                out.write(e)
            }
        }
    }

    class V2Result(val fingerprint: String)

    /** Проверяет блок v2 по спецификации: пересчитывает дайджест и проверяет подпись ключом из сертификата. */
    fun verifyV2(apk: File): V2Result = java.io.RandomAccessFile(apk, "r").use { raf ->
        val t = readTail(raf)
        val cdOffset = t.cdOffset.toInt().let { t.cdOffset }
        require(cdOffset >= 24 + 16) { "блока подписи v2 нет" }
        val magic = ByteArray(16); raf.seek(cdOffset - 16); raf.readFully(magic)
        require(String(magic) == "APK Sig Block 42") { "блока подписи v2 нет" }
        val sizeBytes = ByteArray(8); raf.seek(cdOffset - 24); raf.readFully(sizeBytes)
        val size = u64(sizeBytes, 0)
        val start = cdOffset - size - 8
        require(start >= 0) { "размеры блока подписи не совпадают" }
        val b = ByteArray((cdOffset - start).toInt()); raf.seek(start); raf.readFully(b)
        require(u64(b, 0) == size) { "размеры блока подписи не совпадают" }
        // пары id-значение
        var p = 8; val end = b.size - 24
        var value: ByteArray? = null
        while (p < end) {
            val len = u64(b, p).toInt(); val id = u32(b, p + 8)
            if (id == V2_ID) value = b.copyOfRange(p + 12, p + 8 + len)
            p += 8 + len
        }
        val v = value ?: error("в блоке нет подписи v2")
        // signers → signer → (signedData, signatures, publicKey)
        var q = 4 // пропустили длину последовательности signers
        val signerLen = u32(v, q); q += 4
        val signer = v.copyOfRange(q, q + signerLen)
        var o = 0
        val sdLen = u32(signer, o); o += 4; val signedData = signer.copyOfRange(o, o + sdLen); o += sdLen
        val sgLen = u32(signer, o); o += 4; val sigs = signer.copyOfRange(o, o + sgLen); o += sgLen
        val pkLen = u32(signer, o); o += 4; val pk = signer.copyOfRange(o, o + pkLen)
        // подпись
        val firstLen = u32(sigs, 0); val algo = u32(sigs, 4); val sigLen = u32(sigs, 8)
        require(algo == V2_ALGO_RSA_SHA256 && firstLen >= 8) { "неизвестный алгоритм подписи" }
        val sig = sigs.copyOfRange(12, 12 + sigLen)
        val pub = KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(pk))
        val ok = Signature.getInstance("SHA256withRSA").run { initVerify(pub); update(signedData); verify(sig) }
        if (!ok) error("подпись v2 не сходится с ключом")
        // дайджест из signedData и сертификат
        var s0 = 0
        val digestsLen = u32(signedData, s0); s0 += 4
        val dEntryLen = u32(signedData, s0 + 0); val dAlgo = u32(signedData, s0 + 4); val dLen = u32(signedData, s0 + 8)
        require(dAlgo == V2_ALGO_RSA_SHA256) { "неожиданный алгоритм дайджеста" }
        val declared = signedData.copyOfRange(s0 + 12, s0 + 12 + dLen)
        s0 += digestsLen
        val certsLen = u32(signedData, s0); s0 += 4
        val certLen = u32(signedData, s0 + 0)
        val certDer = signedData.copyOfRange(s0 + 4, s0 + 4 + certLen)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate
        if (!cert.publicKey.encoded.contentEquals(pk)) error("ключ в блоке не из сертификата")
        // пересчёт по файлу: в EOCD указатель на каталог заменяется на начало блока подписи
        val eocd = t.eocd.copyOf().also { e -> for (k in 0..3) e[16 + k] = (start shr (8 * k)).toByte() }
        val actual = rootDigest(raf, start, t.cd, eocd)
        if (!actual.contentEquals(declared)) error("архив изменён после подписи v2")
        require(dEntryLen == 8 + dLen) { "битая запись дайджеста" }
        V2Result(MessageDigest.getInstance("SHA-256").digest(certDer).joinToString("") { "%02x".format(it) })
    }

    // ---------- проверка ----------

    class Verified(val fingerprint: String, val files: Int)

    /** Проверяет v1-подпись так же, как это делает установщик. */
    fun verify(apk: File): Verified {
        ZipFile(apk).use { z ->
            fun read(n: String) = (z.getEntry(n) ?: error("нет $n — APK не подписан")).let { e -> z.getInputStream(e).use { it.readBytes() } }
            val mf = read(MF); val sf = read(SF); val rsa = read(RSA)

            // 1) PKCS#7: достаём сертификат и подпись
            val root = Der.read(rsa, 0).first.children()
            val sd = root[1].children()[0].children()
            val certNode = sd.first { it.tag == 0xA0 }.children()[0]
            val certDer = Der.tlv(0x30, certNode.content)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate
            val signerInfo = sd.last().children()[0].children()
            val sig = signerInfo.last { it.tag == 0x04 }.content
            val ok = Signature.getInstance("SHA256withRSA").run { initVerify(cert.publicKey); update(sf); verify(sig) }
            if (!ok) error("подпись CERT.SF не сходится с сертификатом")

            // 2) CERT.SF → дайджест манифеста
            val sfText = String(sf, Charsets.UTF_8)
            val want = Regex("SHA-256-Digest-Manifest: (\\S+)").find(sfText.replace("\r\n ", ""))?.groupValues?.get(1) ?: error("в CERT.SF нет дайджеста манифеста")
            if (want != b64(sha256(mf))) error("MANIFEST.MF изменён после подписи")

            // 3) MANIFEST.MF → дайджесты файлов, и они же в CERT.SF
            val mfText = String(mf, Charsets.UTF_8)
            val blocks = mfText.split("\r\n\r\n").drop(1).filter { it.isNotBlank() }
            val sfBlocks = sfText.split("\r\n\r\n").drop(1).filter { it.isNotBlank() }.associate { b ->
                val flat = b.replace("\r\n ", "")
                flat.substringAfter("Name: ").substringBefore("\r\n") to flat.substringAfter("SHA-256-Digest: ").substringBefore("\r\n")
            }
            var checked = 0
            for (b in blocks) {
                val flat = b.replace("\r\n ", "")
                val name = flat.substringAfter("Name: ").substringBefore("\r\n")
                val dig = flat.substringAfter("SHA-256-Digest: ").substringBefore("\r\n")
                val e = z.getEntry(name) ?: error("в манифесте есть $name, а в архиве нет")
                val md = MessageDigest.getInstance("SHA-256")
                z.getInputStream(e).use { inp -> val buf = ByteArray(65536); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                if (b64(md.digest()) != dig) error("$name изменён после подписи")
                val secDigest = b64(sha256((b + "\r\n\r\n").toByteArray(Charsets.UTF_8)))
                if (sfBlocks[name] != secDigest) error("CERT.SF не совпадает с манифестом для $name")
                checked++
            }
            // 4) в архиве нет неподписанных файлов
            val listed = blocks.map { it.replace("\r\n ", "").substringAfter("Name: ").substringBefore("\r\n") }.toSet()
            for (e in z.entries()) if (!e.isDirectory && !isSignatureFile(e.name) && e.name !in listed) error("${e.name} не подписан")
            return Verified(MessageDigest.getInstance("SHA-256").digest(certDer).joinToString("") { "%02x".format(it) }, checked)
        }
    }
}
