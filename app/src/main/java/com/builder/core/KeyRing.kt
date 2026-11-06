package com.builder.core

import java.io.File

/** Какой ключ подпишет сборку: сам ключ, откуда он взят и, если есть, предупреждение. */
class KeyPick(val id: Identity, val source: String, val warning: String? = null)

/**
 * «Умная» связка ключей. Вместо одного глобального ключа сборка сама выбирает подходящий:
 *
 *  1. ключ, привязанный к проекту (keys/projects/<проект>/) — его создают или импортируют для одного проекта;
 *  2. хранилище из самого проекта — `signingConfigs` в build.gradle (storeFile + пароли), если файл лежит в архиве;
 *  3. ключ, которым это приложение (applicationId) уже подписывалось: приложение → отпечаток запоминается после каждой
 *     успешной сборки, и если нужный ключ есть на телефоне (общий или чей-то проектный), берётся именно он;
 *  4. общий ключ (по умолчанию — тот же, что в debug.keystore GitHub-сборок).
 *
 * Если выбранный ключ не совпадает с тем, которым приложение подписывали раньше, сборка об этом предупреждает:
 * Android не даст обновить установленную версию поверх подписанной другим ключом.
 */
class KeyRing(val dir: File) {
    private val registryFile get() = File(dir, "apps.json")

    private fun safe(project: String) = project.replace(Regex("[^A-Za-z0-9._-]"), "_")
    fun projectDir(project: String) = File(dir, "projects/" + safe(project))
    fun hasProjectKey(project: String) = ApkSigner.load(projectDir(project)) != null

    // ------------------------------------------------------------------ реестр «приложение → отпечаток»

    private fun registry(): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        try { (Json.parse(registryFile.readText()) as? JObj)?.forEach { (k, v) -> (v as? String)?.let { m[k] = it } } } catch (_: Exception) {}
        return m
    }

    fun remembered(appId: String?): String? = appId?.takeIf { it.isNotBlank() }?.let { registry()[it] }

    /** Запоминает, каким ключом подписано приложение (после успешной сборки). */
    fun remember(appId: String?, fingerprint: String) {
        if (appId.isNullOrBlank()) return
        synchronized(this) {
            val m = registry()
            if (m[appId] == fingerprint) return
            m[appId] = fingerprint
            try { dir.mkdirs(); registryFile.writeText(Json.stringify(m)) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ хранилище из Gradle

    private class Cfg(val name: String, val file: String, val storePass: String?, val alias: String?, val keyPass: String?)

    /** Содержимое блока `{ … }`, открывающая скобка которого стоит в позиции [open]; null, если скобки не сошлись. */
    private fun block(t: String, open: Int): String? {
        var depth = 0
        for (i in open until t.length) {
            if (t[i] == '{') depth++
            else if (t[i] == '}' && --depth == 0) return t.substring(open + 1, i)
        }
        return null
    }

    /** Конфигурации подписи из `signingConfigs { … }`: только с storeFile; пароли — только строковыми литералами. */
    private fun configs(gradle: String): List<Cfg> {
        val text = gradle.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }
        val head = Regex("signingConfigs\\s*\\{").find(text) ?: return emptyList()
        val body = block(text, head.range.last) ?: return emptyList()
        val out = ArrayList<Cfg>()
        var i = 0
        while (i < body.length) {
            val o = body.indexOf('{', i); if (o < 0) break
            val inner = block(body, o) ?: break
            val pre = body.substring(i, o)
            val name = Regex("[\"']([^\"']+)[\"']").findAll(pre).lastOrNull()?.groupValues?.get(1)
                ?: Regex("([A-Za-z_]\\w*)\\s*$").find(pre.trimEnd())?.groupValues?.get(1) ?: "config"
            fun lit(key: String) = Regex("\\b$key\\b\\s*(?:=|\\()?\\s*[\"']([^\"']*)[\"']").find(inner)?.groupValues?.get(1)
            val file = Regex("\\bstoreFile\\b\\s*(?:=|\\()?\\s*(?:[\\w.]*file\\s*\\()?\\s*[\"']([^\"']+)[\"']").find(inner)?.groupValues?.get(1)
            if (file != null) out.add(Cfg(name, file, lit("storePassword"), lit("keyAlias"), lit("keyPassword")))
            i = o + inner.length + 2
        }
        // release важнее отладочных
        return out.sortedBy { if (it.name.contains("debug", true)) 1 else 0 }
    }

    private fun findStore(projectFiles: File, rel: String): File? {
        File(projectFiles, rel).takeIf { it.isFile }?.let { return it }
        val name = rel.substringAfterLast('/')
        return projectFiles.walkTopDown().maxDepth(3).onEnter { !it.name.startsWith(".") && it.name != "build" }.firstOrNull { it.isFile && it.name == name }
    }

    private class Found(val id: Identity?, val label: String, val problem: String?)

    private fun fromProject(projectFiles: File, gradle: String?): Found? {
        if (gradle.isNullOrBlank()) return null
        for (c in configs(gradle)) {
            val f = findStore(projectFiles, c.file) ?: return Found(null, c.file, "в build.gradle указано хранилище ${c.file}, но в проекте его нет — положи файл в архив или импортируй ключ вручную")
            val pass = c.storePass ?: return Found(null, f.name, "для хранилища ${f.name} пароль не задан строкой в build.gradle (хранится вне проекта) — импортируй ключ вручную")
            return try {
                val (k, cert) = ApkSigner.readStore(f, pass, c.alias, c.keyPass)
                val id = Identity(java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(k)), cert)
                Found(id, f.name + (c.alias?.let { " · $it" } ?: ""), null)
            } catch (e: Exception) { Found(null, f.name, "хранилище ${f.name} не открылось: ${e.message}") }
        }
        return null
    }

    // ------------------------------------------------------------------ выбор

    private val defaultFp: String by lazy { sha(DefaultKey.cert) }
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * Выбирает ключ для сборки. [create] = false — только посмотреть (для экрана настроек): общий ключ при его отсутствии не создаётся,
     * вместо него возвращается null.
     */
    fun pick(project: String, appId: String?, projectFiles: File, gradle: String?, create: Boolean = true): KeyPick? {
        val want = remembered(appId)
        var id: Identity? = null
        var source = ""
        var problem: String? = null

        // 1) ключ проекта
        ApkSigner.load(projectDir(project))?.let { id = it; source = "ключ проекта" }

        // 2) хранилище из build.gradle
        if (id == null) fromProject(projectFiles, gradle)?.let { f ->
            if (f.id != null) { id = f.id; source = "ключ из проекта (${f.label})" } else problem = f.problem
        }

        // 3) ключ, которым это приложение уже подписывалось
        val global = ApkSigner.load(dir)
        if (id == null && want != null) {
            if (global != null && global.fingerprint == want) { id = global; source = "общий ключ (им уже подписано это приложение)" }
            else File(dir, "projects").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.forEach { d ->
                if (id == null) ApkSigner.load(d)?.takeIf { it.fingerprint == want }?.let { id = it; source = "ключ проекта «${d.name}» (им уже подписано это приложение)" }
            }
        }

        // 4) общий ключ
        if (id == null) {
            val g = global ?: if (create) ApkSigner.loadOrCreate(dir) else return null
            id = g
            source = if (g.fingerprint == defaultFp) "общий ключ по умолчанию (как в GitHub-сборке)" else "общий ключ"
        }

        val cur = id!!
        val warn = buildString {
            if (problem != null) append(problem).append(". Подписано другим ключом.")
            if (want != null && cur.fingerprint != want) {
                if (isNotEmpty()) append(' ')
                append("Раньше ").append(appId).append(" подписывалось ключом ").append(want.take(16)).append("…, а эта сборка — ").append(cur.fingerprint.take(16))
                    .append("…: поверх установленной версии она не встанет. Импортируй прежний ключ (Настройки → «Ключ подписи») или удали старое приложение.")
            }
        }.ifEmpty { null }
        return KeyPick(cur, source, warn)
    }

    /** Коротко: чем подпишется текущий проект (для настроек). */
    fun describe(project: String, appId: String?, projectFiles: File, gradle: String?): String {
        val p = try { pick(project, appId, projectFiles, gradle, create = false) } catch (_: Exception) { null }
            ?: return "создастся при первой сборке"
        val kind = when {
            p.source.startsWith("ключ проекта") -> "ключ проекта"
            p.source.startsWith("ключ из проекта") -> "ключ из проекта"
            p.source.contains("по умолчанию") -> "по умолчанию"
            else -> "общий"
        }
        return "$kind · SHA-256 ${p.id.fingerprint.take(16)}…" + if (p.warning != null) " ⚠" else ""
    }
}
