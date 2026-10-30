package com.builder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** TextView, который при английском языке переводит присваиваемые русские строки. */
open class TTextView(c: Context) : TextView(c) {
    override fun setText(t: CharSequence?, type: BufferType?) { super.setText(if (t is String) com.builder.core.L.tr(t) else t, type) }
}

object Pal {
    /** true — тёмная тема; переключается в MainActivity до построения интерфейса. */
    @JvmStatic var dark = true
    private fun c(d: String, l: String) = Color.parseColor(if (dark) d else l)
    val BG get() = c("#000000", "#F4F7FC")
    val SURFACE get() = c("#060A14", "#FFFFFF")
    val RAISED get() = c("#0A1020", "#EAF0FA")
    val LINE get() = c("#14213D", "#D3DDEE")
    val ACCENT get() = c("#2F6BFF", "#2F6BFF")
    val GLOW get() = c("#8BAEFF", "#5C8BFF")
    val TEXT get() = c("#E8EEF9", "#0E1A33")
    val SOFT get() = c("#A9B6D0", "#44557A")
    val MUTED get() = c("#56658A", "#7080A0")
    val ERROR get() = c("#E5484D", "#D93036")
    val OK get() = c("#3DD68C", "#12945A")
    val WARN get() = c("#F5C451", "#B77F00")
    val TERM get() = c("#02050B", "#F8FAFE")
    /** текст и значки поверх синей заливки */
    val ON_ACCENT = Color.WHITE
}

fun Context.px(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.pxf(v: Float) = v * resources.displayMetrics.density
fun Context.spf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Иконка из res/drawable (векторная «SVG»-линия), покрашенная и приведённая к размеру в dp. */
fun Context.ico(res: Int, color: Int, dp: Float = 16f): android.graphics.drawable.Drawable {
    val d = getDrawable(res)!!.mutate()
    d.setTint(color)
    val s = pxf(dp).toInt(); d.setBounds(0, 0, s, s)
    return d
}

/** Иконка в начале/конце текста TextView, по центру строки. */
fun TextView.iconStart(d: android.graphics.drawable.Drawable?, gapDp: Int = 8) { setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = context.px(gapDp) }
fun TextView.iconEnd(d: android.graphics.drawable.Drawable?, gapDp: Int = 8) { setCompoundDrawablesRelative(null, null, d, null); compoundDrawablePadding = context.px(gapDp) }

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    return Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
    )
}

/** Тонкая линия прогресса; без известного размера — бегущий отрезок. */
class LineBar(c: Context) : View(c) {
    private val p = Paint()
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private var indet = false
    private var sweep = 0f
    private val sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1700; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
    }

    private var target = 0f
    // плавное «догоняние» цели каждый кадр: скорость зависит от расстояния, поэтому редкие или частые обновления не дёргают полосу
    private val follow = object : Runnable {
        override fun run() {
            val d = target - shown
            if (Math.abs(d) < 0.0006f) { shown = target; invalidate(); return }
            shown += d * 0.14f; invalidate(); postOnAnimation(this)
        }
    }

    fun progress(t: Float) {
        if (indet) { indet = false; sweepAnim.cancel() }
        target = t.coerceIn(0f, 1f)
        removeCallbacks(follow); postOnAnimation(follow)
    }

    /** Мгновенно, без собственной анимации — когда прогрессом управляет внешний аниматор. */
    fun set(t: Float) { if (indet) { indet = false; sweepAnim.cancel() }; removeCallbacks(follow); anim?.cancel(); shown = t; target = t; invalidate() }

    fun indeterminate() { if (!indet) { indet = true; anim?.cancel(); sweepAnim.start() } }

    fun reset() { indet = false; sweepAnim.cancel(); removeCallbacks(follow); anim?.cancel(); shown = 0f; target = 0f; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (indet && !sweepAnim.isRunning) sweepAnim.start() }

    override fun onDetachedFromWindow() { sweepAnim.cancel(); removeCallbacks(follow); anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = Pal.LINE; cv.drawRect(0f, 0f, w, h, p)
        p.color = Pal.ACCENT
        if (indet) {
            val len = w * .3f; val l = -len + sweep * (w + len)
            cv.drawRect(maxOf(0f, l), 0f, minOf(w, l + len), h, p)
        } else cv.drawRect(0f, 0f, w * shown, h, p)
    }
}

/** Плоская кнопка: сама служит общим индикатором — заливка идёт слева направо, во время работы по ней бежит блик. */
class ActionButton(c: Context) : View(c) {
    var label: String = ""
        set(v) { field = v; invalidate() }
    var ghost = false
        set(v) { field = v; invalidate() }
    var shimmer = false
        set(v) {
            field = v
            if (v) { if (!shimmerAnim.isRunning) shimmerAnim.start() } else { shimmerAnim.cancel(); invalidate() }
        }
    var heightDp = 54
    /** Иконка слева от подписи (в «большом» виде — над ней). */
    var icon: android.graphics.drawable.Drawable? = null
        set(v) { field = v?.mutate(); invalidate() }
    /** 0 — обычная полоса, 1 — большая кнопка по центру экрана. Меняется анимацией вместе с размером. */
    var heroT = 0f
        set(v) { field = v; invalidate() }
    /** Если задано, onMeasure не навязывает высоту — размером управляет родитель. */
    var managed = false

    private var fill = 0f
    private var anim: ValueAnimator? = null
    private var phase = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bw = c.pxf(1f)
    private var band: LinearGradient? = null
    private val m = Matrix()
    private val shimmerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init {
        isClickable = true
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = c.spf(13f)
        p.letterSpacing = 0.14f
        p.textAlign = Paint.Align.CENTER
    }

    private var fillTarget = 0f
    private val followFill = object : Runnable {
        override fun run() {
            val d = fillTarget - fill
            if (Math.abs(d) < 0.0006f) { fill = fillTarget; invalidate(); return }
            fill += d * 0.14f; invalidate(); postOnAnimation(this)
        }
    }

    fun setFill(t: Float, animate: Boolean = true) {
        anim?.cancel(); removeCallbacks(followFill)
        fillTarget = t
        if (!animate) { fill = t; invalidate(); return }
        postOnAnimation(followFill)
    }

    override fun onMeasure(w: Int, h: Int) = if (managed) setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h)) else setMeasuredDimension(MeasureSpec.getSize(w), context.px(heightDp))

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val bandW = w * 0.35f
        band = LinearGradient(0f, 0f, bandW, 0f, intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        glareShader = RadialGradient(0f, 0f, maxOf(w, h) * 0.8f, 0x70FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    // блик под пальцем: при касании вспыхивает в точке нажатия и мягко гаснет после отпускания
    private var glareShader: RadialGradient? = null
    private var glare = 0f
    private var glareAnim: ValueAnimator? = null
    private var touchX = 0f
    private var touchY = 0f

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_MOVE) { touchX = e.x; touchY = e.y; if (glare > 0f) invalidate() }
        return super.onTouchEvent(e)
    }

    private fun glareTo(on: Boolean) {
        glareAnim?.cancel()
        glareAnim = ValueAnimator.ofFloat(glare, if (on) 1f else 0f).apply {
            duration = if (on) 140 else 480; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { glare = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (shimmer && !shimmerAnim.isRunning) shimmerAnim.start() }

    override fun onDetachedFromWindow() { shimmerAnim.cancel(); anim?.cancel(); glareAnim?.cancel(); removeCallbacks(followFill); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = null
        val ht = heroT
        p.style = Paint.Style.FILL; p.color = if (ghost) Pal.SURFACE else lerpColor(Pal.SURFACE, Pal.ACCENT, ht); cv.drawRect(0f, 0f, w, h, p)
        if (fill > 0f && !ghost) { p.color = Pal.ACCENT; cv.drawRect(0f, 0f, w * fill, h, p) }
        if (shimmer && !ghost) {
            val b = band
            if (b != null) {
                val bandW = w * 0.35f
                m.setTranslate(-bandW + phase * (w + bandW), 0f); b.setLocalMatrix(m)
                p.shader = b
                cv.save(); cv.clipRect(0f, 0f, w, h); cv.drawRect(0f, 0f, w, h, p); cv.restore()
                p.shader = null
            }
        }
        val gs = glareShader
        if (glare > 0.01f && gs != null) {
            m.setTranslate(touchX, touchY); gs.setLocalMatrix(m)
            p.style = Paint.Style.FILL; p.shader = gs; p.alpha = (glare * 255).toInt()
            cv.save(); cv.clipRect(0f, 0f, w, h); cv.drawRect(0f, 0f, w, h, p); cv.restore()
            p.shader = null; p.alpha = 255
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = if (ghost) Pal.MUTED else Pal.ACCENT
        cv.drawRect(bw / 2, bw / 2, w - bw / 2, h - bw / 2, p)
        val base = if (ghost) Pal.SOFT else lerpColor(Pal.TEXT, Pal.ON_ACCENT, ht)
        drawContent(cv, w, h, ht, base)
        if (!ghost && fill > 0f && ht < 1f) { // поверх синей заливки подпись белая, на фоне — цвета темы
            cv.save(); cv.clipRect(0f, 0f, w * fill, h); drawContent(cv, w, h, ht, Pal.ON_ACCENT); cv.restore()
        }
    }

    private fun drawContent(cv: Canvas, w: Float, h: Float, ht: Float, color: Int) {
        p.style = Paint.Style.FILL; p.color = color
        val text = com.builder.core.L.tr(label).uppercase()
        val ic = icon
        // подпись никогда не обрезается: если не влезает, шрифт чуть уменьшается
        val baseSize = context.spf(13f); p.textSize = baseSize
        val avail = w - context.pxf(if (ic != null && heroT < 0.5f) 76f else 28f)
        val fullW = p.measureText(text)
        if (fullW > avail) p.textSize = baseSize * (avail / fullW).coerceAtLeast(0.6f)
        if (ic == null) {
            cv.drawText(text, w / 2, h / 2 - (p.descent() + p.ascent()) / 2, p)
            p.textSize = baseSize
            return
        }
        // и полоса, и большая кнопка — одна и та же раскладка, между ними плавный переход
        val tw = p.measureText(text)
        val sBar = context.pxf(20f); val sHero = context.pxf(60f)
        val s = sBar + (sHero - sBar) * ht
        val gap = context.pxf(10f)
        val rowW = s + gap + tw
        val barIx = (w - rowW) / 2; val barIy = h / 2 - s / 2
        val barTx = barIx + s + gap + tw / 2
        val heroIx = w / 2 - s / 2; val heroIy = h / 2 - s - context.pxf(2f)
        val ix = barIx + (heroIx - barIx) * ht; val iy = barIy + (heroIy - barIy) * ht
        val tx = barTx + (w / 2 - barTx) * ht
        val ty = h / 2 - (p.descent() + p.ascent()) / 2 + context.pxf(34f) * ht
        ic.setTint(color); ic.setBounds(ix.toInt(), iy.toInt(), (ix + s).toInt(), (iy + s).toInt()); ic.draw(cv)
        cv.drawText(text, tx, ty, p)
        p.textSize = baseSize
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (!shimmer) glareTo(pressed)
        animate().scaleX(if (pressed) .985f else 1f).scaleY(if (pressed) .94f else 1f)
            .setStartDelay(0).setDuration(if (pressed) 80 else 170).setInterpolator(DecelerateInterpolator()).start()
    }
}

/** Небольшая квадратная кнопка-иконка с рамкой; при нажатии мягко утапливается, рамка и иконка подсвечиваются. */
class IconButton(c: Context, private val res: Int, desc: String) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = c.ico(res, Pal.SOFT, 18f)
    private var glow = 0f
    private var anim: ValueAnimator? = null

    init { isClickable = true; contentDescription = desc }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.px(38), context.px(38))

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) .92f else 1f).scaleY(if (pressed) .92f else 1f).setStartDelay(0).setDuration(if (pressed) 80 else 180).start()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(glow, if (pressed) 1f else 0f).apply {
            duration = if (pressed) 90 else 260; addUpdateListener { glow = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val bw = context.pxf(1f)
        p.style = Paint.Style.FILL; p.color = lerpColor(Pal.SURFACE, Pal.RAISED, glow); cv.drawRect(0f, 0f, w, h, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = lerpColor(Pal.LINE, Pal.ACCENT, glow)
        cv.drawRect(bw / 2, bw / 2, w - bw / 2, h - bw / 2, p)
        val s = context.px(18)
        d.setTint(lerpColor(Pal.SOFT, Pal.TEXT, glow))
        d.setBounds(((w - s) / 2).toInt(), ((h - s) / 2).toInt(), ((w + s) / 2).toInt(), ((h + s) / 2).toInt()); d.draw(cv)
    }
}

/** Статус справа в шапке: квадрат-индикатор и подпись. Слово меняется плавным перекрёстным переходом, ширина перетекает, счётчик шагов меняется без мигания. */
class StatusPill(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var text = ""
    private var prev = ""
    private var fade = 1f
    private var fadeAnim: ValueAnimator? = null
    private var color = Pal.MUTED
    private var shown = Pal.MUTED
    private var dotAlpha = 1f
    private var colorAnim: ValueAnimator? = null
    private val pulse = ValueAnimator.ofFloat(0.35f, 1f).apply {
        duration = 1400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { dotAlpha = it.animatedValue as Float; invalidate() }
    }

    init {
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = c.spf(11f); p.letterSpacing = 0.14f
    }

    private fun word(t: String) = t.filter { !it.isDigit() }.trim()

    fun set(t: String, col: Int, pulsing: Boolean) {
        if (t != text) {
            if (word(t) != word(text) && text.isNotEmpty()) {
                prev = text; text = t
                fadeAnim?.cancel()
                fade = 0f
                fadeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 320; interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { fade = it.animatedValue as Float; requestLayout(); invalidate() }; start()
                }
            } else { text = t; if (fade >= 1f) { requestLayout(); invalidate() } }
        }
        if (col != color) {
            color = col
            colorAnim?.cancel()
            colorAnim = ValueAnimator.ofArgb(shown, col).apply {
                duration = 420; interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Int; invalidate() }; start()
            }
        }
        pulsingWanted = pulsing
        if (pulsing) { if (!pulse.isRunning) pulse.start() }
        else if (pulse.isRunning) {
            pulse.cancel()
            ValueAnimator.ofFloat(dotAlpha, 1f).apply { duration = 240; addUpdateListener { dotAlpha = it.animatedValue as Float; invalidate() }; start() }
        }
    }

    private fun textW(t: String) = p.measureText(t.uppercase())

    override fun onMeasure(w: Int, h: Int) {
        val d = context.px(8); val gap = context.px(8)
        val tw = if (fade >= 1f) textW(text) else textW(prev) + (textW(text) - textW(prev)) * fade
        setMeasuredDimension((d + gap + tw).toInt() + context.px(2), context.px(24))
    }

    private var pulsingWanted = false
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (pulsingWanted && !pulse.isRunning) pulse.start() }

    override fun onDetachedFromWindow() { pulse.cancel(); colorAnim?.cancel(); fadeAnim?.cancel(); fade = 1f; super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val d = context.pxf(8f); val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = shown; p.alpha = (dotAlpha * 255).toInt()
        cv.drawRect(0f, h / 2 - d / 2, d, h / 2 + d / 2, p)
        val base = h / 2 - (p.descent() + p.ascent()) / 2
        val x = d + context.pxf(8f)
        p.color = lerpColor(Pal.SOFT, shown, 0.6f)
        if (fade < 1f) {
            val dy = context.pxf(4f)
            p.alpha = ((1f - fade) * 255).toInt(); cv.drawText(com.builder.core.L.tr(prev).uppercase(), x, base - dy * fade, p)
            p.alpha = (fade * 255).toInt(); cv.drawText(com.builder.core.L.tr(text).uppercase(), x, base + dy * (1f - fade), p)
        } else { p.alpha = 255; cv.drawText(com.builder.core.L.tr(text).uppercase(), x, base, p) }
        p.alpha = 255
    }
}

/** Вкладки с плавно скользящим подчёркиванием и точкой «есть новые строки». */
class TabBar(c: Context, private val labels: List<String>, private val onSelect: (Int) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pos = 0f
    var selected = 0; private set
    private val badge = BooleanArray(labels.size)
    private var anim: ValueAnimator? = null

    init {
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = c.spf(11.5f); p.letterSpacing = 0.1f; p.textAlign = Paint.Align.CENTER
        isClickable = true
    }

    fun setBadge(i: Int, on: Boolean) { if (badge[i] != on) { badge[i] = on; invalidate() } }

    fun select(i: Int, notify: Boolean = true) {
        if (i == selected) return
        selected = i
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, i.toFloat()).apply {
            duration = 300; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }; start()
        }
        badge[i] = false
        if (notify) onSelect(i)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(44))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            select((e.x / (width.toFloat() / labels.size)).toInt().coerceIn(0, labels.size - 1))
            performClick()
        }
        return true
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val tw = w / labels.size
        p.style = Paint.Style.FILL; p.color = Pal.LINE
        cv.drawRect(0f, h - context.pxf(1f), w, h, p)
        labels.map { com.builder.core.L.tr(it) }.forEachIndexed { i, s ->
            val near = 1f - minOf(1f, Math.abs(pos - i))
            p.color = lerpColor(Pal.MUTED, Pal.TEXT, near)
            val cy = h / 2 - (p.descent() + p.ascent()) / 2
            cv.drawText(s.uppercase(), tw * i + tw / 2, cy, p)
            if (badge[i]) {
                p.color = Pal.ACCENT
                val x = tw * i + tw / 2 + p.measureText(s.uppercase()) / 2 + context.pxf(8f)
                cv.drawRect(x, h / 2 - context.pxf(3f), x + context.pxf(6f), h / 2 + context.pxf(3f), p)
            }
        }
        p.color = Pal.ACCENT
        val pad = context.pxf(24f)
        cv.drawRect(tw * pos + pad, h - context.pxf(2f), tw * pos + tw - pad, h, p)
    }
}


/** Логотип-робот (векторный), красится в цвет акцента. */
class LogoView(c: Context, private val dp: Int = 84) : View(c) {
    private val d = c.ico(R.drawable.ic_tbot, Pal.ACCENT, dp.toFloat())
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.px(dp), context.px(dp))
    override fun onDraw(cv: Canvas) { d.setBounds(0, 0, width, height); d.draw(cv) }
}

/**
 * Лента показателей во время сборки: три строгих дуги в 270° (разрыв снизу, без скруглённых концов) —
 * процессор, память системы и память процессов сборки. Высота плавно раскрывается и схлопывается.
 */
class ResourceStrip(c: Context) : View(c) {
    private class G(val label: String) { var target = 0f; var shown = 0f; var text = "" ; var detail = "" }
    private val gs = listOf(G("CPU"), G("RAM"), G("Процесс"))
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var open = 0f
    private var openTarget = 0f
    private var anim: ValueAnimator? = null
    private val full get() = context.px(104)

    private val follow = object : Runnable {
        override fun run() {
            var moving = false
            for (g in gs) { val d = g.target - g.shown; if (Math.abs(d) > 0.002f) { g.shown += d * 0.14f; moving = true } else g.shown = g.target }
            invalidate(); if (moving) postOnAnimation(this)
        }
    }

    init { visibility = View.GONE }

    fun show(on: Boolean) {
        if (on == (openTarget == 1f)) return
        openTarget = if (on) 1f else 0f
        anim?.cancel()
        if (on) visibility = View.VISIBLE
        anim = ValueAnimator.ofFloat(open, openTarget).apply {
            duration = 320; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { open = it.animatedValue as Float; requestLayout(); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (openTarget == 0f) { visibility = View.GONE } }
            })
            start()
        }
    }

    fun set(cpu: Float, ram: Float, ramText: String, proc: Float, procText: String) {
        gs[0].target = cpu / 100f; gs[0].text = "${Math.round(cpu)}%"; gs[0].detail = ""
        gs[1].target = ram; gs[1].text = "${Math.round(ram * 100)}%"; gs[1].detail = ramText
        gs[2].target = proc.coerceAtMost(1f); gs[2].text = procText; gs[2].detail = ""
        removeCallbacks(follow); postOnAnimation(follow)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (full * open).toInt())
    override fun onDetachedFromWindow() { anim?.cancel(); removeCallbacks(follow); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        if (open <= 0.01f) return
        val w = width.toFloat(); val cell = w / gs.size
        val d = context.pxf(60f); val sw = context.pxf(4f)
        val cy = context.pxf(10f) + d / 2
        cv.save(); cv.clipRect(0f, 0f, w, height.toFloat())
        val a = (255 * open).toInt().coerceIn(0, 255)
        gs.forEachIndexed { i, g ->
            val cx = cell * i + cell / 2
            oval.set(cx - d / 2 + sw / 2, cy - d / 2 + sw / 2, cx + d / 2 - sw / 2, cy + d / 2 - sw / 2)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeCap = Paint.Cap.BUTT
            p.color = Pal.LINE; p.alpha = a; cv.drawArc(oval, 135f, 270f, false, p)
            p.color = if (g.shown > 0.9f) Pal.WARN else Pal.ACCENT; p.alpha = a
            if (g.shown > 0.003f) cv.drawArc(oval, 135f, 270f * g.shown.coerceIn(0f, 1f), false, p)
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.color = Pal.TEXT; p.alpha = a
            p.textSize = context.spf(if (g.text.length > 4) 12f else 15f)
            cv.drawText(g.text, cx, cy - (p.descent() + p.ascent()) / 2 - context.pxf(2f), p)
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.textSize = context.spf(9f); p.letterSpacing = 0.12f
            p.color = Pal.MUTED; p.alpha = a
            cv.drawText(com.builder.core.L.tr(g.label).uppercase(), cx, cy + d / 2 + context.pxf(3f), p)
            p.letterSpacing = 0f
            if (g.detail.isNotEmpty()) {
                p.typeface = Typeface.MONOSPACE; p.textSize = context.spf(10.5f); p.color = Pal.SOFT; p.alpha = a
                cv.drawText(com.builder.core.L.tr(g.detail), cx, cy + d / 2 + context.pxf(19f), p)
            }
        }
        cv.restore(); p.alpha = 255
    }
}
