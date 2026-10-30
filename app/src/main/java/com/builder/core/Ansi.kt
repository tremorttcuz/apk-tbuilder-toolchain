package com.builder.core

/**
 * Разбор ANSI SGR-последовательностей (цвет, жирный) для терминала.
 * Возвращает чистый текст и список раскрашенных отрезков; остальные CSI-коды вырезаются.
 */
object Ansi {
    const val ESC = "\u001B"
    const val RESET = "\u001B[0m"

    class Run(val start: Int, val end: Int, val fg: Int, val bold: Boolean)
    class Parsed(val text: String, val runs: List<Run>)

    // Палитра под чёрно-синюю тему приложения (ARGB).
    /** светлая тема терминала: тёмные оттенки вместо светлых, чтобы текст читался на белом фоне */
    @JvmStatic var light = false
    private val LBASE = intArrayOf(
        0xFF4A5778.toInt(), 0xFFC7302F.toInt(), 0xFF0F8A52.toInt(), 0xFF9A6A00.toInt(),
        0xFF2457E6.toInt(), 0xFF7B3FD6.toInt(), 0xFF0B8AA6.toInt(), 0xFF1B2744.toInt(),
    )
    private val LBRIGHT = intArrayOf(
        0xFF7080A0.toInt(), 0xFFD9484A.toInt(), 0xFF12A360.toInt(), 0xFFB07C00.toInt(),
        0xFF3B6BFF.toInt(), 0xFF8F5BE8.toInt(), 0xFF0E9FBF.toInt(), 0xFF0E1A33.toInt(),
    )
    private val BASE get() = if (light) LBASE else DBASE
    private val BRIGHT get() = if (light) LBRIGHT else DBRIGHT
    private val DBASE = intArrayOf(
        0xFF2A3350.toInt(), 0xFFE5484D.toInt(), 0xFF3DD68C.toInt(), 0xFFF5C451.toInt(),
        0xFF5B8CFF.toInt(), 0xFFB07CFF.toInt(), 0xFF3CC8E8.toInt(), 0xFFD5DDF0.toInt(),
    )
    private val DBRIGHT = intArrayOf(
        0xFF56658A.toInt(), 0xFFFF7A7F.toInt(), 0xFF6BF0B0.toInt(), 0xFFFFDC80.toInt(),
        0xFF8BAEFF.toInt(), 0xFFCDA6FF.toInt(), 0xFF7DE0F5.toInt(), 0xFFFFFFFF.toInt(),
    )

    const val DEFAULT = 0

    fun color(s: String) = "$ESC[${s}m"

    fun parse(src: String, defaultFg: Int): Parsed {
        if (src.indexOf('\u001B') < 0) return Parsed(src, emptyList())
        val out = StringBuilder()
        val runs = ArrayList<Run>()
        var fg = defaultFg
        var bold = false
        var runStart = 0
        var i = 0

        fun flush() {
            if (out.length > runStart && (fg != defaultFg || bold)) runs.add(Run(runStart, out.length, fg, bold))
            runStart = out.length
        }

        while (i < src.length) {
            val c = src[i]
            if (c == '\u001B' && i + 1 < src.length && src[i + 1] == '[') {
                var j = i + 2
                while (j < src.length && src[j] !in '@'..'~') j++
                if (j >= src.length) break
                if (src[j] == 'm') {
                    flush()
                    val codes = src.substring(i + 2, j).split(';').map { it.toIntOrNull() ?: 0 }.ifEmpty { listOf(0) }
                    var k = 0
                    while (k < codes.size) {
                        val n = codes[k]
                        when {
                            n == 0 -> { fg = defaultFg; bold = false }
                            n == 1 -> bold = true
                            n == 22 -> bold = false
                            n == 39 -> fg = defaultFg
                            n in 30..37 -> fg = if (bold) BRIGHT[n - 30] else BASE[n - 30]
                            n in 90..97 -> fg = BRIGHT[n - 90]
                            n == 38 && k + 2 < codes.size && codes[k + 1] == 5 -> { fg = xterm(codes[k + 2]); k += 2 }
                            n == 38 && k + 4 < codes.size && codes[k + 1] == 2 -> {
                                fg = (0xFF shl 24) or (codes[k + 2] shl 16) or (codes[k + 3] shl 8) or codes[k + 4]; k += 4
                            }
                        }
                        k++
                    }
                }
                i = j + 1
            } else if (c == '\u001B') {
                i++ // одинокий ESC
            } else {
                out.append(c); i++
            }
        }
        flush()
        return Parsed(out.toString(), runs)
    }

    private fun xterm(n: Int): Int = when {
        n < 8 -> BASE[n]
        n < 16 -> BRIGHT[n - 8]
        n < 232 -> {
            val v = n - 16
            fun lv(x: Int) = if (x == 0) 0 else 55 + x * 40
            (0xFF shl 24) or (lv(v / 36) shl 16) or (lv(v / 6 % 6) shl 8) or lv(v % 6)
        }
        else -> { val g = 8 + (n - 232) * 10; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
    }
}
