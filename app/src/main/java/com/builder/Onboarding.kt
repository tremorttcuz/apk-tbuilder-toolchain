package com.builder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.builder.core.Downloader
import com.builder.core.Engine
import com.builder.core.Fix
import com.builder.core.FixState

private typealias OL = LinearLayout.LayoutParams

/** Квадратный индикатор: пустой, «в работе» (мерцает), готов (заливка и галочка), ошибка (красный крест). */
private class Mark(c: Context) : View(c) {
    enum class M { EMPTY, ON, BUSY, DONE, ERROR }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = M.EMPTY
    private var t = 0f          // 0..1 — заливка
    private var pulse = 0f
    private var anim: ValueAnimator? = null
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 800; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }
    private val tick = c.ico(R.drawable.ic_check, Pal.ON_ACCENT, 15f)
    private val cross = c.ico(R.drawable.ic_close, Pal.ERROR, 15f)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.px(22), context.px(22))

    fun set(m: M, animate: Boolean = true) {
        if (m == mode) return
        mode = m
        if (m == M.BUSY) { if (!pulseAnim.isRunning) pulseAnim.start() } else pulseAnim.cancel()
        val to = if (m == M.ON || m == M.DONE) 1f else 0f
        anim?.cancel()
        if (!animate) { t = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(t, to).apply {
            duration = 240; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); pulseAnim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val bw = context.pxf(1.5f)
        val edge = when (mode) { M.ERROR -> Pal.ERROR; M.EMPTY -> Pal.MUTED; M.DONE -> Pal.OK; else -> Pal.ACCENT }
        if (t > 0f) { p.style = Paint.Style.FILL; p.color = if (mode == M.DONE) Pal.OK else Pal.ACCENT; p.alpha = (t * 255).toInt(); cv.drawRect(0f, 0f, w, h, p); p.alpha = 255 }
        if (mode == M.BUSY) { p.style = Paint.Style.FILL; p.color = Pal.ACCENT; p.alpha = ((0.15f + 0.5f * pulse) * 255).toInt(); cv.drawRect(w * .25f, h * .25f, w * .75f, h * .75f, p); p.alpha = 255 }
        p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = edge
        cv.drawRect(bw / 2, bw / 2, w - bw / 2, h - bw / 2, p)
        val s = context.px(15); val l = ((w - s) / 2).toInt(); val tp = ((h - s) / 2).toInt()
        if (t > 0.05f && mode != M.ERROR) { tick.setAlpha((t * 255).toInt()); tick.setBounds(l, tp, l + s, tp + s); tick.draw(cv) }
        if (mode == M.ERROR) { cross.setBounds(l, tp, l + s, tp + s); cross.draw(cv) }
    }
}

/**
 * Первый запуск: тёмное «Добро пожаловать» → выбор компонентов → установка.
 * Страницы листаются наискосок: новая въезжает справа снизу, старая уходит влево вверх.
 */
class Onboarding(ctx: Context, private val engine: Engine, private val onDone: () -> Unit) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val welcome = FrameLayout(ctx)
    private val choose = FrameLayout(ctx)
    private val install = FrameLayout(ctx)
    private var page = 0
    private var moving = false
    private var closing = false

    private val comps = engine.components()
    private val picks = HashMap<String, Boolean>()
    private val chooseMarks = HashMap<String, Mark>()
    private val sizeViews = HashMap<String, TextView>()

    private lateinit var goBtn: ActionButton
    private lateinit var allBtn: TextView
    private lateinit var scroll: ScrollView

    // страница установки
    private val rowMarks = HashMap<String, Mark>()
    private val rowBars = HashMap<String, LineBar>()
    private val rowText = HashMap<String, TextView>()
    private lateinit var mainBtn: ActionButton
    private lateinit var skipBtn: ActionButton
    private var installing = false
    private var queue: List<Fix> = emptyList()

    private val ticker = object : Runnable {
        override fun run() { refreshInstall(); if (installing || engine.fixing) ui.postDelayed(this, 120) }
    }

    private fun tv(s: String, sp: Float, color: Int, face: String = "sans-serif") = TTextView(context).apply {
        text = s; textSize = sp; setTextColor(color); typeface = Typeface.create(face, Typeface.NORMAL)
    }

    init {
        setBackgroundColor(Pal.BG)
        isClickable = true // не пропускаем касания к экрану под нами
        for (pg in listOf(welcome, choose, install)) pg.setBackgroundColor(Pal.BG) // страницы непрозрачные — листаются целиком
        comps.forEach { picks[it.id] = it.state != FixState.DONE }
        buildWelcome(); buildChoose(); buildInstall()
        addView(welcome, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(choose, LayoutParams(MATCH_PARENT, MATCH_PARENT)); choose.visibility = View.GONE
        addView(install, LayoutParams(MATCH_PARENT, MATCH_PARENT)); install.visibility = View.GONE
        probeSizes()
    }

    // ------------------------------------------------------------------ 1. приветствие

    private fun buildWelcome() {
        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val kicker = tv("APK TBUILDER", 11f, Pal.MUTED, "sans-serif-medium").apply { letterSpacing = 0.3f; gravity = Gravity.CENTER }
        val title = tv("Добро пожаловать", 34f, Pal.TEXT, "sans-serif-light").apply { gravity = Gravity.CENTER }
        val rule = View(context).apply { setBackgroundColor(Pal.ACCENT) }
        center.addView(LogoView(context), OL(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = context.px(22) })
        center.addView(kicker, OL(MATCH_PARENT, WRAP_CONTENT))
        center.addView(title, OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(14) })
        center.addView(rule, OL(context.px(40), context.px(2)).apply { topMargin = context.px(20) })
        welcome.addView(center, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        val start = ActionButton(context).apply { label = "Начать"; setFill(1f, false); setOnClickListener { go(1) } }
        welcome.addView(start, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(context.px(24), 0, context.px(24), context.px(32)) })

    }

    // ------------------------------------------------------------------ 2. выбор компонентов

    private fun buildChoose() {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.px(24), context.px(48), context.px(24), context.px(24)) }
        col.addView(tv("Что установить", 26f, Pal.TEXT, "sans-serif-medium"))
        col.addView(tv("Для успешных сборок APK нужны эти компоненты. Отметь, что скачать сейчас.", 13f, Pal.MUTED).apply { setLineSpacing(0f, 1.15f) },
            OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(8) })

        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("КОМПОНЕНТЫ", 11f, Pal.MUTED, "sans-serif-medium").apply { letterSpacing = 0.2f }, OL(0, WRAP_CONTENT, 1f))
        allBtn = tv("ВЫБРАТЬ ВСЁ", 11f, Pal.ACCENT, "sans-serif-medium").apply {
            letterSpacing = 0.14f; setPadding(context.px(12), context.px(10), 0, context.px(10)); visibility = View.INVISIBLE; alpha = 0f
            setOnClickListener { comps.forEach { if (it.state != FixState.DONE) picks[it.id] = true }; syncPicks() }
        }
        head.addView(allBtn)
        col.addView(head, OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(20) })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (f in comps) list.addView(chooseRow(f), OL(MATCH_PARENT, WRAP_CONTENT))
        scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
        col.addView(scroll, OL(MATCH_PARENT, 0, 1f))

        val note = tv("Если выбрать не всё нужное, сборка может не пройти.", 11f, Pal.MUTED).apply {
            iconStart(context.ico(R.drawable.ic_warn, Pal.MUTED, 13f), 6); gravity = Gravity.CENTER_VERTICAL
        }
        col.addView(note, OL(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.px(12); bottomMargin = context.px(14) })

        goBtn = ActionButton(context).apply { label = "Установить"; setFill(1f, false); setOnClickListener { startInstall() } }
        col.addView(goBtn, OL(MATCH_PARENT, WRAP_CONTENT))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val back = ActionButton(context).apply { label = "Назад"; ghost = true; heightDp = 48; setOnClickListener { go(0) } }
        val skip = ActionButton(context).apply { label = "Пропустить"; ghost = true; heightDp = 48; setOnClickListener { finish() } }
        row.addView(back, OL(0, WRAP_CONTENT, 1f).apply { marginEnd = context.px(8) }); row.addView(skip, OL(0, WRAP_CONTENT, 1f))
        col.addView(row, OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(8) })
        choose.addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        syncPicks(false)
    }

    private fun chooseRow(f: Fix): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, context.px(14), 0, context.px(14)) }
        val mk = Mark(context); chooseMarks[f.id] = mk
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(tv(f.title, 15f, Pal.TEXT, "sans-serif-medium"))
        text.addView(tv(f.why, 12f, Pal.MUTED).apply { setLineSpacing(0f, 1.1f) }, OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(3) })
        val sz = tv(if (f.state == FixState.DONE) "установлено" else "…", 12f, if (f.state == FixState.DONE) Pal.OK else Pal.MUTED, "monospace").apply { gravity = Gravity.END }
        sizeViews[f.id] = sz
        row.addView(mk, OL(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.px(16) })
        row.addView(text, OL(0, WRAP_CONTENT, 1f)); row.addView(sz, OL(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.px(10) })
        if (f.state != FixState.DONE) row.setOnClickListener { picks[f.id] = !(picks[f.id] ?: false); syncPicks() }
        return row
    }

    private fun selected() = comps.filter { picks[it.id] == true && it.state != FixState.DONE }

    private fun syncPicks(animate: Boolean = true) {
        for (f in comps) chooseMarks[f.id]?.set(if (f.state == FixState.DONE) Mark.M.DONE else if (picks[f.id] == true) Mark.M.ON else Mark.M.EMPTY, animate)
        val n = selected().size
        val installable = comps.count { it.state != FixState.DONE }
        goBtn.label = if (n > 0) "Установить · $n" else if (installable == 0) "Продолжить" else "Ничего не выбрано"
        goBtn.animate().alpha(if (n > 0 || installable == 0) 1f else 0.35f).setDuration(200).start()
        // «Выбрать всё» нужно только когда ничего не выбрано
        val showAll = n == 0 && installable > 0
        allBtn.animate().cancel()
        if (showAll) { allBtn.visibility = View.VISIBLE; allBtn.animate().alpha(1f).setDuration(220).start() }
        else allBtn.animate().alpha(0f).setDuration(160).withEndAction { if (selected().isNotEmpty()) allBtn.visibility = View.INVISIBLE }.start()
    }

    private fun probeSizes() {
        Thread {
            for (f in comps) {
                if (f.state == FixState.DONE) continue
                val b = try { engine.probe(f) } catch (_: Exception) { -1L }
                ui.post { sizeViews[f.id]?.text = if (b > 0) Downloader.human(b) else "—" }
            }
        }.apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ 3. установка

    private fun buildInstall() {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.px(24), context.px(48), context.px(24), context.px(24)) }
        col.addView(tv("Установка", 26f, Pal.TEXT, "sans-serif-medium"))
        col.addView(tv("Скачиваю и распаковываю. Экран можно не выключать.", 13f, Pal.MUTED), OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(8); bottomMargin = context.px(20) })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (f in comps) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, context.px(14), 0, context.px(14)) }
            val mk = Mark(context); rowMarks[f.id] = mk
            val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            text.addView(tv(f.title, 15f, Pal.TEXT, "sans-serif-medium"))
            val st = tv("", 12f, Pal.MUTED, "monospace").apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            rowText[f.id] = st
            text.addView(st, OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(3) })
            val bar = LineBar(context); rowBars[f.id] = bar
            text.addView(bar, OL(MATCH_PARENT, context.px(2)).apply { topMargin = context.px(9) })
            row.addView(mk, OL(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.px(16); topMargin = context.px(1) })
            row.addView(text, OL(0, WRAP_CONTENT, 1f))
            list.addView(row, OL(MATCH_PARENT, WRAP_CONTENT))
        }
        col.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }, OL(MATCH_PARENT, 0, 1f))
        mainBtn = ActionButton(context).apply { label = "Установка…"; setFill(0f, false); setOnClickListener { onMain() } }
        skipBtn = ActionButton(context).apply { label = "Пропустить"; ghost = true; heightDp = 48; setOnClickListener { finish() } }
        col.addView(mainBtn, OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(12) })
        col.addView(skipBtn, OL(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.px(8) })
        install.addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun startInstall() {
        val sel = selected()
        if (sel.isEmpty()) {
            if (comps.none { it.state != FixState.DONE }) finish()   // всё уже стоит
            else { goBtn.animate().translationX(context.pxf(8f)).setDuration(60).withEndAction { goBtn.animate().translationX(0f).setDuration(200).setInterpolator(android.view.animation.OvershootInterpolator(4f)).start() }.start() }
            return
        }
        queue = sel
        for (f in comps) {
            val sel1 = f in sel
            rowMarks[f.id]?.set(if (f.state == FixState.DONE) Mark.M.DONE else Mark.M.EMPTY, false)
            rowText[f.id]?.text = if (f.state == FixState.DONE) "уже установлено" else if (sel1) "в очереди" else "пропущено"
            rowText[f.id]?.setTextColor(if (f.state == FixState.DONE) Pal.OK else Pal.MUTED)
        }
        go(2)
        runInstall(sel)
    }

    private fun runInstall(sel: List<Fix>) {
        installing = true
        mainBtn.label = "Установка…"; mainBtn.shimmer = true; mainBtn.setFill(0f); mainBtn.alpha = 1f
        engine.install(sel)
        ui.removeCallbacks(ticker); ui.post(ticker)
    }

    private fun refreshInstall() {
        var done = 0; var failed = false
        for (f in queue) {
            val mk = rowMarks[f.id] ?: continue; val bar = rowBars[f.id]!!; val tx = rowText[f.id]!!
            when (f.state) {
                FixState.OFFERED -> { mk.set(Mark.M.EMPTY); if (engine.fixing || installing) tx.text = "в очереди" }
                FixState.RUNNING -> {
                    mk.set(Mark.M.BUSY); tx.setTextColor(Pal.SOFT); tx.text = f.detail.ifEmpty { "подключаюсь…" }
                    if (f.progress < 0f) bar.indeterminate() else bar.progress(f.progress)
                }
                FixState.DONE -> {
                    done++; mk.set(Mark.M.DONE); tx.setTextColor(Pal.OK); tx.text = "готово"
                    bar.progress(1f)
                }
                FixState.ERROR -> {
                    failed = true; mk.set(Mark.M.ERROR); tx.setTextColor(Pal.ERROR); tx.text = f.error ?: "ошибка"
                }
            }
        }
        if (queue.isNotEmpty()) mainBtn.setFill(done.toFloat() / queue.size)
        if (installing && !engine.fixing) {
            installing = false; mainBtn.shimmer = false
            if (failed) { mainBtn.label = "Повторить"; mainBtn.setFill(0f) }
            else { mainBtn.label = "Перейти к сборке"; mainBtn.setFill(1f); skipBtn.visibility = View.INVISIBLE }
        }
    }

    private fun onMain() {
        if (installing) return
        val bad = queue.filter { it.state == FixState.ERROR || it.state == FixState.OFFERED }
        if (bad.isNotEmpty()) runInstall(bad) else finish()
    }

    // ------------------------------------------------------------------ переходы

    /**
     * Страницы листаются целиком, как в пейджере: обе движутся одновременно с одной скоростью и без прозрачности —
     * новая въезжает справа и чуть снизу, старая уходит влево и чуть вверх. Назад — в обратную сторону.
     */
    private fun go(to: Int) {
        if (moving || to == page) return
        val pages = listOf(welcome, choose, install)
        val from = pages[page]; val dest = pages[to]
        val s = if (to > page) 1f else -1f
        val w = width.toFloat().coerceAtLeast(context.pxf(300f)); val dy = context.pxf(96f); val rot = 2.6f
        moving = true
        dest.visibility = View.VISIBLE; dest.alpha = 1f
        dest.translationX = w * s; dest.translationY = dy * s; dest.rotation = rot * s
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 430; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                from.translationX = -w * s * p; from.translationY = -dy * s * p; from.rotation = -rot * s * p
                dest.translationX = w * s * (1f - p); dest.translationY = dy * s * (1f - p); dest.rotation = rot * s * (1f - p)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    from.visibility = View.GONE; from.translationX = 0f; from.translationY = 0f; from.rotation = 0f
                    dest.translationX = 0f; dest.translationY = 0f; dest.rotation = 0f; moving = false
                }
            })
            start()
        }
        page = to
    }

    /** Закрыть: вся страница уходит влево и чуть вверх, под ней уже стоит приложение. */
    fun finish() {
        if (closing) return
        closing = true
        installing = false; ui.removeCallbacks(ticker)
        val w = width.toFloat(); val dy = context.pxf(96f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 430; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { val p = it.animatedValue as Float; translationX = -w * p; translationY = -dy * p; rotation = -2.6f * p }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { onDone() } })
            start()
        }
    }

    /** Открыть сразу на выборе компонентов (из настроек). */
    fun startAtChoose() { welcome.visibility = View.GONE; choose.visibility = View.VISIBLE; page = 1 }

    /** Кнопка «назад» системы. Возвращает true, если событие обработано. */
    fun back(): Boolean {
        if (moving) return true
        return when (page) { 1 -> { go(0); true } ; 2 -> if (installing) true else { go(1); true } ; else -> true }
    }

    override fun onDetachedFromWindow() { ui.removeCallbacks(ticker); super.onDetachedFromWindow() }
}
