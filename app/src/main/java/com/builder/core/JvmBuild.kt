package com.builder.core

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Вспомогательные вещи для компиляции Kotlin/Java на телефоне: запуск компиляторов через app_process, слияние манифестов, ресурсы из jar. */
object Jvm {
    const val KOTLIN = "2.1.21"
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    const val TOOLS_NS = "http://schemas.android.com/tools"

    /** Версии библиотек без BOM: если в build.gradle платформа не прочиталась, берём проверенный набор. */
    val DEFAULT_BOMS = mapOf("androidx.compose" to "androidx.compose:compose-bom:2024.12.01")

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Размер кучи для компилятора: треть памяти устройства, но в пределах [512, cap] МБ. */
    fun heapMb(abis: List<String>, cap: Int): Int {
        val totalKb = try { File("/proc/meminfo").readLines().first { it.startsWith("MemTotal") }.filter { it.isDigit() }.toLong() } catch (_: Exception) { 4L shl 20 }
        var h = (totalKb / 1024 / 3).toInt().coerceIn(512, cap)
        if (abis.none { it.contains("64") }) h = minOf(h, 1024)
        return h
    }

    /**
     * Путь к dalvikvm, если он запускается из приложения. app_process ограничивает кучу 256 МБ (системное свойство
     * dalvik.vm.heapgrowthlimit, -Xmx его не переопределяет), а dalvikvm даёт столько, сколько попросят.
     */
    @Volatile var dalvik: String? = null

    /** Команда запуска Java-программы (в dex-jar): через dalvikvm с большой кучей, а если он недоступен — через app_process. */
    fun appProcess(heapMb: Int, classpath: List<File>, main: String, args: List<String>, props: List<String> = emptyList()): String =
        buildString {
            val vm = dalvik
            if (vm != null) {
                append("exec ").append(vm).append(" -Xmx${heapMb}m -cp ").append(q(classpath.joinToString(":") { it.path }))
                for (p in props) append(' ').append(q(p))
                append(' ').append(main)
            } else {
                append("exec /system/bin/app_process -Xmx${heapMb}m -XX:HeapGrowthLimit=${heapMb}m -Djava.class.path=").append(q(classpath.joinToString(":") { it.path }))
                for (p in props) append(' ').append(q(p))
                append(" /system/bin ").append(main)
            }
            for (a in args) append(' ').append(q(a))
        }

    fun files(dir: File, ext: String): List<File> =
        if (dir.isDirectory) dir.walkTopDown().onEnter { it.name != "build" && !it.name.startsWith(".") }.filter { it.isFile && it.extension.equals(ext, true) }.toList().sortedBy { it.path } else emptyList()

    /** Класс-файлы каталога → один jar (d8 принимает jar, и список аргументов остаётся коротким). */
    fun jarOf(dirs: List<File>, out: File): Int {
        var n = 0
        ZipOut(out).use { z ->
            for (d in dirs) {
                if (!d.isDirectory) continue
                for (f in d.walkTopDown()) if (f.isFile) {
                    val name = f.relativeTo(d).path.replace(File.separatorChar, '/')
                    if (z.has(name)) continue
                    z.add(name, f.readBytes(), false); n++
                }
            }
        }
        return n
    }

    /** Убирает из jar классы, которые уже определены в более ранних jar (иначе d8 падает с «defined multiple times»). */
    fun dedupe(jars: List<File>, tmp: File, log: (String) -> Unit): List<File> {
        val seen = HashMap<String, File>()
        val out = ArrayList<File>()
        tmp.mkdirs()
        for ((i, j) in jars.withIndex()) {
            val names = JarIndex.classes(j)
            val dup = names.filter { it in seen && !it.endsWith("module-info.class") }
            if (dup.isEmpty()) { names.forEach { seen[it] = j }; out.add(j); continue }
            log("  дубликаты классов: ${j.name} ∩ ${seen[dup[0]]!!.name} (${dup.size}) — оставляю первые")
            val filtered = File(tmp, "dedupe-$i-${j.name}")
            val drop = dup.toHashSet()
            ZipOut(filtered).use { z -> z.copyFrom(j, skip = { it in drop }) }
            names.filter { it !in drop }.forEach { seen[it] = filtered }
            out.add(filtered)
        }
        return out
    }

    /** Нужны ли в APK Java-ресурсы из jar (services, свойства, шрифты) — класс-файлы и служебные записи пропускаем. */
    private fun wanted(n: String): Boolean {
        if (n.endsWith("/") || n.endsWith(".class")) return false
        if (n.startsWith("META-INF/")) return n.startsWith("META-INF/services/")
        if (n.endsWith(".kotlin_module") || n.endsWith(".kotlin_builtins") || n.endsWith(".kotlin_metadata") || n.endsWith(".pro") || n.endsWith(".java") || n.endsWith(".kt")) return false
        if (n == "module-info.class" || n.startsWith("DebugProbesKt") || n.startsWith("LICENSE") || n.startsWith("NOTICE")) return false
        return true
    }

    /** Копирует Java-ресурсы библиотек в APK; файлы META-INF/services с одинаковым именем склеиваются. */
    fun addJavaResources(z: ZipOut, jars: List<File>): Int {
        val services = LinkedHashMap<String, StringBuilder>()
        var n = 0
        for (j in jars) try {
            ZipFile(j).use { zf ->
                for (e in zf.entries()) {
                    if (!wanted(e.name)) continue
                    val data = zf.getInputStream(e).use { it.readBytes() }
                    if (e.name.startsWith("META-INF/services/")) { services.getOrPut(e.name) { StringBuilder() }.append(String(data)).append('\n'); continue }
                    if (z.has(e.name)) continue
                    z.add(e.name, data, false); n++
                }
            }
        } catch (_: Exception) {}
        for ((k, v) in services) if (!z.has(k)) { z.add(k, v.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n").toByteArray(), false); n++ }
        return n
    }

    // ------------------------------------------------------------------ манифест

    private fun parseXml(text: String): Document {
        val f = DocumentBuilderFactory.newInstance(); f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(text.byteInputStream())
    }

    private fun attr(e: Element, name: String) = e.getAttributeNS(ANDROID_NS, name).ifEmpty { null }

    /**
     * Простое слияние манифестов: из манифестов библиотек в манифест приложения переносятся разрешения, uses-feature,
     * а также activity/service/receiver/provider/uses-library/meta-data (если такого имени там ещё нет).
     * Возвращает итоговый XML и число добавленных элементов.
     */
    private fun kids(e: Element): List<Element> {
        val out = ArrayList<Element>(); var n: Node? = e.firstChild
        while (n != null) { if (n is Element) out.add(n); n = n.nextSibling }
        return out
    }

    fun mergeManifests(appXml: String, appId: String, libs: List<Pair<String, String>>): Pair<String, Int> {
        val doc = parseXml(appXml)
        val root = doc.documentElement
        var app = root.getElementsByTagName("application").item(0) as? Element
        var added = 0
        fun exists(parent: Element, tag: String, name: String?) = kids(parent).any { it.tagName == tag && (name == null || attr(it, "name") == name) }
        fun cleaned(src: Element, libPkg: String): Element {
            val c = doc.importNode(src, true) as Element
            fun walk(e: Element) {
                val toRemove = ArrayList<String>()
                for (i in 0 until e.attributes.length) {
                    val a = e.attributes.item(i)
                    if (a.namespaceURI == TOOLS_NS || a.nodeName.startsWith("tools:")) toRemove.add(a.nodeName)
                    else {
                        var v = a.nodeValue
                        if (v.contains("\${applicationId}")) v = v.replace("\${applicationId}", appId)
                        if (a.localName == "name" && a.namespaceURI == ANDROID_NS && v.startsWith(".")) v = libPkg + v
                        if (a.nodeValue != v) a.nodeValue = v
                    }
                }
                toRemove.forEach { e.removeAttribute(it) }
                kids(e).forEach { walk(it) }
            }
            walk(c)
            return c
        }
        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider", "uses-library", "uses-native-library", "meta-data")
        for ((pkg, xml) in libs) {
            val ld = try { parseXml(xml) } catch (_: Exception) { continue }
            for (n in kids(ld.documentElement)) when (n.tagName) {
                "uses-permission", "uses-permission-sdk-23", "permission", "uses-feature" -> {
                    if (!exists(root, n.tagName, attr(n, "name"))) { root.insertBefore(cleaned(n, pkg), root.firstChild); added++ }
                }
                "application" -> {
                    val target: Element = app ?: doc.createElement("application").also { root.appendChild(it); app = it }
                    for (c in kids(n)) if (c.tagName in componentTags) {
                        var nm = attr(c, "name")
                        if (nm != null && nm.startsWith(".")) nm = pkg + nm
                        if (!exists(target, c.tagName, nm)) { target.appendChild(cleaned(c, pkg)); added++ }
                        else {
                            // одноимённый компонент уже есть (например, службы обнаружения Firebase/ML Kit): объединяем его meta-data,
                            // иначе регистраторы остальных библиотек теряются и приложение падает при старте
                            val have = kids(target).first { it.tagName == c.tagName && attr(it, "name") == nm }
                            for (ch in kids(c)) if (ch.tagName == "meta-data" && !exists(have, "meta-data", attr(ch, "name"))) { have.appendChild(cleaned(ch, pkg)); added++ }
                        }
                    }
                }
            }
        }
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.INDENT, "yes"); t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val sw = java.io.StringWriter()
        t.transform(DOMSource(doc), StreamResult(sw))
        return sw.toString() to added
    }

    fun packageOf(manifestXml: String): String? = Regex("<manifest\\b[^>]*?\\bpackage\\s*=\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).find(manifestXml)?.groupValues?.get(1)

    /** Ошибки компилятора: строки с `error:`/`ошибка` и их контекст — для карточки ошибки. */
    fun errorLines(lines: List<String>): List<String> {
        val clean = lines.map { Ansi.parse(it, 0).text.trim() }.filter { it.isNotEmpty() }
        val errs = clean.filter { it.contains("error:", true) || it.contains("ERROR", false) || it.startsWith("Error") || it.contains("Exception") }
        return (if (errs.isNotEmpty()) errs else clean).takeLast(5)
    }
}
