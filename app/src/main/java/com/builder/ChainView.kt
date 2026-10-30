package com.builder

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.builder.core.Engine
import com.builder.core.Group
import com.builder.core.St
import com.builder.core.Stage

/**
 * Вертикальная «рейка» шага: узел-квадрат и соединители сверху и снизу.
 * Соединитель заполняется синим сверху вниз, под работающим узлом бегут «пакеты»,
 * готовый узел зажигается и дорисовывает галочку, ошибка трясёт узел.
 */
class RailView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.SQUARE }
    var state = St.IDLE; private set
    var first = false
    var last = false

    private var topLit = 0f
    private var botFill = 0f
    private var tick = 0f
    private var check = 0f
    private var shakeX = 0f
    private var flash = 0f
    private var crossT = 0f      // 0..1 — насколько нарисован крестик (отмена/ошибка)
    private var fillT = 1f       // заливка готового узла (при откате гаснет)
    private var rolling = false  // идёт откат — без вспышек
    private var rollTok = 0
    private var intro = 1f
    private var topMix = 0f      // 0 — верхний соединитель синий, 1 — красный (ошибка)
    private var trackA = 1f      // прозрачность тёмной «дорожки» под узлом; при ошибке затухает
    private var botMix = 0f      // нижний соединитель перетекает в цвет следующего узла
    private var botTo = Pal.ACCENT
    private var errAnim: ValueAnimator? = null
    private var mixAnim: ValueAnimator? = null

    private val sw = c.pxf(1.5f)
    private val lw = c.pxf(2f)
    private val node = c.pxf(14f)
    private val nodeY = c.pxf(22f)
    private val tickPath = Path()
    private val crossPath = Path()
    private val seg = Path()
    private val dash = DashPathEffect(floatArrayOf(c.pxf(3f), c.pxf(3f)), 0f)
    private val running = HashMap<String, ValueAnimator>()

    private val tickAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { tick = it.animatedValue as Float; invalidate() }
    }

    /**
     * Каждое свойство рисуется своим аниматором (по ключу): смена состояния не обрывает чужие анимации.
     * Если аниматор всё же отменили, он доводит значение до конечного — ничего не застревает на полпути.
     */
    private fun anim(key: String, from: Float, to: Float, ms: Long, delay: Long = 0, interp: android.animation.TimeInterpolator = DecelerateInterpolator(1.6f), upd: (Float) -> Unit) {
        running.remove(key)?.let { old -> old.removeAllListeners(); old.cancel() }
        upd(from)
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = ms; startDelay = delay; interpolator = interp
            addUpdateListener { upd(it.animatedValue as Float); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled) { upd(to); invalidate() }
                    if (running[key] === a) running.remove(key)
                }
            })
        }
        running[key] = a; a.start()
    }

    /** Останавливает анимации перечисленных свойств; значения доводятся до конечных. */
    private fun stop(vararg keys: String) { for (k in keys) running.remove(k)?.cancel() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (state == St.RUNNING && !tickAnim.isRunning) tickAnim.start()
    }

    /** Достроить узел сразу: строка показана без анимации появления. */
    fun skipIntro() { stop("intro"); intro = 1f; invalidate() }

    fun setState(s: St, animate: Boolean) {
        if (s == state) return
        state = s
        rollTok++; rolling = false
        stop("check", "cross", "shake", "flash", "roll")
        fillT = 1f
        tickAnim.cancel()
        if (s == St.RUNNING) tickAnim.start()
        check = 0f
        errAnim?.cancel()
        if (s == St.ERROR && animate) {
            errAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900; interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { val u = it.animatedValue as Float; topMix = u; trackA = 1f - u; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationCancel(a: android.animation.Animator) { topMix = 1f; trackA = 0f }
                })
                start()
            }
        } else if (s == St.ERROR) { topMix = 1f; trackA = 0f }
        else { topMix = 0f; trackA = 1f }
        if (s == St.DONE) { if (animate) anim("check", 0f, 1f, 520, 90, DecelerateInterpolator(1.3f)) { check = it } else check = 1f }
        crossT = if (s == St.ERROR) 1f else 0f
        if (s == St.CANCELLED) { if (animate) anim("cross", 0f, 1f, 460, 60, DecelerateInterpolator(1.4f)) { crossT = it } else crossT = 1f }
        if (s == St.ERROR && animate) shake()
        invalidate()
    }

    /**
     * Откат: всё, что нарисовано, плавно стирается в обратном порядке — галочка или крестик «убираются» справа налево,
     * заливка гаснет, соединители втягиваются. По окончании узел снова пустой.
     */
    fun rollback(end: () -> Unit) {
        if (state == St.IDLE) { end(); return }
        val tok = ++rollTok
        stop("check", "cross", "shake", "flash", "top", "bot", "roll")
        tickAnim.cancel(); errAnim?.cancel(); mixAnim?.cancel()
        shakeX = 0f; flash = 0f
        rolling = true
        val c0 = check; val x0 = crossT; val t0 = topLit; val b0 = botFill; val m0 = topMix; val bm0 = botMix
        val ease = android.view.animation.AccelerateDecelerateInterpolator()
        anim("roll", 0f, 1f, 360, 0, ease) { k ->
            check = c0 * (1f - k); crossT = x0 * (1f - k)
            fillT = (1f - (k * 1.6f - 0.4f).coerceIn(0f, 1f))
            botFill = b0 * (1f - k); topLit = t0 * (1f - minOf(1f, k * 1.3f))
            topMix = m0 * (1f - k); botMix = bm0 * (1f - k); trackA = 1f - (1f - trackA) * (1f - k)
        }
        postDelayed({
            if (tok != rollTok) return@postDelayed
            state = St.IDLE; rolling = false; check = 0f; crossT = 0f; fillT = 1f; topLit = 0f; botFill = 0f; topMix = 0f; botMix = 0f; trackA = 1f
            running.clear(); invalidate(); end()
        }, 400)
    }

    /** Следующий узел стал красным/синим: нижний соединитель плавно перетекает в его цвет. */
    fun setNext(error: Boolean, animate: Boolean) {
        val to = if (error) 1f else 0f
        botTo = Pal.ERROR
        mixAnim?.cancel()
        if (!animate) { botMix = to; invalidate(); return }
        mixAnim = ValueAnimator.ofFloat(botMix, to).apply {
            duration = 800; interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { botMix = it.animatedValue as Float; invalidate() }; start()
        }
    }

    fun setTop(lit: Boolean, animate: Boolean) {
        val to = if (lit) 1f else 0f
        if (to == topLit && running["top"] == null) return
        if (animate) anim("top", topLit, to, 240) { topLit = it } else { stop("top"); topLit = to; invalidate() }
    }

    fun setBottom(full: Boolean, animate: Boolean) {
        val to = if (full) 1f else 0f
        if (to == botFill && running["bot"] == null) return
        if (animate) anim("bot", botFill, to, 360) { botFill = it } else { stop("bot"); botFill = to; invalidate() }
    }

    /** Узел «рисуется» по периметру, соединитель сверху дорисовывается следом. */
    fun drawIn(delay: Long = 0) { anim("intro", 0f, 1f, 520, delay, DecelerateInterpolator(1.4f)) { intro = it } }

    fun shake() {
        anim("shake", 0f, 1f, 600, 0, LinearInterpolator()) { t -> shakeX = (Math.sin(t * Math.PI * 4) * (1 - t) * context.pxf(2.5f)).toFloat() }
    }

    fun flash() {
        anim("flash", 0f, 1f, 740, 0, LinearInterpolator()) { t -> flash = if (t < 0.3f) t / 0.3f else 1f - (t - 0.3f) / 0.7f }
    }

    private fun drawPartial(cv: Canvas, path: Path, t: Float) {
        if (t <= 0f) return
        val pm = PathMeasure(path, false); var k = 0
        do {
            val local = (t * 2f - k).coerceIn(0f, 1f)
            if (local > 0f) { seg.reset(); pm.getSegment(0f, pm.length * local, seg, true); cv.drawPath(seg, p) }
            k++
        } while (pm.nextContour())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cx = w / 2f; val hf = node / 2
        tickPath.reset()
        tickPath.moveTo(cx - hf * .5f, nodeY + hf * .02f); tickPath.lineTo(cx - hf * .12f, nodeY + hf * .42f); tickPath.lineTo(cx + hf * .55f, nodeY - hf * .4f)
        crossPath.reset()
        crossPath.moveTo(cx - hf * .45f, nodeY - hf * .45f); crossPath.lineTo(cx + hf * .45f, nodeY + hf * .45f)
        crossPath.moveTo(cx + hf * .45f, nodeY - hf * .45f); crossPath.lineTo(cx - hf * .45f, nodeY + hf * .45f)
    }

    override fun onDetachedFromWindow() {
        tickAnim.cancel(); errAnim?.cancel(); mixAnim?.cancel()
        running.values.toList().forEach { it.cancel() }; running.clear()
        super.onDetachedFromWindow()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val hf = node / 2
        val top = nodeY - hf; val bot = nodeY + hf
        p.pathEffect = null; p.style = Paint.Style.STROKE; p.strokeWidth = lw; p.alpha = 255

        if (!first) {
            p.color = Pal.LINE; cv.drawLine(cx, 0f, cx, top, p)
            if (topLit > 0f) { p.color = lerpColor(Pal.ACCENT, Pal.ERROR, topMix); cv.drawLine(cx, 0f, cx, top * topLit, p) }
        }
        if (!last) {
            val len = h - bot
            p.color = Pal.LINE; p.alpha = (255 * trackA).toInt(); cv.drawLine(cx, bot, cx, h, p); p.alpha = 255
            if (botFill > 0f) {
                if (botMix > 0f) {
                    // градиент от синего к цвету следующего узла: соединитель «перетекает» в него
                    p.shader = LinearGradient(cx, bot, cx, h, Pal.ACCENT, lerpColor(Pal.ACCENT, botTo, botMix), Shader.TileMode.CLAMP)
                } else p.color = Pal.ACCENT
                cv.drawLine(cx, bot, cx, bot + len * botFill, p); p.shader = null
            }
            if (state == St.RUNNING) {
                p.style = Paint.Style.FILL; p.color = Pal.GLOW
                for (k in 0 until 3) {
                    val t = (tick + k / 3f) % 1f
                    p.alpha = (Math.sin(t * Math.PI) * 255).toInt()
                    cv.drawRect(cx - lw, bot + t * len - lw * 1.5f, cx + lw, bot + t * len + lw * 1.5f, p)
                }
                p.alpha = 255
            }
        }

        val nx = cx + shakeX
        val l = nx - hf; val r = nx + hf; val t = nodeY - hf; val b = nodeY + hf
        p.strokeWidth = sw
        if (intro < 1f) {
            // пока контур рисуется — только он (цвет зависит от состояния)
            val pth = Path(); pth.addRect(l + sw / 2, t + sw / 2, r - sw / 2, b - sw / 2, Path.Direction.CW)
            val pm = PathMeasure(pth, false)
            seg.reset(); pm.getSegment(0f, pm.length * intro, seg, true)
            p.style = Paint.Style.STROKE; p.color = if (state == St.ERROR) Pal.ERROR else Pal.ACCENT
            cv.drawPath(seg, p)
            return
        }
        when (state) {
            St.IDLE -> { p.style = Paint.Style.STROKE; p.color = Pal.MUTED; cv.drawRect(l + sw / 2, t + sw / 2, r - sw / 2, b - sw / 2, p) }
            St.RUNNING -> {
                val wave = ((Math.sin(tick * Math.PI * 2) + 1) / 2).toFloat()
                p.style = Paint.Style.STROKE; p.color = Pal.ACCENT; cv.drawRect(l + sw / 2, t + sw / 2, r - sw / 2, b - sw / 2, p)
                p.style = Paint.Style.FILL; p.alpha = ((0.25f + 0.65f * wave) * 255).toInt()
                val i = node * .28f; cv.drawRect(l + i, t + i, r - i, b - i, p)
                // расходящееся квадратное кольцо
                val rs = hf + tick * context.pxf(11f)
                p.style = Paint.Style.STROKE; p.alpha = ((1f - tick) * 140).toInt()
                cv.drawRect(nx - rs, nodeY - rs, nx + rs, nodeY + rs, p); p.alpha = 255
            }
            St.DONE -> {
                p.style = Paint.Style.FILL; p.color = Pal.ACCENT; p.alpha = (255 * fillT).toInt(); cv.drawRect(l, t, r, b, p); p.alpha = 255
                if (fillT < 1f) { p.style = Paint.Style.STROKE; p.strokeWidth = sw; cv.drawRect(l + sw / 2, t + sw / 2, r - sw / 2, b - sw / 2, p) }
                if (check < 1f && !rolling) { // вспышка-кольцо при завершении
                    val rs = hf + check * context.pxf(9f)
                    p.style = Paint.Style.STROKE; p.color = Pal.GLOW; p.alpha = ((1f - check) * 200).toInt()
                    cv.drawRect(nx - rs, nodeY - rs, nx + rs, nodeY + rs, p); p.alpha = 255
                }
                if (check > 0f) {
                    val pm = PathMeasure(tickPath, false)
                    seg.reset(); pm.getSegment(0f, pm.length * minOf(1f, check * 1.4f), seg, true)
                    p.style = Paint.Style.STROKE; p.color = if (fillT > 0.5f) Pal.ON_ACCENT else Pal.ACCENT; p.strokeWidth = context.pxf(2f)
                    cv.save(); cv.translate(shakeX, 0f); cv.drawPath(seg, p); cv.restore()
                }
            }
            St.ERROR -> {
                p.style = Paint.Style.STROKE; p.color = Pal.ERROR; cv.drawRect(l + sw / 2, t + sw / 2, r - sw / 2, b - sw / 2, p)
                cv.save(); cv.translate(shakeX, 0f); drawPartial(cv, crossPath, crossT); cv.restore()
            }
            St.CANCELLED -> {
                p.style = Paint.Style.STROKE; p.color = Pal.ACCENT; cv.drawRect(l + sw / 2, t + sw / 2, r - sw / 2, b - sw / 2, p)
                p.strokeWidth = context.pxf(1.8f); drawPartial(cv, crossPath, crossT)
            }
            St.SKIPPED -> {
                p.style = Paint.Style.STROKE; p.color = Pal.MUTED; p.pathEffect = dash
                cv.drawRect(l + sw / 2, t + sw / 2, r - sw / 2, b - sw / 2, p); p.pathEffect = null
                cv.drawLine(nx - hf * .4f, nodeY, nx + hf * .4f, nodeY, p)
            }
        }
        if (flash > 0f) {
            p.style = Paint.Style.FILL; p.color = Pal.GLOW; p.alpha = (flash * 150).toInt()
            cv.drawRect(cx - lw * 2, 0f, cx + lw * 2, h, p)
            p.alpha = (flash * 255).toInt(); cv.drawRect(nx - hf, nodeY - hf, nx + hf, nodeY + hf, p); p.alpha = 255
        }
    }
}

/** Строка цепочки: рейка + название, живая строка вывода, линия прогресса, время и ошибка. */
class StageRow(ctx: Context, val stage: Stage, first: Boolean, last: Boolean, private val onOpenLog: () -> Unit) : LinearLayout(ctx) {
    val rail = RailView(ctx).also { it.first = first; it.last = last }
    private val title = TTextView(ctx)
    private val time = TTextView(ctx)
    private val sub = TTextView(ctx)
    private val bar = LineBar(ctx)
    private val err = TTextView(ctx)
    private val tip = TTextView(ctx)
    private val link = LinearLayout(ctx)
    private var shown: St? = null
    val shownState: St get() = shown ?: St.IDLE
    private var titleAnim: ValueAnimator? = null
    private var bgAnim: ValueAnimator? = null
    private var lastSubAt = 0L
    private var subText = ""
    private var transGen = 0
    private var swapTok = 0
    private val fadeTok = HashMap<View, Int>()

    init {
        orientation = HORIZONTAL
        addView(rail, LayoutParams(ctx.px(28), MATCH_PARENT))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(0, ctx.px(13), ctx.px(4), ctx.px(14)) }

        title.apply { text = stage.title; textSize = 15f; setTextColor(Pal.MUTED); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        time.apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Pal.MUTED); gravity = Gravity.END }
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(title, LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(time, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8) })
        col.addView(head)

        sub.apply { textSize = 12f; setTextColor(Pal.MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.MONOSPACE; text = stage.hint }
        col.addView(sub, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(3) })

        bar.visibility = View.INVISIBLE // место под полосу занято всегда — высота строки не меняется
        col.addView(bar, LayoutParams(MATCH_PARENT, ctx.px(2)).apply { topMargin = ctx.px(9) })

        err.apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Pal.ERROR); visibility = View.GONE; setLineSpacing(0f, 1.1f) }
        col.addView(err, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8) })
        tip.apply { textSize = 12f; setTextColor(Pal.WARN); visibility = View.GONE }
        col.addView(tip, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(6) })
        val openLog = TTextView(ctx).apply {
            text = "ОТКРЫТЬ ЖУРНАЛ"; textSize = 11f; letterSpacing = 0.14f; setTextColor(Pal.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, ctx.px(10), ctx.px(20), ctx.px(4)); setOnClickListener { onOpenLog() }
            iconEnd(ctx.ico(R.drawable.ic_arrow_right, Pal.ACCENT, 14f), 6)
        }
        val copy = TTextView(ctx).apply {
            text = "КОПИРОВАТЬ"; textSize = 11f; letterSpacing = 0.14f; setTextColor(Pal.SOFT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, ctx.px(10), ctx.px(4), ctx.px(4)); setOnClickListener { copyError() }
        }
        link.apply { orientation = HORIZONTAL; visibility = View.GONE; addView(openLog); addView(copy) }
        col.addView(link, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        err.setOnLongClickListener { copyError(); true }
        // ошибка/подсказка раскрываются и сворачиваются плавно, строки ниже «едут», а не прыгают
        col.layoutTransition = LayoutTransition().apply {
            setDuration(260); enableTransitionType(LayoutTransition.CHANGING)
            disableTransitionType(LayoutTransition.APPEARING); disableTransitionType(LayoutTransition.DISAPPEARING)
            disableTransitionType(LayoutTransition.CHANGE_APPEARING); disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
        }
        addView(col, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ctx.px(8) })
    }

    private var typeAnim: ValueAnimator? = null

    /** Копирует в буфер обмена название шага, ошибку и подсказку. */
    private fun copyError() {
        val s = stage
        val text = buildString {
            append(com.builder.core.L.tr(s.title)); append('\n'); append(s.error ?: "")
            if (!s.tip.isNullOrEmpty()) { append("\n→ "); append(s.tip) }
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
        android.widget.Toast.makeText(context, com.builder.core.L.tr("Ошибка скопирована"), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Появление строки: соскальзывает, узел рисуется, название «печатается». */
    fun playIn() {
        animate().cancel()
        alpha = 0f; translationY = context.pxf(14f)
        animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(340).setInterpolator(DecelerateInterpolator(2f)).start()
        rail.drawIn()
        val full = com.builder.core.L.tr(stage.title)
        typeAnim?.cancel()
        title.text = ""
        typeAnim = ValueAnimator.ofInt(0, full.length).apply {
            duration = (full.length * 22L).coerceIn(200L, 700L); startDelay = 120; interpolator = LinearInterpolator()
            addUpdateListener { title.text = typed(full, it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { title.text = full }
                override fun onAnimationEnd(a: android.animation.Animator) { title.text = full }
            })
            start()
        }
    }

    /** Непропечатанный хвост рисуется прозрачным, но остаётся в тексте: переносы и высота строки не меняются. */
    private fun typed(full: String, n: Int): CharSequence {
        if (n >= full.length) return full
        return android.text.SpannableString(full).apply {
            setSpan(android.text.style.ForegroundColorSpan(Color.TRANSPARENT), n, full.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun hideBar() { fadeTok[bar] = (fadeTok[bar] ?: 0) + 1; bar.animate().cancel(); bar.alpha = 1f; bar.visibility = View.INVISIBLE; bar.reset() }

    fun showNow() { animate().cancel(); typeAnim?.cancel(); alpha = 1f; translationY = 0f; title.text = stage.title; rail.skipIntro() }

    fun bind(animate: Boolean) {
        val st = stage.state
        if (st != shown) transition(st, animate) else if (st == St.RUNNING) live()
        updateTime()
    }

    fun updateTime() {
        val s = stage
        time.text = when (s.state) {
            St.RUNNING -> {
                val el = System.currentTimeMillis() - s.startedAt
                val p = s.shownProgress()
                // процент и «сколько осталось»: оценка по прошлым сборкам, если шаг сам прогресс не сообщает
                if (p >= 0f && s.expectedMs > 0L && s.progress < 0f) "${(p * 100).toInt()}% · " + Engine.fmtMs(el) + " / ≈" + Engine.fmtMs(s.expectedMs)
                else if (p >= 0f) "${(p * 100).toInt()}% · " + Engine.fmtMs(el)
                else Engine.fmtMs(el)
            }
            St.DONE, St.ERROR -> Engine.fmtMs(s.elapsed)
            else -> ""
        }
    }

    private fun live() {
        val d = stage.detail
        val now = System.currentTimeMillis()
        // живой текст (скорость, размер) обновляется на месте, без затухания: иначе при частых обновлениях строка мигает
        if (d.isNotEmpty() && d != subText && now - lastSubAt > 120) { lastSubAt = now; subText = d; sub.animate().cancel(); sub.alpha = 1f; sub.setTextColor(Pal.SOFT); sub.text = d }
        val p = stage.shownProgress()
        if (p < 0f) bar.indeterminate() else bar.progress(p)
    }

    private fun swap(tv: TextView, text: String, color: Int, animate: Boolean = true) {
        subText = text
        tv.setTextColor(color)
        val tok = ++swapTok
        tv.animate().cancel()
        if (!animate || !isAttachedToWindow) { tv.alpha = 1f; tv.text = text; return }
        tv.animate().alpha(0f).setStartDelay(0).setDuration(60).withEndAction {
            if (tok == swapTok) { tv.text = text; tv.animate().alpha(1f).setStartDelay(0).setDuration(130).start() }
        }.start()
    }

    private fun titleColor(to: Int, animate: Boolean) {
        titleAnim?.cancel()
        val from = title.currentTextColor
        if (!animate || from == to) { title.setTextColor(to); return }
        titleAnim = ValueAnimator.ofArgb(from, to).apply { duration = 260; addUpdateListener { title.setTextColor(it.animatedValue as Int) }; start() }
    }

    private fun highlight(on: Boolean, animate: Boolean) {
        bgAnim?.cancel()
        val from = (background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.TRANSPARENT
        val to = if (on) Pal.SURFACE else Color.TRANSPARENT
        if (!animate) { setBackgroundColor(to); return }
        bgAnim = ValueAnimator.ofArgb(from, to).apply { duration = 320; addUpdateListener { setBackgroundColor(it.animatedValue as Int) }; start() }
    }

    private fun bump(v: View): Int { val n = (fadeTok[v] ?: 0) + 1; fadeTok[v] = n; return n }

    /** Показ: отменяет отложенное скрытие (иначе только что показанный элемент мог бы пропасть). */
    private fun reveal(v: View, animate: Boolean) {
        bump(v); v.animate().cancel()
        val wasShown = v.visibility == View.VISIBLE && v.alpha >= 1f
        v.visibility = View.VISIBLE
        if (wasShown || !animate) { v.alpha = 1f; v.translationY = 0f; return }
        v.alpha = 0f; v.translationY = context.pxf(8f)
        v.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun hide(v: View) { bump(v); v.animate().cancel(); v.alpha = 1f; v.translationY = 0f; v.visibility = View.GONE }

    private fun fadeOut(v: View) {
        if (v.visibility != View.VISIBLE) return
        val tok = bump(v)
        v.animate().cancel()
        v.animate().alpha(0f).setStartDelay(0).setDuration(200).withEndAction { if (fadeTok[v] == tok) hide(v) }.start()
    }

    /** Плавный откат строки в «ожидание»: рейка стирается, текст возвращается к серому, ошибка и ссылки уходят. */
    fun rollback(delay: Long, done: () -> Unit) {
        if (shownState == St.IDLE) { done(); return }
        val gen = transGen
        postDelayed({
            if (transGen != gen) { done(); return@postDelayed } // строка уже ожила — откат не нужен
            titleColor(Pal.MUTED, true); swap(sub, stage.hint, Pal.MUTED, true)
            fadeOut(err); fadeOut(tip); fadeOut(link); hideBar(); highlight(false, true)
            rail.rollback { if (transGen == gen) shown = St.IDLE; done() }
        }, delay)
    }

    private fun transition(st: St, animate: Boolean) {
        shown = st; transGen++
        val s = stage
        when (st) {
            St.IDLE -> {
                rail.setState(St.IDLE, false); rail.setTop(false, false); rail.setBottom(false, false)
                titleColor(Pal.MUTED, false); swap(sub, s.hint, Pal.MUTED, false)
                hideBar(); hide(err); hide(tip); hide(link); highlight(false, false)
            }
            St.RUNNING -> {
                rail.setTop(true, animate); rail.setState(St.RUNNING, animate)
                titleColor(Pal.TEXT, animate)
                swap(sub, if (s.detail.isNotEmpty()) s.detail else s.hint, Pal.SOFT, animate)
                hide(err); hide(tip); hide(link)
                reveal(bar, animate); bar.indeterminate()
                highlight(true, animate)
            }
            St.DONE -> {
                rail.setTop(true, animate); rail.setState(St.DONE, animate); rail.setBottom(true, animate)
                titleColor(Pal.TEXT, animate)
                swap(sub, if (s.note.isNotEmpty()) s.note else s.hint, Pal.MUTED, animate)
                bar.progress(1f)
                if (bar.visibility == View.VISIBLE) {
                    val tok = bump(bar)
                    bar.animate().cancel()
                    bar.animate().alpha(0f).setStartDelay(250).setDuration(300).withEndAction { if (fadeTok[bar] == tok) hideBar() }.start()
                }
                highlight(false, animate)
            }
            St.SKIPPED -> {
                rail.setTop(true, animate); rail.setState(St.SKIPPED, animate); rail.setBottom(true, animate)
                titleColor(Pal.MUTED, animate)
                swap(sub, "пропущено · " + s.note, Pal.MUTED, animate)
                hideBar(); highlight(false, animate)
            }
            St.CANCELLED -> {
                rail.setTop(true, animate); rail.setState(St.CANCELLED, animate)
                titleColor(Pal.TEXT, animate)
                swap(sub, "отменено", Pal.MUTED, animate)
                hideBar(); hide(err); hide(tip); hide(link); highlight(false, animate)
            }
            St.ERROR -> {
                rail.setTop(true, animate); rail.setState(St.ERROR, animate)
                titleColor(Pal.ERROR, animate)
                swap(sub, s.hint, Pal.MUTED, animate)
                hideBar(); highlight(false, animate)
                err.text = s.error ?: "ошибка"; reveal(err, animate)
                if (!s.tip.isNullOrEmpty()) { tip.text = s.tip; tip.iconStart(context.ico(R.drawable.ic_corner, Pal.WARN, 14f), 6); reveal(tip, animate) }
                reveal(link, animate)
            }
        }
    }
}

/** Заголовок группы: подпись, счётчик и (для тулчейна) шеврон сворачивания. */
class GroupHeader(ctx: Context, private val name: String, private val collapsible: Boolean, onClick: () -> Unit) : LinearLayout(ctx) {
    private val left = TTextView(ctx)
    private val right = TTextView(ctx)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ctx.px(16), 0, ctx.px(12)) // высота ≈ 48dp: за заголовок удобно нажимать
        left.apply { textSize = 11f; letterSpacing = 0.2f; setTextColor(Pal.MUTED); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        right.apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Pal.MUTED) }
        addView(left, LayoutParams(0, WRAP_CONTENT, 1f)); addView(right, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        if (collapsible) setOnClickListener { onClick() }
        set(0, 0, false)
    }

    fun set(done: Int, total: Int, collapsed: Boolean) {
        left.text = name
        right.text = "$done / $total"
        if (collapsible) right.iconEnd(context.ico(if (collapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up, Pal.MUTED, 16f), 6)
        right.setTextColor(if (total > 0 && done == total) Pal.ACCENT else Pal.MUTED)
    }
}

/**
 * Вся цепочка: две группы (тулчейн и сборка). Строки не показываются заранее — каждая «рисуется»
 * в момент, когда сборка до неё доходит. Готовый тулчейн сворачивается в одну строку-заголовок.
 */
class ChainView(ctx: Context, private val onActive: (View) -> Unit, private val onOpenLog: () -> Unit) : LinearLayout(ctx) {
    private var byIndex: Array<StageRow?> = emptyArray()
    private var stages: List<Stage> = emptyList()
    private var revealed = BooleanArray(0)
    private val headers = HashMap<Group, GroupHeader>()
    private var collapsed = false
    private var userToggled = false   // пользователь сам сворачивал/разворачивал тулчейн — не переопределяем это по ходу сборки

    private fun newTransition() = LayoutTransition().apply {
        setDuration(260); enableTransitionType(LayoutTransition.CHANGING)
        disableTransitionType(LayoutTransition.APPEARING) // появление рисуем сами
    }

    init { orientation = VERTICAL; layoutTransition = newTransition() }

    private fun toolchainReady() = stages.filter { it.group == Group.TOOLCHAIN }.let { l -> l.isNotEmpty() && l.all { it.state == St.DONE } }

    fun setStages(list: List<Stage>, collapseReady: Boolean) {
        stages = list
        layoutTransition = null // при пересборке не анимируем перестановку
        removeAllViews(); headers.clear()
        byIndex = arrayOfNulls(list.size); revealed = BooleanArray(list.size)
        collapsed = collapseReady && toolchainReady()
        for (g in Group.values()) {
            val items = list.withIndex().filter { it.value.group == g }
            if (items.isEmpty()) continue
            val h = GroupHeader(context, if (g == Group.TOOLCHAIN) "ТУЛЧЕЙН" else "СБОРКА", g == Group.TOOLCHAIN) { toggle() }
            headers[g] = h; addView(h)
            items.forEachIndexed { k, iv ->
                val row = StageRow(context, iv.value, k == 0, k == items.lastIndex, onOpenLog)
                byIndex[iv.index] = row
                addView(row)
                row.bind(false)
            }
        }
        rebind()
        post { layoutTransition = newTransition() }
    }

    private fun toggle() {
        collapsed = !collapsed; userToggled = true
        stages.forEachIndexed { i, s ->
            if (s.group != Group.TOOLCHAIN) return@forEachIndexed
            if (!collapsed) { revealed[i] = true; byIndex[i]?.apply { showNow(); visibility = View.VISIBLE } }
            else byIndex[i]?.visibility = View.GONE
        }
        refreshHeaders()
    }

    private fun refreshHeaders() {
        for ((g, h) in headers) {
            val items = stages.withIndex().filter { it.value.group == g }
            val any = items.any { revealed[it.index] } || (g == Group.TOOLCHAIN && collapsed)
            if (any && h.visibility != View.VISIBLE) {
                h.visibility = View.VISIBLE; h.alpha = 0f; h.animate().alpha(1f).setDuration(300).start()
            } else if (!any) h.visibility = View.GONE
            h.set(items.count { val st = byIndex[it.index]?.shownState ?: it.value.state; st == St.DONE || st == St.SKIPPED }, items.size, collapsed)
        }
    }

    /**
     * Запуск сборки. Первый запуск — строки рисуются по одной. Повторный — уже показанные строки остаются на месте
     * (сбрасываются в «ожидание» и оживают по ходу сборки), поэтому список не схлопывается и не прыгает.
     */
    fun begin() {
        collapsed = toolchainReady(); userToggled = false
        stages.indices.forEach { i ->
            val row = byIndex[i] ?: return@forEach
            val keep = revealed[i] && row.visibility == View.VISIBLE && !(stages[i].group == Group.TOOLCHAIN && collapsed)
            row.animate().cancel()
            if (keep) { row.alpha = 1f; row.translationY = 0f; if (row.shownState == St.IDLE) row.bind(false) }
            else { revealed[i] = false; row.visibility = View.GONE }
        }
        rollbackVisible(0L)
        refreshHeaders()
    }

    /** Все показанные строки откатываются снизу вверх с шагом по времени; счётчик в заголовке убывает вместе с ними. */
    private fun rollbackVisible(startDelay: Long): Long {
        val rows = stages.indices.mapNotNull { i -> byIndex[i]?.takeIf { it.visibility == View.VISIBLE && it.shownState != St.IDLE } }
        if (rows.isEmpty()) return 0L
        val step = (360L / rows.size).coerceIn(30L, 70L)
        rows.asReversed().forEachIndexed { k, r -> r.rollback(startDelay + k * step) { refreshHeaders() } }
        return startDelay + rows.size * step + 400L
    }

    /** Откат перед повторным запуском: вызывает [then], когда всё стёрто. Если откатывать нечего — сразу. */
    fun rollbackThen(then: () -> Unit) {
        val total = rollbackVisible(0L)
        if (total == 0L) then() else postDelayed({ then() }, total)
    }

    private fun reveal(i: Int) {
        val g = stages[i].group
        // сначала все ещё не показанные строки этой группы до i (на случай пропущенных событий), по очереди
        var k = 0
        for (j in stages.indices) {
            if (j > i || stages[j].group != g || revealed[j]) continue
            revealed[j] = true
            val row = byIndex[j] ?: continue
            if (j != i) row.bind(false)
            row.visibility = View.VISIBLE; row.alpha = 0f
            if (k == 0) row.playIn() else postDelayed({ row.playIn() }, 140L * k)
            k++
        }
        refreshHeaders()
    }

    fun onStage(i: Int) {
        val row = byIndex.getOrNull(i) ?: return
        val s = stages[i]
        if (s.group == Group.TOOLCHAIN && collapsed && s.state == St.RUNNING && !userToggled) { collapsed = false }
        if (!(s.group == Group.TOOLCHAIN && collapsed)) {
            if (!revealed[i]) {
                if (s.state == St.IDLE) return // например, сброс после отмены — не рисуем заранее
                reveal(i)
            }
            row.bind(true)
            linkPrev(i, true)
            if (s.state == St.RUNNING || s.state == St.ERROR) onActive(row)
        }
        if (s.state != St.RUNNING) refreshHeaders()
    }

    /** Рейка предыдущего шага «знает», что следующий упал: её соединитель плавно перетекает в красный. */
    private fun linkPrev(i: Int, animate: Boolean) {
        val g = stages[i].group
        var j = i - 1
        while (j >= 0 && stages[j].group == g) {
            val r = byIndex[j]
            if (r != null && r.visibility == View.VISIBLE) { r.rail.setNext(stages[i].state == St.ERROR, animate); return }
            j--
        }
    }

    /** Приводит вид в соответствие состоянию движка без анимации (возврат в приложение, смена проекта). */
    fun rebind() {
        stages.forEachIndexed { i, s ->
            val row = byIndex[i] ?: return@forEachIndexed
            val hiddenByCollapse = s.group == Group.TOOLCHAIN && collapsed
            val show = !hiddenByCollapse && s.state != St.IDLE
            revealed[i] = show
            if (show) { row.showNow(); row.bind(false); row.visibility = View.VISIBLE; linkPrev(i, false) }
            else { row.visibility = View.GONE; row.rail.setNext(false, false) }
        }
        refreshHeaders()
    }

    fun tick() { for (i in stages.indices) if (stages[i].state == St.RUNNING) byIndex[i]?.updateTime() }

    /**
     * Финал: волна света пробегает по рейке сверху вниз с постоянной скоростью
     * (задержка пропорциональна положению строки). Возвращает, через сколько мс волна закончится.
     */
    fun celebrate(): Long {
        val rows = ArrayList<StageRow>()
        stages.forEachIndexed { i, s ->
            val r = byIndex[i]
            if (s.group == Group.BUILD && r != null && s.state != St.SKIPPED && revealed[i]) rows.add(r)
        }
        if (rows.isEmpty()) return 0L
        val y0 = rows.first().top.toFloat(); val span = (rows.last().top - y0).coerceAtLeast(1f)
        val total = (rows.size * 70L).coerceIn(350L, 900L)
        for (r in rows) postDelayed({ r.rail.flash() }, (total * (r.top - y0) / span).toLong())
        return total + 320L
    }
}
