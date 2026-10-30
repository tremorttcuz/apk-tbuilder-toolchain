package com.builder.core

/**
 * Маленький JSON-парсер без зависимостей: ядро не тянет org.json,
 * поэтому его можно прогонять на обычной JVM.
 * Объекты → Map<String, Any?>, массивы → List<Any?>, числа → Double.
 */
object Json {
    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) p.fail("лишние символы после JSON")
        return v
    }

    fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    fun stringify(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Number -> v.toDouble().let { if (it == Math.floor(it) && !it.isInfinite()) it.toLong().toString() else it.toString() }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + stringify(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { stringify(it) }
        else -> quote(v.toString())
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg (позиция $i)")

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            if (i >= s.length) fail("неожиданный конец")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("неожиданный символ '$c'")
            }
        }

        private fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("ожидалось $w")
            i += w.length
            return v
        }

        private fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull() ?: fail("плохое число")
        }

        private fun str(): String {
            i++ // "
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("строка не закрыта")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) fail("обрыв экранирования")
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) fail("обрыв \\u")
                                b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                if (i >= s.length) fail("массив не закрыт")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                fail("ожидалась , или ]")
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("ожидался ключ")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') fail("ожидалось :")
                i++; ws()
                out[k] = value(); ws()
                if (i >= s.length) fail("объект не закрыт")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                fail("ожидалась , или }")
            }
        }
    }
}

typealias JObj = Map<String, Any?>

fun JObj.str(k: String, d: String = ""): String = (this[k] as? String) ?: d
fun JObj.bool(k: String, d: Boolean = false): Boolean = (this[k] as? Boolean) ?: d
fun JObj.int(k: String, d: Int = 0): Int = (this[k] as? Number)?.toInt() ?: d

@Suppress("UNCHECKED_CAST")
fun JObj.obj(k: String): JObj? = this[k] as? JObj

/** Строка или массив строк → список строк (массив склеивается по строкам). */
fun JObj.strList(k: String): List<String> = when (val v = this[k]) {
    is String -> listOf(v)
    is List<*> -> v.mapNotNull { it as? String }
    else -> emptyList()
}
