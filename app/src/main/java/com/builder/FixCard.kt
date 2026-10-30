package com.builder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.builder.core.Downloader
import com.builder.core.Fix
import com.builder.core.FixState

/** Квадратный чекбокс: галочка прорисовывается, при загрузке по периметру бежит огонёк, затем зелёная заливка. */
class FixCheck(c: Context) : View(c) {
    enum class Mode { OFF, ON, RUN, DONE, FAIL }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Path(); private val cross = Path(); private val seg = Path(); private val ring = Path()
    private var mode = Mode.ON
    private var on = 1f
    private var done = 0f
    private var phase = 0f
    private var a1: ValueAnimator? = null
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.px(22), context.px(22))

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = w.toFloat(); val i = s * .12f
        ring.reset(); ring.addRect(1.5f, 1.5f, s - 1.5f, s - 1.5f, Path.Direction.CW)
        tick.reset(); tick.moveTo(s * .24f, s * .52f); tick.lineTo(s * .44f, s * .72f); tick.lineTo(s * .78f, s * .30f)
        cross.reset(); cross.moveTo(s * .30f, s * .30f); cross.lineTo(s * .70f, s * .70f); cross.moveTo(s * .70f, s * .30f); cross.lineTo(s * .30f, s * .70f)
    }

    fun set(m: Mode, animate: Boolean) {
        if (m == mode) return
        val old = mode; mode = m
        spin.cancel(); if (m == Mode.RUN) spin.start()
        a1?.cancel()
        val (fromOn, toOn) = when (m) { Mode.ON -> on to 1f; Mode.OFF -> on to 0f; Mode.RUN -> 1f to 1f; else -> 1f to 1f }
        val toDone = if (m == Mode.DONE || m == Mode.FAIL) 1f else 0f
        if (!animate) { on = toOn; done = toDone; invalidate(); return }
        val fromDone = if (old == Mode.DONE || old == Mode.FAIL) done else 0f
        a1 = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { val t = it.animatedValue as Float; on = fromOn + (toOn - fromOn) * t; done = fromDone + (toDone - fromDone) * t; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { spin.cancel(); a1?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val s = width.toFloat()
        p.style = Paint.Style.STROKE; p.strokeWidth = context.pxf(1.5f); p.strokeCap = Paint.Cap.SQUARE
        when (mode) {
            Mode.OFF, Mode.ON -> {
                p.color = lerpColor(Pal.MUTED, Pal.WARN, on); cv.drawPath(ring, p)
                if (on > 0f) {
                    p.style = Paint.Style.FILL; p.color = Pal.WARN; p.alpha = (255 * on).toInt()
                    cv.drawRect(0f, 0f, s, s * on, p); p.alpha = 255
                    val pm = PathMeasure(tick, false); seg.reset(); pm.getSegment(0f, pm.length * on, seg, true)
                    p.style = Paint.Style.STROKE; p.strokeWidth = context.pxf(2f); p.color = Pal.BG; cv.drawPath(seg, p)
                }
            }
            Mode.RUN -> {
                p.color = Pal.LINE; cv.drawPath(ring, p)
                val pm = PathMeasure(ring, false); val len = pm.length
                p.color = Pal.ACCENT; p.strokeWidth = context.pxf(2f)
                seg.reset(); val st = phase * len; val en = st + len * .28f
                if (en <= len) pm.getSegment(st, en, seg, true) else { pm.getSegment(st, len, seg, true); pm.getSegment(0f, en - len, seg, true) }
                cv.drawPath(seg, p)
            }
            Mode.DONE -> {
                p.style = Paint.Style.FILL; p.color = Pal.OK; p.alpha = (255 * done).toInt(); cv.drawRect(0f, 0f, s, s, p); p.alpha = 255
                val pm = PathMeasure(tick, false); seg.reset(); pm.getSegment(0f, pm.length * done, seg, true)
                p.style = Paint.Style.STROKE; p.strokeWidth = context.pxf(2f); p.color = Pal.BG; cv.drawPath(seg, p)
            }
            Mode.FAIL -> {
                p.color = Pal.ERROR; cv.drawPath(ring, p)
                val pm = PathMeasure(cross, false); seg.reset(); pm.getSegment(0f, pm.length * done, seg, true)
                cv.drawPath(seg, p)
            }
        }
    }
}

/** Строка «что докачать»: чекбокс, название, причина, размер и (во время загрузки) полоса с живой подписью. */
class FixRow(ctx: Context, val fix: Fix, delay: Long, private val onToggle: () -> Unit) : LinearLayout(ctx) {
    val box = FixCheck(ctx)
    private val title = TTextView(ctx)
    private val why = TTextView(ctx)
    private val info = TTextView(ctx)
    private val size = TTextView(ctx)
    private val bar = LineBar(ctx)
    var checked = true; private set
    private var lastState: FixState? = null
    private var sizeShown = false

    init {
        orientation = HORIZONTAL; setPadding(0, ctx.px(13), 0, ctx.px(13))
        addView(box, LayoutParams(ctx.px(22), ctx.px(22)).apply { topMargin = ctx.px(1); marginEnd = ctx.px(14) })
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        title.apply { text = fix.title; textSize = 15f; setTextColor(Pal.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        why.apply { text = fix.why; textSize = 12f; setTextColor(Pal.SOFT); setLineSpacing(0f, 1.1f) }
        info.apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Pal.SOFT); visibility = View.GONE }
        bar.visibility = View.GONE
        col.addView(title); col.addView(why, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(3) })
        col.addView(info, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(6) })
        col.addView(bar, LayoutParams(MATCH_PARENT, ctx.px(2)).apply { topMargin = ctx.px(8) })
        addView(col, LayoutParams(0, WRAP_CONTENT, 1f))
        size.apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Pal.MUTED); gravity = Gravity.END; text = "…" }
        addView(size, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(10); topMargin = ctx.px(2) })

        isClickable = true
        setOnClickListener { if (lastState == FixState.OFFERED || lastState == FixState.ERROR) { checked = !checked; box.set(if (checked) FixCheck.Mode.ON else FixCheck.Mode.OFF, true); title.setTextColor(if (checked) Pal.TEXT else Pal.MUTED); onToggle() } }
        alpha = 0f; translationY = ctx.pxf(14f)
        animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(.985f).scaleY(.985f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
        return super.onTouchEvent(e)
    }

    fun update() {
        val f = fix
        // размер: появляется плавно, когда сервер ответил
        if (f.bytes > 0 && !sizeShown) {
            sizeShown = true; size.text = Downloader.human(f.bytes)
            size.alpha = 0f; size.animate().alpha(1f).setDuration(300).start()
        } else if (f.bytes <= 0 && !sizeShown && f.state == FixState.OFFERED) size.text = "…"
        val st = f.state
        if (st != lastState) {
            val animate = lastState != null
            when (st) {
                FixState.OFFERED -> { box.set(if (checked) FixCheck.Mode.ON else FixCheck.Mode.OFF, animate); fade(why, true); fade(info, false); bar.reset(); bar.visibility = View.GONE }
                FixState.RUNNING -> { box.set(FixCheck.Mode.RUN, animate); title.setTextColor(Pal.TEXT); fade(info, true); info.setTextColor(Pal.SOFT); bar.visibility = View.VISIBLE; bar.indeterminate() }
                FixState.DONE -> { box.set(FixCheck.Mode.DONE, animate); bar.set(1f); bar.animate().alpha(0f).setStartDelay(250).setDuration(300).withEndAction { bar.visibility = View.GONE; bar.alpha = 1f }.start(); info.setTextColor(Pal.OK) }
                FixState.ERROR -> { box.set(FixCheck.Mode.FAIL, animate); bar.reset(); bar.visibility = View.GONE; info.setTextColor(Pal.ERROR); fade(info, true) }
            }
            lastState = st
        }
        when (st) {
            FixState.RUNNING -> { info.text = f.detail; if (f.progress < 0f) bar.indeterminate() else bar.progress(f.progress) }
            FixState.DONE -> info.text = "готово"
            FixState.ERROR -> info.text = f.error ?: "ошибка"
            else -> {}
        }
    }

    private fun fade(v: View, show: Boolean) {
        if (show && v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; v.alpha = 0f; v.animate().alpha(1f).setDuration(260).start() }
        else if (!show && v.visibility == View.VISIBLE) v.animate().alpha(0f).setDuration(160).withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start()
    }

    /** Мгновенно, без анимации — при возврате в приложение. */
    fun settle() { animate().cancel(); alpha = 1f; translationY = 0f }
}

/**
 * «Помощник»: после ошибки сборки приложение решает, что докачать, и спрашивает.
 * Рамка прорисовывается жёлтым, строки выезжают по очереди, во время загрузки у каждой — своя полоса.
 */
class FixCard(ctx: Context, private val onGo: (List<Fix>) -> Unit, private val onSkip: () -> Unit, private val onCancel: () -> Unit) : LinearLayout(ctx) {
    private val label = TTextView(ctx)
    private val head = TTextView(ctx)
    private val total = TTextView(ctx)
    private val rows = LinearLayout(ctx)
    private val go = ActionButton(ctx)
    private val skip = ActionButton(ctx)
    private var shownIds: List<String> = emptyList()
    private val rowViews = ArrayList<FixRow>()
    private var current: List<Fix> = emptyList()
    private var busy = false
    private var hiding = false

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.pxf(1.5f) }
    private val rect = Path(); private val seg = Path()
    private var draw = 0f
    private var anim: ValueAnimator? = null

    init {
        orientation = VERTICAL; setWillNotDraw(false); setBackgroundColor(Pal.SURFACE)
        setPadding(ctx.px(18), ctx.px(18), ctx.px(18), ctx.px(18)); visibility = View.GONE
        label.apply { text = "ПОМОЩНИК"; textSize = 11f; letterSpacing = 0.2f; setTextColor(Pal.WARN); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        head.apply { textSize = 19f; setTextColor(Pal.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        total.apply { textSize = 12f; setTextColor(Pal.MUTED); typeface = Typeface.MONOSPACE }
        rows.orientation = VERTICAL
        addView(label)
        addView(head, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8) })
        addView(total, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(4) })
        addView(rows, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(10) })
        go.apply { this.label = "Скачать и собрать снова"; setOnClickListener { click() } }
        skip.apply { this.label = "Не сейчас"; ghost = true; heightDp = 46; setOnClickListener { if (busy) onCancel() else onSkip() } }
        addView(go, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(16) })
        addView(skip, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(10) })
    }

    private fun selected(): List<Fix> = rowViews.filter { it.checked && it.fix.state != FixState.DONE }.map { it.fix }

    private fun click() {
        if (busy) return
        val sel = selected(); if (sel.isEmpty()) return
        onGo(sel)
    }

    fun bind(fixes: List<Fix>, fixing: Boolean) {
        current = fixes; busy = fixing
        if (fixes.isEmpty()) { hide(); return }
        if (hiding) { animate().cancel(); alpha = 1f; translationY = 0f; hiding = false }
        val ids = fixes.map { it.id }
        val fresh = visibility != View.VISIBLE
        if (ids != shownIds) rebuild(fixes)
        rowViews.forEach { it.update() }
        if (fresh) appear()
        refreshTexts()
    }

    private fun rebuild(fixes: List<Fix>) {
        rows.removeAllViews(); rowViews.clear(); shownIds = fixes.map { it.id }
        fixes.forEachIndexed { k, f ->
            val r = FixRow(context, f, 260L + 90L * k) { refreshTexts() }
            rowViews.add(r); rows.addView(r)
            if (k < fixes.lastIndex) rows.addView(View(context).apply { setBackgroundColor(Pal.LINE) }, LayoutParams(MATCH_PARENT, 1))
        }
    }

    private var lastHead = ""
    private fun swap(tv: TextView, s: String) {
        if (tv.text.toString() == s) return
        if (!isAttachedToWindow || visibility != View.VISIBLE) { tv.text = s; return }
        tv.animate().cancel(); tv.animate().alpha(0f).setDuration(90).withEndAction { tv.text = s; tv.animate().alpha(1f).setDuration(200).start() }.start()
    }

    private fun refreshTexts() {
        val fs = current
        val failed = fs.any { it.state == FixState.ERROR }
        val running = busy
        swap(head, when {
            running -> if (fs.size > 1) "Докачиваю ${fs.count { it.state == FixState.DONE } + 1} из ${fs.size}…" else "Докачиваю…"
            failed -> "Не удалось докачать"
            fs.size == 1 -> "Для сборки нужно докачать файл"
            else -> "Для сборки нужно докачать ${fs.size} " + if (fs.size in 2..4) "файла" else "файлов"
        })
        val sel = if (running) fs else selected()
        val known = sel.filter { it.bytes > 0 }
        val sum = known.sumOf { it.bytes }
        swap(total, when {
            running -> "не закрывай приложение — сборка начнётся сама"
            sel.isEmpty() -> "ничего не выбрано"
            known.isEmpty() -> "размер уточняется…"
            else -> "≈ " + Downloader.human(sum) + if (known.size < sel.size) " и ещё что-то небольшое" else " · пересоберу сразу после загрузки"
        })
        val has = selected().isNotEmpty()
        go.shimmer = running
        go.ghost = !running && !has
        go.label = when { running -> "Скачиваю…"; failed -> "Повторить"; has -> "Скачать и собрать снова"; else -> "Выбери, что скачать" }
        skip.label = if (running) "Отмена" else "Не сейчас"
        if (running) {
            val avg = fs.map { when (it.state) { FixState.DONE -> 1f; FixState.RUNNING -> if (it.progress >= 0f) it.progress else 0.15f; else -> 0f } }.average().toFloat()
            go.setFill(avg)
        } else go.setFill(if (has) 1f else 0f)
    }

    private fun appear() {
        anim?.cancel(); animate().cancel()
        visibility = View.VISIBLE; alpha = 0f; translationY = context.pxf(28f); draw = 0f
        animate().alpha(1f).translationY(0f).setDuration(460).setInterpolator(DecelerateInterpolator(2.2f)).start()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; startDelay = 120; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { draw = it.animatedValue as Float; invalidate() }; start()
        }
    }

    fun hide() {
        if (visibility != View.VISIBLE || hiding) return
        hiding = true
        animate().cancel(); animate().alpha(0f).translationY(context.pxf(10f)).setDuration(280)
            .withEndAction { if (hiding) { visibility = View.GONE; shownIds = emptyList(); rows.removeAllViews(); rowViews.clear(); hiding = false } }.start()
    }

    fun hideNow() { anim?.cancel(); animate().cancel(); visibility = View.GONE; hiding = false; shownIds = emptyList(); rows.removeAllViews(); rowViews.clear() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val hf = border.strokeWidth / 2
        rect.reset(); rect.addRect(hf, hf, w - hf, h - hf, Path.Direction.CW)
    }

    override fun dispatchDraw(cv: Canvas) {
        super.dispatchDraw(cv)
        border.color = Pal.LINE; cv.drawPath(rect, border)
        if (draw > 0f) {
            val pm = PathMeasure(rect, false); seg.reset(); pm.getSegment(0f, pm.length * draw, seg, true)
            border.color = Pal.WARN; cv.drawPath(seg, border)
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
}
