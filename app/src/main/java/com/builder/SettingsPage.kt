package com.builder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

private typealias SL = LinearLayout.LayoutParams

/** Спокойная кривая: быстрый старт и очень мягкая остановка. */
private fun calm(): android.animation.TimeInterpolator = DecelerateInterpolator(1.5f)

/** Переключатель: квадратный «ползунок» мягко переезжает по дорожке, цвет перетекает, при нажатии ползунок чуть растягивается. */
private class Toggle(c: Context, on: Boolean, private val onChange: (Boolean) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var checked = on; private set
    private var t = if (on) 1f else 0f
    private var press = 0f
    private var anim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null
    init { isClickable = true; setOnClickListener { set(!checked, true); onChange(checked) } }

    fun set(v: Boolean, animate: Boolean) {
        checked = v
        anim?.cancel()
        if (!animate) { t = if (v) 1f else 0f; invalidate(); return }
        // новая анимация стартует с текущего положения (отмена прежней не «прыгает»), длительность — по оставшемуся пути
        val to = if (v) 1f else 0f
        anim = ValueAnimator.ofFloat(t, to).apply {
            duration = (180 * Math.abs(to - t)).toLong().coerceAtLeast(60); interpolator = calm()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        press = 0f
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.px(46), context.px(26))
    override fun onDetachedFromWindow() { anim?.cancel(); pressAnim?.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val bw = context.pxf(1.5f)
        p.style = Paint.Style.FILL; p.color = lerpColor(Pal.SURFACE, Pal.ACCENT, t); cv.drawRect(0f, 0f, w, h, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = lerpColor(Pal.MUTED, Pal.ACCENT, t)
        cv.drawRect(bw / 2, bw / 2, w - bw / 2, h - bw / 2, p)
        val k = h - context.pxf(10f); val stretch = 0f
        val travel = w - k - context.pxf(10f) - stretch
        val x = context.pxf(5f) + travel * t
        p.style = Paint.Style.FILL; p.color = lerpColor(Pal.MUTED, Pal.ON_ACCENT, t)
        cv.drawRect(x, h / 2 - k / 2, x + k + stretch, h / 2 + k / 2, p)
    }
}

/** Выбор из нескольких вариантов в одну строку: подсветка плавно скользит к выбранному, цвет текста перетекает. */
private class Segment(c: Context, private val opts: List<Pair<String, String>>, cur: String, private val onPick: (String) -> Unit) : LinearLayout(c) {
    private val cells = ArrayList<TTextView>()
    private val p = Paint()
    private var sel = cur
    private var pos = opts.indexOfFirst { it.first == cur }.coerceAtLeast(0).toFloat()
    private var anim: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        opts.forEach { (key, label) ->
            val tv = TTextView(c).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, c.px(12), 0, c.px(12)); isClickable = true
                setOnClickListener { if (sel != key) { sel = key; slide(opts.indexOfFirst { it.first == key }.toFloat()); onPick(key) } }
            }
            cells.add(tv); addView(tv, SL(0, WRAP_CONTENT, 1f))
        }
        paint()
    }

    private fun slide(to: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, to).apply {
            duration = (180 * Math.abs(to - pos) / 1f).toLong().coerceIn(80, 260); interpolator = calm()
            addUpdateListener { pos = it.animatedValue as Float; paint(); invalidate() }
            start()
        }
    }

    private fun paint() {
        cells.forEachIndexed { i, tv -> tv.setTextColor(lerpColor(Pal.SOFT, Pal.ON_ACCENT, 1f - minOf(1f, Math.abs(pos - i)))) }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun dispatchDraw(cv: Canvas) {
        val w = width.toFloat() / opts.size
        p.color = Pal.SURFACE; cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.color = Pal.ACCENT; cv.drawRect(w * pos, 0f, w * pos + w, height.toFloat(), p)
        super.dispatchDraw(cv)
    }
}

/** Строка настроек: при касании фон просто слегка светлеет, без анимации. */
private class PressRow(c: Context) : LinearLayout(c) {
    init { setBackgroundColor(Pal.SURFACE) }
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (hasOnClickListeners()) setBackgroundColor(if (pressed) Pal.RAISED else Pal.SURFACE)
    }
}

class SettingsPage(
    c: Context,
    private val onClose: () -> Unit,
    /** true — нужно полностью пересоздать экран (язык, тема); пересоздаётся после завершения анимации. */
    private val onChanged: (Boolean) -> Unit,
    private val actions: Actions,
) : FrameLayout(c) {

    class Actions(
        val installer: () -> Unit, val welcome: () -> Unit,
        val clearHistory: () -> Unit, val resetToolchain: () -> Unit,
        val busy: () -> Boolean, val version: String,
        val keyInfo: () -> String, val keyMenu: () -> Unit,
    )

    private fun tv(s: String, sp: Float, col: Int, face: String = "sans-serif") = TTextView(context).apply {
        text = s; textSize = sp; setTextColor(col); typeface = Typeface.create(face, Typeface.NORMAL)
    }

    private fun section(col: LinearLayout, s: String) {
        col.addView(tv(s, 11f, Pal.ACCENT, "sans-serif-medium").apply { letterSpacing = 0.2f; text = com.builder.core.L.tr(s).uppercase() },
            SL(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.px(26); bottomMargin = context.px(10) })
    }

    private fun row(col: LinearLayout, title: String, hint: String?, right: View?, click: (() -> Unit)? = null) {
        val r = PressRow(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(context.px(16), context.px(14), context.px(16), context.px(14))
            if (click != null) { isClickable = true; setOnClickListener { click() } }
        }
        val t = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        t.addView(tv(title, 15f, Pal.TEXT, "sans-serif-medium"))
        if (hint != null) t.addView(tv(hint, 12f, Pal.MUTED), SL(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.px(3) })
        r.addView(t, SL(0, WRAP_CONTENT, 1f))
        if (right != null) r.addView(right, SL(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.px(12) })
        col.addView(r, SL(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = context.px(2) })
    }

    private fun confirm(msg: String, yes: String, go: () -> Unit) {
        android.app.AlertDialog.Builder(context).setMessage(com.builder.core.L.tr(msg))
            .setPositiveButton(com.builder.core.L.tr(yes)) { _, _ -> go() }
            .setNegativeButton(com.builder.core.L.tr("Отмена"), null).show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(context, com.builder.core.L.tr(s), android.widget.Toast.LENGTH_SHORT).show()

    init {
        setBackgroundColor(Pal.BG)
        isClickable = true // не пропускаем касания на страницу под настройками
        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(context.px(24), context.px(36), context.px(24), 0) }
        head.addView(tv("Настройки", 28f, Pal.TEXT, "sans-serif-medium"), SL(0, WRAP_CONTENT, 1f))
        head.addView(IconButton(context, R.drawable.ic_close, "Закрыть").apply { setOnClickListener { onClose() } }, SL(WRAP_CONTENT, WRAP_CONTENT))
        outer.addView(head)
        val rule = View(context).apply { setBackgroundColor(Pal.ACCENT) }
        outer.addView(rule, SL(context.px(40), context.px(2)).apply { topMargin = context.px(12); marginStart = context.px(24) })

        val sv = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.px(24), 0, context.px(24), context.px(40)) }

        section(col, "Внешний вид")
        col.addView(tv("Язык", 13f, Pal.SOFT), SL(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = context.px(8) })
        col.addView(Segment(context, listOf("auto" to "Авто", "ru" to "Русский", "en" to "English"), Prefs.lang(context)) { Prefs.setStr(context, "lang", it); onChanged(true) },
            SL(MATCH_PARENT, WRAP_CONTENT))
        col.addView(tv("Тема", 13f, Pal.SOFT), SL(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.px(16); bottomMargin = context.px(8) })
        col.addView(Segment(context, listOf("auto" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная"), Prefs.theme(context)) { Prefs.setStr(context, "theme", it); onChanged(true) },
            SL(MATCH_PARENT, WRAP_CONTENT))
        col.addView(tv("«Авто» следует языку и теме телефона.", 12f, Pal.MUTED), SL(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.px(8) })

        section(col, "Сборка")
        row(col, "Не гасить экран", "Пока приложение открыто",
            Toggle(context, Prefs.keepOn(context)) { Prefs.setBool(context, "keepon", it); onChanged(false) })
        row(col, "Уведомление о ходе", "Прогресс загрузки и сборки в шторке",
            Toggle(context, Prefs.notify(context)) { Prefs.setBool(context, "notify", it) })
        row(col, "Уведомление о результате", "Когда сборка закончилась, а приложение закрыто",
            Toggle(context, Prefs.finishedNote(context)) { Prefs.setBool(context, "finished", it) })

        row(col, "Растущий номер версии", "Каждая сборка новее прежней — ставится поверх старой без «пакет повреждён»",
            Toggle(context, Prefs.autoVersion(context)) { Prefs.setBool(context, "autover", it) })

        row(col, "Свежие версии библиотек", "Зависимости без версии берутся самые новые; без сети — из кэша",
            Toggle(context, Prefs.freshLibs(context)) { Prefs.setBool(context, "fresh", it) })

        section(col, "Инструменты")
        row(col, "Установка компонентов", "Выбрать и докачать нужные файлы", null) {
            if (actions.busy()) toast("Дождись конца сборки") else actions.installer()
        }
        row(col, "Показать приветствие", "Первый экран ещё раз", null) { actions.welcome() }
        row(col, "Удалить скачанные инструменты", "Тулчейн скачается заново при следующей сборке", null) {
            if (actions.busy()) toast("Дождись конца сборки")
            else confirm("Удалить скачанный тулчейн?", "Удалить") { actions.resetToolchain(); toast("Тулчейн удалён") }
        }

        section(col, "Подпись")
        row(col, "Ключ подписи", actions.keyInfo(), null) { actions.keyMenu() }

        section(col, "Данные")
        row(col, "Очистить историю сборок", "График и список на вкладке «История»", null) {
            confirm("Очистить историю сборок?", "Очистить") { actions.clearHistory(); toast("История очищена") }
        }

        section(col, "О приложении")
        row(col, "APK TBuilder", actions.version, null)

        sv.addView(col)
        outer.addView(sv, SL(MATCH_PARENT, 0, 1f))
        addView(outer, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
}
