package com.builder.core

import java.util.Calendar
import java.util.TimeZone

/** Одна «корзина» графика: час или день. */
class Bucket(val start: Long, val end: Long) {
    var total = 0
    var failed = 0
    var ms = 0L
    val ok get() = total - failed
}

enum class Span(val label: String, val count: Int, val hourly: Boolean) {
    DAY("24 ч", 24, true), WEEK("7 дн", 7, false), MONTH("30 дн", 30, false)
}

class ProjectShare(val name: String, val builds: Int, val failed: Int)

/** Запись истории: время, проект, итог, длительность, файл. */
class Rec(val t: Long, val project: String, val ok: Boolean, val ms: Long, val apk: String?, val size: Long)

/** Чистая арифметика по журналу сборок (без Android — проверяется на JVM). */
object Stats {
    fun parse(rows: List<JObj>): List<Rec> = rows.mapNotNull { r ->
        val t = (r["t"] as? Number)?.toLong() ?: return@mapNotNull null
        Rec(t, r["project"] as? String ?: "?", r["ok"] as? Boolean ?: false, (r["ms"] as? Number)?.toLong() ?: 0L,
            r["apk"] as? String, (r["size"] as? Number)?.toLong() ?: 0L)
    }.sortedBy { it.t }

    /** Корзины подряд, последняя содержит [now]; границы — по местному времени (полночь, начало часа). */
    fun buckets(recs: List<Rec>, span: Span, now: Long, tz: TimeZone = TimeZone.getDefault()): List<Bucket> {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (!span.hourly) cal.set(Calendar.HOUR_OF_DAY, 0)
        val field = if (span.hourly) Calendar.HOUR_OF_DAY else Calendar.DAY_OF_YEAR
        cal.add(field, -(span.count - 1))
        val out = ArrayList<Bucket>()
        for (i in 0 until span.count) {
            val s = cal.timeInMillis
            cal.add(field, 1)
            out.add(Bucket(s, cal.timeInMillis))
        }
        for (r in recs) {
            val b = out.firstOrNull { r.t >= it.start && r.t < it.end } ?: continue
            b.total++; if (!r.ok) b.failed++; b.ms += r.ms
        }
        return out
    }

    fun byProject(recs: List<Rec>, since: Long): List<ProjectShare> =
        recs.filter { it.t >= since }.groupBy { it.project }
            .map { (n, l) -> ProjectShare(n, l.size, l.count { !it.ok }) }
            .sortedWith(compareByDescending<ProjectShare> { it.builds }.thenBy { it.name })
}
