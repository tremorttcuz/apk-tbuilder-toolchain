package com.builder.core

import java.io.File

/**
 * Сборка APK из проекта Godot 4 прямо на телефоне.
 *
 * Редактор Godot делает APK так: берёт готовый движок (libgodot_android.so + Java-обвязка) и кладёт в assets/ файлы проекта.
 * Здесь то же самое без редактора: движок приходит как обычная Maven-библиотека `org.godotengine:godot` (она есть на Maven Central
 * для каждой версии), а вокруг неё при импорте создаётся крошечный Android-проект — манифест, одна Activity и значок.
 * Обычный конвейер (Maven → ресурсы → Java → dex → подпись) собирает его как любое другое приложение,
 * а на шаге «Сборка APK» файлы проекта Godot добавляются в assets/.
 *
 * Раскладка проекта на телефоне: AndroidManifest.xml, res/, src/ (заготовка), tbuilder.json и godot/ — исходный проект Godot без изменений.
 */
object Godot {
    /** Папка с исходным проектом Godot внутри проекта TBuilder. */
    const val DIR = "godot"

    class Info(
        val name: String,
        /** «4.3» — версия редактора, в которой сохранён проект. null — не указана. */
        val version: String?,
        val configVersion: Int,
        val iconPath: String?,
        val orientation: Int,
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        /** Архитектуры из пресета экспорта; пусто — пресета нет. */
        val abis: List<String>,
        val mono: Boolean,
    ) {
        val major: Int get() = version?.substringBefore('.')?.toIntOrNull() ?: if (configVersion >= 5) 4 else 3
        val minor: Int get() = version?.substringAfter('.', "0")?.toIntOrNull() ?: 3
    }

    fun isProject(dir: File) = File(dir, DIR + "/project.godot").isFile

    /** Значения `key=value` из файла в формате ConfigFile: секция → ключ → значение (строки без кавычек). */
    private fun config(text: String): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var sec = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) { sec = line.substring(1, line.length - 1); continue }
            val eq = line.indexOf('='); if (eq <= 0) continue
            out.getOrPut(sec) { LinkedHashMap() }[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return out
    }

    private fun unq(v: String?) = v?.trim()?.let { if (it.length >= 2 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1) else it }

    private fun slug(s: String): String {
        val t = s.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return if (t.isEmpty()) "game" else if (t[0].isDigit()) "g$t" else t
    }

    private val validPackage = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val javaKeywords = setOf("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "true", "false", "null")

    private fun okPackage(p: String) = validPackage.matches(p) && p.split('.').none { it in javaKeywords }

    /** Читает project.godot и (если есть) настройки Android-пресета из export_presets.cfg. */
    fun parse(projectGodot: String, exportPresets: String?, deviceAbis: List<String>): Info {
        val cfg = config(projectGodot)
        val app = cfg["application"].orEmpty()
        val name = unq(app["config/name"]).orEmpty().ifBlank { "Godot Game" }
        val features = Regex("\"([^\"]*)\"").findAll(app["config/features"].orEmpty()).map { it.groupValues[1] }.toList()
        val version = features.firstOrNull { Regex("\\d+\\.\\d+").matches(it) }
        val configVersion = cfg[""]?.get("config_version")?.toIntOrNull() ?: 5
        val orientation = cfg["display"]?.get("window/handheld/orientation")?.toIntOrNull() ?: 0

        var uniq: String? = null; var code = 1; var vname = "1.0"; var abis = emptyList<String>()
        if (exportPresets != null) {
            val ex = config(exportPresets)
            val preset = ex.keys.firstOrNull { k -> Regex("preset\\.\\d+").matches(k) && unq(ex[k]?.get("platform")) == "Android" }
            val o = preset?.let { ex["$it.options"] }.orEmpty()
            uniq = unq(o["package/unique_name"])
            o["version/code"]?.toIntOrNull()?.let { code = it }
            unq(o["version/name"])?.takeIf { it.isNotBlank() }?.let { vname = it }
            abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").filter { o["architectures/$it"] == "true" }
        }
        val gen = slug(name)
        var pkg = uniq?.replace("\$genname", gen)?.takeIf { it.isNotBlank() } ?: "org.godotengine.$gen"
        if (!okPackage(pkg)) pkg = "org.godotengine.$gen"
        return Info(name, version, configVersion, unq(app["config/icon"]), orientation, pkg, code, vname, abis,
            features.any { it.equals("C#", true) } || cfg.containsKey("dotnet"))
    }

    /** Строка зависимости: версия движка = версия проекта (4.3 → последняя 4.3.x.stable). */
    fun dependency(info: Info): String = "org.godotengine:godot:" + (info.version ?: "4.3") + ".+"

    private fun orientationAttr(v: Int) = when (v) {
        1 -> "portrait"; 2 -> "reverseLandscape"; 3 -> "reversePortrait"; 4 -> "userLandscape"; 5 -> "userPortrait"; 6 -> "fullSensor"
        else -> "landscape"
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Манифест Android-обёртки: как в шаблоне Godot, плюс INTERNET (HTTPRequest и мультиплеер работают без доп. настроек). */
    fun manifest(info: Info) = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${info.packageName}"
    android:versionCode="${info.versionCode}"
    android:versionName="${xml(info.versionName)}"
    android:installLocation="auto">

    <supports-screens
        android:smallScreens="true"
        android:normalScreens="true"
        android:largeScreens="true"
        android:xlargeScreens="true" />
    <uses-feature android:glEsVersion="0x00030000" android:required="true" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:label="@string/godot_project_name_string"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:isGame="true"
        android:hasFragileUserData="false">

        <activity
            android:name=".GodotApp"
            android:label="@string/godot_project_name_string"
            android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen"
            android:launchMode="singleInstancePerTask"
            android:excludeFromRecents="false"
            android:exported="true"
            android:screenOrientation="${orientationAttr(info.orientation)}"
            android:configChanges="orientation|keyboardHidden|screenSize|smallestScreenSize|density|keyboard|navigation|screenLayout|uiMode"
            android:resizeableActivity="false">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

    fun activity(info: Info) = """package ${info.packageName};

import org.godotengine.godot.GodotActivity;

/** Точка входа: всё остальное делает GodotActivity из библиотеки движка. */
public class GodotApp extends GodotActivity {
}
"""

    fun strings(info: Info) = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="godot_project_name_string">${xml(info.name).replace("'", "\\'").replace(Regex("^([@?])"), "\\\\$1")}</string>
</resources>
"""

    /** Что не нужно игре внутри APK: служебное редактора, git, шаблоны сборки, кэши. */
    fun skip(rel: String): Boolean {
        val p = rel.replace('\\', '/')
        val top = p.substringBefore('/')
        if (top in setOf(".git", ".github", ".vscode", ".idea", "android", ".import", ".mono", ".vs")) return true
        if (p == "export_presets.cfg" || p == "export_credentials.cfg" || p == ".gitignore" || p == ".gitattributes" || p == ".editorconfig") return true
        if (p.startsWith(".godot/editor") || p.startsWith(".godot/shader_cache") || p.startsWith(".godot/mono")) return true
        return false
    }

    /** Файлы проекта для assets/: пути относительно папки Godot. Каталоги с `.gdignore` движок тоже не видит. */
    fun assets(godotDir: File): List<File> {
        val out = ArrayList<File>()
        fun walk(d: File) {
            for (f in d.listFiles().orEmpty().sortedBy { it.name }) {
                val rel = f.relativeTo(godotDir).path
                if (skip(rel)) continue
                if (f.isDirectory) { if (!File(f, ".gdignore").exists()) walk(f) } else if (f.name != ".gdignore") out.add(f)
            }
        }
        walk(godotDir)
        return out
    }

    /** Есть ли у проекта импортированные ресурсы (.godot/imported). Без них картинки и звуки в игре не загрузятся. */
    fun importedOk(godotDir: File): Boolean {
        if (File(godotDir, ".godot/imported").isDirectory) return true
        // ресурсов, требующих импорта, может и не быть (чистый GDScript и сцены)
        val raw = setOf("png", "jpg", "jpeg", "webp", "svg", "wav", "ogg", "mp3", "glb", "gltf", "fbx", "obj", "ttf", "otf", "bmp", "tga", "hdr", "exr")
        return godotDir.walkTopDown().onEnter { !skip(it.relativeTo(godotDir).path) }.none { it.isFile && it.extension.lowercase() in raw }
    }
}
