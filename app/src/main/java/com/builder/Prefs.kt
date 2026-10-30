package com.builder

import android.content.Context
import android.content.res.Configuration
import com.builder.core.Ansi
import com.builder.core.L
import java.util.Locale

/** Пользовательские настройки (язык, тема, поведение) в одном месте. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun lang(c: Context): String = sp(c).getString("lang", "auto") ?: "auto"
    fun theme(c: Context): String = sp(c).getString("theme", "auto") ?: "auto"
    fun notify(c: Context) = sp(c).getBoolean("notify", true)
    fun keepOn(c: Context) = sp(c).getBoolean("keepon", false)
    fun finishedNote(c: Context) = sp(c).getBoolean("finished", true)
    /** Номер версии APK растёт с каждой сборкой: свежая сборка всегда ставится поверх старой. */
    /** Для зависимостей без версии брать самые свежие версии из репозитория (без сети — из кэша). */
    fun freshLibs(c: Context) = sp(c).getBoolean("fresh", true)
    fun autoVersion(c: Context) = sp(c).getBoolean("autover", true)

    fun setStr(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
    fun setBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }

    /** Выставляет язык и тему из настроек и системы. Возвращает true, если что-то поменялось. */
    fun apply(c: Context): Boolean {
        val oldEn = L.en; val oldDark = Pal.dark
        L.en = L.resolve(lang(c), Locale.getDefault().language)
        Pal.dark = when (theme(c)) {
            "dark" -> true
            "light" -> false
            else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        Ansi.light = !Pal.dark
        return oldEn != L.en || oldDark != Pal.dark
    }
}
