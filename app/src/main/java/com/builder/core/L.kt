package com.builder.core

import java.util.Locale

/**
 * Язык интерфейса. Исходные тексты в коде русские; при английском языке они переводятся
 * только при показе (в TextView, терминале, уведомлениях) — внутри движка текст остаётся прежним,
 * потому что по нему разбираются некоторые ошибки.
 */
object L {
    @JvmStatic var en = false

    /** "auto" — как в системе, "ru" или "en". */
    fun resolve(pref: String, systemLang: String): Boolean = when (pref) {
        "en" -> true
        "ru" -> false
        else -> systemLang != "ru" && systemLang != "uk" && systemLang != "be" && systemLang != "kk"
    }

    private val cache = HashMap<String, String>()
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(\\d) Б(?![А-Яа-яЁё])") to "$1 B",
        Regex("(\\d) с(?![А-Яа-яЁё])") to "$1 s",
        Regex("(\\d) мс(?![А-Яа-яЁё])") to "$1 ms",
        Regex("(\\d) ч(?![А-Яа-яЁё])") to "$1 h",
        Regex("(\\d) мин(?![А-Яа-яЁё])") to "$1 min",
        Regex("(\\d+) сек(?![А-Яа-яЁё])") to "$1 s",
        Regex("(\\d+) шт\\.") to "$1 items",
    )
    private val cyr = Regex("[А-Яа-яЁё]")

    fun tr(s: String): String {
        if (!en || s.isEmpty() || !cyr.containsMatchIn(s)) return s
        synchronized(cache) { cache[s]?.let { return it } }
        var t = s
        val p = LDict.pairs
        var i = 0
        while (i < p.size) { if (t.contains(p[i])) t = t.replace(p[i], p[i + 1]); i += 2 }
        for ((r, to) in rules) t = r.replace(t, to)
        synchronized(cache) { if (cache.size > 4000) cache.clear(); cache[s] = t }
        return t
    }

    fun tr(s: CharSequence): CharSequence = if (s is String) tr(s) else s

    /** Дата/время по языку. */
    fun locale(): Locale = if (en) Locale.ENGLISH else Locale("ru")
}
