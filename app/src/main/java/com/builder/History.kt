package com.builder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.builder.core.Bucket
import com.builder.core.Downloader
import com.builder.core.Engine
import com.builder.core.Rec
import com.builder.core.Span
import com.builder.core.Stats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * График «горы»: плавная кривая числа сборок по часам/дням, заливка, красная линия ошибок.
 * Палец двигает выделение: кривая приглушается, выбранный отрезок загорается, всплывает синяя подсказка.
 */
class MountainChart(c: Context) : View(c) {
    var onSelect: (Int) -> Unit = {}

    private var data: List<Bucket> = emptyList()
    private var axis: (Int) -> String? = { null }
    private var tip: (Int) -> String = { "" }
    private var vFrom = FloatArray(0); private var vTo = FloatArray(0)
    private var fFrom = FloatArray(0); private var fTo = FloatArray(0)
    private var mFrom = 4f; private var mTo = 4f
    private var morph = 1f
    private var drawIn = 1f
    private var focus = 0f
    private var selPos = 0f
    private var pop = 0f
    var selected = -1; private set
    private var moved = false
    private var wasSelected = -1

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Path(); private val fill = Path(); private val fail = Path(); private val seg = Path()
    private val rect = RectF()
    private var l = 0f; private var r = 0f; private var top = 0f; private var bot = 0f

    private var morphA: ValueAnimator? = null
    private var drawA: ValueAnimator? = null
    private var focusA: ValueAnimator? = null
    private var posA: ValueAnimator? = null
    private var popA: ValueAnimator? = null

    private val dim get() = if (Pal.dark) 0xFF22345C.toInt() else 0xFFB8C6E4.toInt()

    init { isClickable = true }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(250))

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        l = context.pxf(4f); r = w - context.pxf(46f); top = context.pxf(46f); bot = h - context.pxf(30f)
    }

    override fun onDetachedFromWindow() {
        listOf(morphA, drawA, focusA, posA, popA).forEach { it?.cancel() }; super.onDetachedFromWindow()
    }

    private fun maxFor(b: List<Bucket>): Float {
        val m = (b.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)
        return (((m + 3) / 4) * 4).toFloat().coerceAtLeast(4f) // кратно 4, чтобы подписи сетки были целыми
    }

    private fun shownVals(): FloatArray = FloatArray(vTo.size) { i -> val a = vFrom.getOrElse(i) { 0f }; a + (vTo[i] - a) * morph }
    private fun shownFail(): FloatArray = FloatArray(fTo.size) { i -> val a = fFrom.getOrElse(i) { 0f }; a + (fTo[i] - a) * morph }
    private fun shownMax() = mFrom + (mTo - mFrom) * morph

    /** Новые данные. При смене диапазона горы «вырастают» заново, при обновлении — плавно перетекают. */
    fun setData(b: List<Bucket>, axisLabel: (Int) -> String?, tipText: (Int) -> String, rebuild: Boolean) {
        val prevV = shownVals(); val prevF = shownFail(); val prevM = shownMax()
        val same = !rebuild && b.size == vTo.size
        data = b; axis = axisLabel; tip = tipText
        vTo = FloatArray(b.size) { b[it].total.toFloat() }
        fTo = FloatArray(b.size) { b[it].failed.toFloat() }
        vFrom = if (same) prevV else FloatArray(b.size)
        fFrom = if (same) prevF else FloatArray(b.size)
        mFrom = if (same) prevM else 4f; mTo = maxFor(b)
        morphA?.cancel(); morph = 0f
        morphA = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (same) 700 else 900; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }; start()
        }
        if (!same) {
            drawA?.cancel(); drawIn = 0f
            drawA = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100; interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { drawIn = it.animatedValue as Float; invalidate() }; start()
            }
            clearSelection(false)
        }
        invalidate()
    }

    fun replay() { drawA?.cancel(); drawIn = 0f
        drawA = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { drawIn = it.animatedValue as Float; invalidate() }; start()
        }
        morphA?.cancel(); vFrom = FloatArray(vTo.size); fFrom = FloatArray(fTo.size); morph = 0f
        morphA = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }; start()
        }
    }

    private fun clearSelection(notify: Boolean) {
        selected = -1
        focusA?.cancel(); popA?.cancel()
        focusA = ValueAnimator.ofFloat(focus, 0f).apply { duration = 220; addUpdateListener { focus = it.animatedValue as Float; invalidate() }; start() }
        popA = ValueAnimator.ofFloat(pop, 0f).apply { duration = 160; addUpdateListener { pop = it.animatedValue as Float; invalidate() }; start() }
        if (notify) onSelect(-1)
    }

    private fun select(i: Int) {
        val first = selected < 0
        if (i == selected) return
        selected = i
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        posA?.cancel()
        if (first) selPos = i.toFloat()
        else posA = ValueAnimator.ofFloat(selPos, i.toFloat()).apply {
            duration = 170; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { selPos = it.animatedValue as Float; invalidate() }; start()
        }
        if (first) {
            focusA?.cancel(); popA?.cancel()
            focusA = ValueAnimator.ofFloat(focus, 1f).apply { duration = 260; addUpdateListener { focus = it.animatedValue as Float; invalidate() }; start() }
            popA = ValueAnimator.ofFloat(pop, 1f).apply { duration = 340; interpolator = OvershootInterpolator(2.2f); addUpdateListener { pop = it.animatedValue as Float; invalidate() }; start() }
        }
        onSelect(i)
    }

    private fun indexAt(x: Float): Int {
        val n = data.size; if (n < 2) return 0
        return Math.round((x - l) / (r - l) * (n - 1)).coerceIn(0, n - 1)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (data.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); moved = false; wasSelected = selected; select(indexAt(e.x)) }
            MotionEvent.ACTION_MOVE -> { moved = true; select(indexAt(e.x)) }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); if (!moved && wasSelected == selected) clearSelection(true); performClick() }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun px(i: Int, n: Int) = if (n < 2) (l + r) / 2 else l + (r - l) * i / (n - 1)
    private fun py(v: Float, max: Float) = bot - (bot - top) * (v / max).coerceIn(0f, 1.05f)

    /** Равномерный Catmull-Rom → кубические кривые Безье; контрольные точки не вылезают за график. */
    private fun smooth(v: FloatArray, max: Float, out: Path) {
        out.reset(); val n = v.size; if (n == 0) return
        out.moveTo(px(0, n), py(v[0], max))
        for (i in 0 until n - 1) {
            val i0 = maxOf(i - 1, 0); val i3 = minOf(i + 2, n - 1)
            val x1 = px(i, n); val y1 = py(v[i], max); val x2 = px(i + 1, n); val y2 = py(v[i + 1], max)
            val c1y = (y1 + (y2 - py(v[i0], max)) / 6f).coerceIn(top, bot)
            val c2y = (y2 - (py(v[i3], max) - y1) / 6f).coerceIn(top, bot)
            out.cubicTo(x1 + (x2 - px(i0, n)) / 6f, c1y, x2 - (px(i3, n) - x1) / 6f, c2y, x2, y2)
        }
    }

    private val hi = Path()

    /** Длина дуги, на которой нарисованная кривая проходит через координату x (x вдоль кривой монотонно растёт — поиск делением пополам). */
    private fun distAtX(pm: PathMeasure, x: Float): Float {
        val pt = FloatArray(2)
        var lo = 0f; var hiD = pm.length
        repeat(26) { val mid = (lo + hiD) / 2; pm.getPosTan(mid, pt, null); if (pt[0] < x) lo = mid else hiD = mid }
        return (lo + hiD) / 2
    }

    /** Высота кривой в дробной позиции индекса (для кружков на концах выделенного отрезка). */
    private fun yAt(v: FloatArray, max: Float, pos: Float): Float {
        val n = v.size; if (n == 0) return bot
        val i = Math.floor(pos.toDouble()).toInt().coerceIn(0, n - 1); val t = pos - i
        fun g(k: Int) = v[k.coerceIn(0, n - 1)]
        val a = g(i - 1); val b = g(i); val c = g(i + 1); val d = g(i + 2)
        val y = 0.5f * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (-a + 3 * b - 3 * c + d) * t * t * t)
        return py(y.coerceIn(0f, max), max)
    }

    override fun onDraw(cv: Canvas) {
        val n = data.size
        val w = width.toFloat()
        val max = shownMax(); val vals = shownVals(); val fl = shownFail()

        // сетка и подписи справа
        p.style = Paint.Style.STROKE; p.strokeWidth = context.pxf(1f); p.color = Pal.LINE
        p.pathEffect = DashPathEffect(floatArrayOf(context.pxf(2f), context.pxf(4f)), 0f)
        p.textSize = context.spf(11f); p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        for (k in 0..4) {
            val y = bot - (bot - top) * k / 4f
            p.style = Paint.Style.STROKE; p.color = Pal.LINE; p.pathEffect = DashPathEffect(floatArrayOf(context.pxf(2f), context.pxf(4f)), 0f)
            cv.drawLine(l, y, r + context.pxf(4f), y, p)
            p.pathEffect = null; p.style = Paint.Style.FILL; p.color = Pal.MUTED; p.textAlign = Paint.Align.LEFT
            cv.drawText("${Math.round(max * k / 4f)}", r + context.pxf(10f), y + context.pxf(4f), p)
        }
        p.pathEffect = null

        // подписи оси X
        p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL
        for (i in 0 until n) {
            val s = axis(i) ?: continue
            val near = if (selected >= 0) (1f - minOf(1f, Math.abs(selPos - i))) * focus else 0f
            p.color = lerpColor(Pal.MUTED, Pal.TEXT, near)
            cv.drawText(s, px(i, n), bot + context.pxf(20f), p)
        }

        if (n == 0) return
        val total = data.sumOf { it.total }
        if (total == 0) {
            p.color = Pal.MUTED; p.textSize = context.spf(13f); p.textAlign = Paint.Align.CENTER
            p.alpha = (255 * morph).toInt()
            cv.drawText(com.builder.core.L.tr("Сборок за этот период нет"), (l + r) / 2, (top + bot) / 2, p); p.alpha = 255
            p.style = Paint.Style.STROKE; p.strokeWidth = context.pxf(2f); p.color = Pal.ACCENT
            cv.drawLine(l, bot, l + (r - l) * drawIn, bot, p)
            return
        }

        // всё, что рисуется «вдоль» кривой, обрезаем по ходу появления
        val clipX = l + (r - l + context.pxf(4f)) * drawIn
        cv.save(); cv.clipRect(0f, 0f, clipX, bot + context.pxf(6f))

        smooth(vals, max, line)
        fill.set(line); fill.lineTo(px(n - 1, n), bot); fill.lineTo(px(0, n), bot); fill.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, top, 0f, bot, intArrayOf(lerpColor(Pal.ACCENT, dim, focus * .7f) and 0x00FFFFFF or ((90 * (1f - focus * .65f)).toInt() shl 24), 0x002F6BFF), null, Shader.TileMode.CLAMP)
        cv.drawPath(fill, p); p.shader = null

        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = context.pxf(2.5f); p.color = lerpColor(Pal.ACCENT, dim, focus)
        cv.drawPath(line, p)

        val hasFail = fTo.any { it > 0f }
        if (hasFail) {
            smooth(fl, max, fail)
            p.strokeWidth = context.pxf(1.6f); p.color = lerpColor(Pal.ERROR, (if (Pal.dark) 0xFF4A2A3A.toInt() else 0xFFE8C4CA.toInt()), focus)
            cv.drawPath(fail, p)
        }
        cv.restore()

        // выделенный отрезок: свет по кривой на половину шага в каждую сторону и кружки на концах.
        // Отрезок вырезается из самой нарисованной кривой (PathMeasure), а не обрезается вертикальной линией:
        // концы получаются скруглёнными и перпендикулярными кривой, а кружки стоят ровно на ней.
        if (focus > 0.01f && selected >= 0 && n > 1) {
            val a = (selPos - .5f).coerceAtLeast(0f); val b = (selPos + .5f).coerceAtMost((n - 1).toFloat())
            val xa = px(0, n) + (r - l) * a / (n - 1); val xb = px(0, n) + (r - l) * b / (n - 1)
            val sx = px(0, n) + (r - l) * selPos / (n - 1)
            val pmLine = PathMeasure(line, false)
            val da = distAtX(pmLine, xa); val db = distAtX(pmLine, xb)

            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = context.pxf(3.5f); p.color = Pal.ACCENT; p.alpha = (255 * focus).toInt()
            hi.reset(); pmLine.getSegment(da, db, hi, true); cv.drawPath(hi, p)
            if (hasFail) {
                val pmFail = PathMeasure(fail, false)
                p.strokeWidth = context.pxf(2.2f); p.color = Pal.ERROR; p.alpha = (255 * focus).toInt()
                hi.reset(); pmFail.getSegment(distAtX(pmFail, xa), distAtX(pmFail, xb), hi, true); cv.drawPath(hi, p)
            }
            p.alpha = 255

            // пунктирная вертикаль через выбранную точку
            p.style = Paint.Style.STROKE; p.strokeWidth = context.pxf(1f); p.color = Pal.GLOW; p.alpha = (110 * focus).toInt()
            p.pathEffect = DashPathEffect(floatArrayOf(context.pxf(2f), context.pxf(3f)), 0f)
            cv.drawLine(sx, top - context.pxf(4f), sx, bot, p); p.pathEffect = null; p.alpha = 255

            // кружки строго на кривой: берём точку по длине дуги
            val pt = FloatArray(2)
            for (d in floatArrayOf(da, db)) {
                pmLine.getPosTan(d, pt, null)
                p.style = Paint.Style.FILL; p.color = Pal.BG; cv.drawCircle(pt[0], pt[1], context.pxf(5f) * focus, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = context.pxf(2f); p.color = Pal.ACCENT; cv.drawCircle(pt[0], pt[1], context.pxf(5f) * focus, p)
            }
            pmLine.getPosTan(distAtX(pmLine, sx), pt, null)
            p.style = Paint.Style.FILL; p.color = Pal.GLOW; cv.drawCircle(pt[0], pt[1], context.pxf(3.2f) * focus, p)

            // подсказка
            val text = com.builder.core.L.tr(tip(selected.coerceIn(0, n - 1)))
            p.textSize = context.spf(13f); p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); p.textAlign = Paint.Align.CENTER
            val bw = p.measureText(text) + context.pxf(28f); val bh = context.pxf(30f)
            val bx = sx.coerceIn(bw / 2 + context.pxf(4f), w - bw / 2 - context.pxf(4f))
            val by = context.pxf(6f)
            cv.save(); cv.scale(pop, pop, bx, by + bh)
            p.style = Paint.Style.FILL; p.color = Pal.ACCENT; p.alpha = (255 * minOf(1f, pop)).toInt()
            rect.set(bx - bw / 2, by, bx + bw / 2, by + bh); cv.drawRoundRect(rect, context.pxf(9f), context.pxf(9f), p)
            p.color = Pal.ON_ACCENT; cv.drawText(com.builder.core.L.tr(text), bx, by + bh / 2 - (p.descent() + p.ascent()) / 2, p)
            cv.restore(); p.alpha = 255
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
}

/** Строка «проект — доля сборок»: полоса заливается слева направо, процент считается вверх. */
class ShareRow(ctx: Context, name: String, builds: Int, failed: Int, private val share: Float, delay: Long) : LinearLayout(ctx) {
    private val pct = TTextView(ctx)
    private val bar = LineBar(ctx)

    init {
        orientation = VERTICAL; setPadding(0, ctx.px(12), 0, ctx.px(12))
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nm = TTextView(ctx).apply { text = name; textSize = 15f; setTextColor(Pal.TEXT); maxLines = 1; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        val cnt = TTextView(ctx).apply { text = "$builds сб." + if (failed > 0) " · $failed ош." else ""; textSize = 11f; setTextColor(if (failed > 0) Pal.WARN else Pal.MUTED); typeface = Typeface.MONOSPACE }
        pct.apply { textSize = 13f; setTextColor(Pal.SOFT); typeface = Typeface.MONOSPACE; gravity = Gravity.END }
        head.addView(nm, LayoutParams(0, WRAP_CONTENT, 1f)); head.addView(cnt, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(12) })
        head.addView(pct, LayoutParams(ctx.px(58), WRAP_CONTENT))
        addView(head)
        addView(bar, LayoutParams(MATCH_PARENT, ctx.px(3)).apply { topMargin = ctx.px(9) })
        alpha = 0f; translationY = ctx.pxf(12f)
        animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(360).setInterpolator(DecelerateInterpolator(2f)).start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay + 120; duration = 900; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { val v = it.animatedValue as Float; bar.set(share * v); pct.text = "%.1f%%".format(share * 100f * v).replace('.', ',') }
            start()
        }
    }
}

/** Строка последней сборки: квадрат-статус, имя, время «назад» и размер. */
class RecentRow(ctx: Context, r: Rec, delay: Long) : LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.px(11), 0, ctx.px(11))
        val mark = View(ctx).apply { setBackgroundColor(if (r.ok) Pal.OK else Pal.ERROR) }
        addView(mark, LayoutParams(ctx.px(8), ctx.px(8)).apply { marginEnd = ctx.px(14) })
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(TTextView(ctx).apply { text = r.apk ?: r.project; textSize = 14f; setTextColor(Pal.TEXT); maxLines = 1 })
        col.addView(TTextView(ctx).apply {
            text = r.project + "  ·  " + Engine.fmtMs(r.ms) + if (r.size > 0) "  ·  " + Downloader.human(r.size) else ""
            textSize = 11f; setTextColor(Pal.MUTED); typeface = Typeface.MONOSPACE
        })
        addView(col, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TTextView(ctx).apply { text = ago(r.t); textSize = 11f; setTextColor(Pal.SOFT); typeface = Typeface.MONOSPACE })
        alpha = 0f; translationX = ctx.pxf(18f)
        animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun ago(t: Long): String {
        val d = System.currentTimeMillis() - t
        return when {
            d < 60_000 -> "только что"
            d < 3_600_000 -> "${d / 60_000} мин назад"
            d < 86_400_000 -> "${d / 3_600_000} ч назад"
            d < 172_800_000 -> "вчера"
            else -> SimpleDateFormat("d MMM", com.builder.core.L.locale()).format(Date(t))
        }
    }
}

/** Вкладка «История»: цифры, график-«горы» с выбором пальцем, доли по проектам, последние сборки. */
class HistoryPage(ctx: Context, private val engine: Engine) : ScrollView(ctx) {
    private val box = LinearLayout(ctx)
    private val big = TTextView(ctx)
    private val caption = TTextView(ctx)
    private val sub = TTextView(ctx)
    private val chart = MountainChart(ctx)
    private val range: TabBar
    private val tiles = LinearLayout(ctx)
    private val sections = LinearLayout(ctx)
    private var span = Span.WEEK
    private var recs: List<Rec> = emptyList()
    private var buckets: List<Bucket> = emptyList()
    private var shownNum = 0
    private var numAnim: ValueAnimator? = null

    private val dayFmt = SimpleDateFormat("d MMM", com.builder.core.L.locale())
    private val hourFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        box.orientation = LinearLayout.VERTICAL; box.setPadding(ctx.px(24), ctx.px(18), ctx.px(24), ctx.px(40))

        // шапка: подпись, крупное число, пояснение, легенда
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        caption.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 11f; letterSpacing = 0.2f; setTextColor(Pal.MUTED); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); text = "СБОРКИ" }
        big.apply { maxLines = 1; includeFontPadding = false; textSize = 46f; setTextColor(Pal.TEXT); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        sub.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 12f; setTextColor(Pal.SOFT); typeface = Typeface.MONOSPACE }
        left.addView(caption); left.addView(big); left.addView(sub)
        val legend = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        fun dot(color: Int, text: String) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(View(ctx).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(ctx.px(8), ctx.px(8)).apply { marginEnd = ctx.px(8) })
            addView(TTextView(ctx).apply { this.text = text; textSize = 12f; setTextColor(Pal.SOFT) })
        }
        legend.addView(dot(Pal.ACCENT, "Сборки")); legend.addView(dot(Pal.ERROR, "Ошибки"), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(6) })
        head.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); head.addView(legend, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(4) })
        box.addView(head)

        range = TabBar(ctx, Span.values().map { it.label }) { setSpan(Span.values()[it]) }
        box.addView(range, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(14) })
        box.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(6) })
        tiles.orientation = LinearLayout.HORIZONTAL
        box.addView(tiles, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(14) })
        sections.orientation = LinearLayout.VERTICAL
        box.addView(sections, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(box)

        chart.onSelect = { i -> showSelection(i) }
        range.select(1, false)
    }

    private fun setSpan(s: Span) { if (s != span) { span = s; load(true) } }

    /** Перечитывает журнал. [rebuild] — рисовать горы заново (смена диапазона / открытие вкладки). */
    fun load(rebuild: Boolean) {
        recs = engine.allRecords()
        buckets = Stats.buckets(recs, span, System.currentTimeMillis())
        val n = buckets.size
        chart.setData(buckets, { i ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = buckets[i].start }
            when {
                span.hourly -> if (i % 6 == 0) "%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY)) else null
                span == Span.WEEK -> cal.get(java.util.Calendar.DAY_OF_MONTH).toString()
                else -> if ((n - 1 - i) % 5 == 0) cal.get(java.util.Calendar.DAY_OF_MONTH).toString() else null
            }
        }, { i -> tipFor(i) }, rebuild)
        showSelection(-1)
        buildTiles(); buildSections()
    }

    private fun tipFor(i: Int): String {
        val b = buckets.getOrNull(i) ?: return ""
        return if (span.hourly) hourFmt.format(Date(b.start)) + "–" + hourFmt.format(Date(b.end)) else dayFmt.format(Date(b.start))
    }

    private fun countTo(v: Int) {
        numAnim?.cancel()
        numAnim = ValueAnimator.ofInt(shownNum, v).apply {
            duration = 420; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { shownNum = it.animatedValue as Int; big.text = shownNum.toString() }; start()
        }
    }

    private fun showSelection(i: Int) {
        if (i < 0) {
            val total = buckets.sumOf { it.total }; val failed = buckets.sumOf { it.failed }
            caption.text = "СБОРКИ  ·  " + span.label.uppercase()
            countTo(total)
            sub.text = if (total == 0) "пока пусто" else "успешно ${total - failed} · ошибок $failed"
        } else {
            val b = buckets[i]
            caption.text = tipFor(i).uppercase()
            countTo(b.total)
            sub.text = if (b.total == 0) "сборок не было" else "успешно ${b.ok} · ошибок ${b.failed} · ср. ${Engine.fmtMs(b.ms / b.total)}"
        }
    }

    private fun tile(label: String, value: String, delay: Long, first: Boolean): View {
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.SURFACE); setPadding(context.px(14), context.px(12), context.px(14), context.px(14)) }
        c.addView(TTextView(context).apply { text = label; textSize = 10f; letterSpacing = 0.18f; setTextColor(Pal.MUTED); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        c.addView(TTextView(context).apply { text = value; textSize = 20f; setTextColor(Pal.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.px(6) })
        c.alpha = 0f; c.translationY = context.pxf(14f)
        c.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
        tiles.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (!first) marginStart = context.px(8) })
        return c
    }

    private fun buildTiles() {
        tiles.removeAllViews()
        val total = buckets.sumOf { it.total }; val failed = buckets.sumOf { it.failed }
        val ms = buckets.sumOf { it.ms }
        tile("УСПЕХ", if (total == 0) "—" else "${(total - failed) * 100 / total}%", 0, true)
        tile("СРЕДНЕЕ", if (total == 0) "—" else Engine.fmtMs(ms / total), 70, false)
        tile("ПИК", (buckets.maxOfOrNull { it.total } ?: 0).toString(), 140, false)
    }

    private fun title(text: String) = TTextView(context).apply {
        this.text = text; textSize = 11f; letterSpacing = 0.2f; setTextColor(Pal.MUTED); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, context.px(26), 0, context.px(2))
    }

    private fun buildSections() {
        sections.removeAllViews()
        val since = buckets.firstOrNull()?.start ?: 0L
        val shares = Stats.byProject(recs, since)
        if (shares.isNotEmpty()) {
            sections.addView(title("ПО ПРОЕКТАМ"))
            val sum = shares.sumOf { it.builds }.coerceAtLeast(1)
            shares.take(6).forEachIndexed { k, s -> sections.addView(ShareRow(context, s.name, s.builds, s.failed, s.builds.toFloat() / sum, 200L + 90L * k)) }
        }
        val last = recs.takeLast(8).reversed()
        if (last.isNotEmpty()) {
            sections.addView(title("ПОСЛЕДНИЕ СБОРКИ"))
            last.forEachIndexed { k, r -> sections.addView(RecentRow(context, r, 300L + 60L * k)) }
        } else {
            sections.addView(TTextView(context).apply {
                text = "Здесь появятся твои сборки: нажми «Собрать» — и график оживёт."
                textSize = 13f; setTextColor(Pal.MUTED); setPadding(0, context.px(30), 0, 0)
            })
        }
    }

    /** Вкладка открылась: числа считаются заново, горы вырастают. */
    fun onShown() { shownNum = 0; load(true) }
}
