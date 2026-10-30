package com.builder.core

import java.io.File

/** Что удалось вычитать из build.gradle(.kts) модуля. Хранится в проекте как .gradle.json. */
class GradleInfo(
    val namespace: String?, val applicationId: String?, val versionCode: Int?, val versionName: String?,
    val minSdk: Int?, val targetSdk: Int?, val compileSdk: Int?, val deps: List<String>, val kotlin: Boolean,
    val placeholders: Map<String, String>,
    /** BOM-платформы (`platform("androidx.compose:compose-bom:2024.12.01")`): задают версии зависимостям без версии. */
    val boms: List<String> = emptyList(),
    /** `compileOnly`: нужны для компиляции, но в APK не попадают. */
    val compileOnly: List<String> = emptyList(),
    /** Плагины/процессоры, которые на телефоне не выполняются (kapt, ksp, annotationProcessor). */
    val processors: List<String> = emptyList(),
) {
    fun toJson(): String {
        val m = LinkedHashMap<String, Any?>()
        namespace?.let { m["namespace"] = it }; applicationId?.let { m["applicationId"] = it }
        versionCode?.let { m["versionCode"] = it.toDouble() }; versionName?.let { m["versionName"] = it }
        minSdk?.let { m["minSdk"] = it.toDouble() }; targetSdk?.let { m["targetSdk"] = it.toDouble() }; compileSdk?.let { m["compileSdk"] = it.toDouble() }
        m["deps"] = deps; m["kotlin"] = kotlin; m["placeholders"] = placeholders
        m["boms"] = boms; m["compileOnly"] = compileOnly; m["processors"] = processors
        return Json.stringify(m)
    }

    companion object {
        private fun strs(o: JObj, k: String) = (o[k] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

        fun fromJson(text: String): GradleInfo? = try {
            val o = Json.parse(text) as JObj
            GradleInfo(
                o["namespace"] as? String, o["applicationId"] as? String, (o["versionCode"] as? Number)?.toInt(), o["versionName"] as? String,
                (o["minSdk"] as? Number)?.toInt(), (o["targetSdk"] as? Number)?.toInt(), (o["compileSdk"] as? Number)?.toInt(),
                strs(o, "deps"), o["kotlin"] as? Boolean ?: false,
                (o["placeholders"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap(),
                strs(o, "boms"), strs(o, "compileOnly"), strs(o, "processors"),
            )
        } catch (_: Exception) { null }
    }
}

/** Версионный каталог gradle/libs.versions.toml: алиасы `libs.androidx.core.ktx` → `group:artifact:version`. */
class VersionCatalog(text: String) {
    private val versions = HashMap<String, String>()
    private val libs = HashMap<String, String>() // нормализованный алиас → координата

    init {
        var section = ""
        for (raw in text.lines()) {
            val line = raw.substringBefore(" #").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) { section = line.trim('[', ']', ' '); continue }
            val eq = line.indexOf('='); if (eq < 0) continue
            val key = line.substring(0, eq).trim(); val value = line.substring(eq + 1).trim()
            when (section) {
                "versions" -> versions[key] = value.trim('"', '\'')
                "libraries" -> {
                    val inline = if (value.startsWith("\"")) value.trim('"') else null
                    val module = Regex("module\\s*=\\s*\"([^\"]+)\"").find(value)?.groupValues?.get(1)
                        ?: Regex("group\\s*=\\s*\"([^\"]+)\"").find(value)?.groupValues?.get(1)?.let { g ->
                            Regex("name\\s*=\\s*\"([^\"]+)\"").find(value)?.groupValues?.get(1)?.let { "$g:$it" }
                        }
                        ?: inline?.let { if (it.count { c -> c == ':' } >= 2) it.substringBeforeLast(':') else it }
                    if (module == null) continue
                    val ver = Regex("version\\.ref\\s*=\\s*\"([^\"]+)\"").find(value)?.groupValues?.get(1)?.let { versions[it] }
                        ?: Regex("version\\s*=\\s*\"([^\"]+)\"").find(value)?.groupValues?.get(1)
                        ?: inline?.takeIf { it.count { c -> c == ':' } >= 2 }?.substringAfterLast(':')
                    libs[norm(key)] = if (ver != null) "$module:$ver" else module
                }
            }
        }
    }

    private fun norm(s: String) = s.replace('-', '.').replace('_', '.').lowercase()

    /** `androidx.core.ktx` (без префикса libs.) → координата либо null. */
    fun lookup(alias: String): String? = libs[norm(alias)]
}

/** Разбор Gradle-файлов регулярными выражениями: работает и для Kotlin DSL, и для Groovy. Ничего не выполняет. */
object Gradle {
    private fun strip(s: String) = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    private fun str(src: String, key: String): String? =
        Regex("\\b" + key + "\\b\\s*[=(]?\\s*[\"']([^\"']*)[\"']").find(src)?.groupValues?.get(1)

    /** Число после ключа: `minSdk = 26`, `minSdkVersion(24)`; для `versionCode = env ?: 2` берём последнее число строки. */
    private fun num(src: String, key: String): Int? {
        val re = Regex("\\b$key\\b")
        for (line in src.lines()) {
            val m = re.find(line) ?: continue
            val rest = line.substring(m.range.last + 1)
            Regex("^\\s*[=(]?\\s*(\\d+)").find(rest)?.let { return it.groupValues[1].toIntOrNull() }
            Regex("(\\d+)").findAll(rest).lastOrNull()?.let { return it.groupValues[1].toIntOrNull() }
        }
        return null
    }

    /**
     * Переменные build.gradle: `val camerax = "1.3.4"`, `def v = '1.0'`, `ext.v = "1.0"`, `v by extra("1.0")`, строки gradle.properties.
     * Первое объявление побеждает; значения без `$` внутри.
     */
    fun variables(vararg texts: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val assign = Regex("""(?m)^\s*(?:(?:const\s+val|val|var|def|final|String|ext\.|extra\[?["']?)\s*)*([A-Za-z_][\w.]*)["']?\]?\s*(?::\s*String)?\s*(?:=|by\s+extra\(|,)\s*["']([^"'$\n]*)["']""")
        val prop = Regex("""(?m)^\s*([A-Za-z_][\w.]*)\s*=\s*([^\s#][^\n]*?)\s*$""")
        for (t in texts) {
            val clean = strip(t)
            assign.findAll(clean).forEach { m.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
            // gradle.properties: key=value без кавычек
            if (!t.contains("{")) prop.findAll(t).forEach { m.putIfAbsent(it.groupValues[1], it.groupValues[2].trim('"', '\'')) }
        }
        return m
    }

    /** Подставляет `$name`, `${name}`, `${rootProject.ext.name}` и т. п.; неизвестное остаётся как есть. */
    fun substitute(s: String, vars: Map<String, String>): String {
        if ('$' !in s) return s
        return Regex("""\$\{([^}]+)}|\$([A-Za-z_][\w.]*)""").replace(s) { m ->
            val name = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            val short = name.removePrefix("rootProject.").removePrefix("project.").removePrefix("ext.").removePrefix("extra.")
            vars[name] ?: vars[short] ?: vars[short.substringAfterLast('.')] ?: m.value
        }
    }

    fun parse(raw: String, catalog: VersionCatalog? = null, extra: String? = null): GradleInfo {
        val s = strip(raw)
        val vars = variables(raw, extra ?: "")
        val deps = ArrayList<String>(); val boms = ArrayList<String>(); val only = ArrayList<String>(); val proc = ArrayList<String>()
        val dep = Regex("\\b(implementation|api|compileOnly|runtimeOnly|kapt|ksp|annotationProcessor)\\s*\\(?\\s*(platform\\(|enforcedPlatform\\()?\\s*(?:\"([^\"]+)\"|'([^']+)'|libs\\.([\\w.]+))")
        for (m in dep.findAll(s)) {
            val scope = m.groupValues[1]
            val coord = substitute(m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5].let { a -> catalog?.lookup(a) ?: "libs.$a" } }, vars)
            val isBom = m.groupValues[2].isNotEmpty()
            when {
                scope in setOf("kapt", "ksp", "annotationProcessor") -> proc.add(coord)
                isBom -> boms.add(coord)
                scope == "compileOnly" -> only.add(coord)
                else -> deps.add(coord)
            }
        }
        val kotlin = Regex("kotlin-android|org\\.jetbrains\\.kotlin\\.android|kotlin\\(\"android\"\\)|kotlin-kapt|plugin\\.compose|kotlin\\.compose|kotlin-compose").containsMatchIn(s)
        val ph = LinkedHashMap<String, String>()
        Regex("manifestPlaceholders\\s*\\[\\s*[\"'](\\w+)[\"']\\s*\\]\\s*=\\s*[\"']([^\"']*)[\"']").findAll(s).forEach { ph[it.groupValues[1]] = it.groupValues[2] }
        Regex("manifestPlaceholders[^\\n]*").find(s)?.value?.let { line ->
            Regex("[\"'](\\w+)[\"']\\s*(?:to|:)\\s*[\"']([^\"']*)[\"']").findAll(line).forEach { ph[it.groupValues[1]] = it.groupValues[2] }
        }
        return GradleInfo(
            str(s, "namespace"), str(s, "applicationId"), num(s, "versionCode"), str(s, "versionName"),
            num(s, "minSdk(?:Version)?"), num(s, "targetSdk(?:Version)?"), num(s, "compileSdk(?:Version)?"),
            deps, kotlin, ph, boms, only, proc,
        )
    }

    /** Ищет build.gradle(.kts) модуля рядом с манифестом: …/app/src/main → …/app, либо сама папка манифеста. */
    fun findModuleFile(manifestDir: File, stop: File): File? {
        val candidates = ArrayList<File>()
        var d: File? = manifestDir
        repeat(4) {
            d?.let { candidates.add(it) }
            if (d == null || d == stop) return@repeat
            d = d?.parentFile
        }
        // сначала «модульные» каталоги выше src/main, потом остальные
        for (dir in candidates) for (n in listOf("build.gradle.kts", "build.gradle")) {
            val f = File(dir, n)
            if (f.isFile && f.readText().let { it.contains("android") && (it.contains("namespace") || it.contains("applicationId") || it.contains("compileSdk")) }) return f
        }
        return null
    }

    /** Версионный каталог проекта: gradle/libs.versions.toml в корне архива или выше модуля. */
    fun findCatalog(manifestDir: File, stop: File): File? {
        var d: File? = manifestDir
        repeat(6) {
            d?.let { dir -> File(dir, "gradle/libs.versions.toml").takeIf { it.isFile }?.let { return it } }
            if (d == null || d == stop) return null
            d = d?.parentFile
        }
        return null
    }
}
