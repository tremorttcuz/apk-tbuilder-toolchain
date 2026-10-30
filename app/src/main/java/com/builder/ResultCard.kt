package com.builder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.builder.core.Downloader
import com.builder.core.Engine
import com.builder.core.Result

/** Карточка готового APK: рамка прорисовывается по периметру, кнопки появляются с заливкой. */
class ResultCard(ctx: Context, onInstall: () -> Unit, onShare: () -> Unit, onExport: () -> Unit) : LinearLayout(ctx) {
    private val label = TTextView(ctx)
    private val name = TTextView(ctx)
    private val meta = TTextView(ctx)
    private val sha = TTextView(ctx)
    private val info = TTextView(ctx)
    private val install = ActionButton(ctx)
    private val share = ActionButton(ctx)
    private val export = ActionButton(ctx)

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.pxf(1.5f) }
    private val rect = Path()
    private val seg = Path()
    private var draw = 0f
    private var anim: ValueAnimator? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setBackgroundColor(Pal.SURFACE)
        setPadding(ctx.px(18), ctx.px(18), ctx.px(18), ctx.px(18))
        visibility = View.GONE

        label.apply { text = "APK ГОТОВ"; textSize = 11f; letterSpacing = 0.2f; setTextColor(Pal.ACCENT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        name.apply { textSize = 19f; setTextColor(Pal.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        meta.apply { textSize = 12f; setTextColor(Pal.SOFT); typeface = Typeface.MONOSPACE }
        sha.apply { textSize = 11f; setTextColor(Pal.MUTED); typeface = Typeface.MONOSPACE }
        info.apply { textSize = 12f; setTextColor(Pal.SOFT) }
        addView(label)
        addView(name, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(8) })
        addView(meta, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(6) })
        addView(info, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(4) })
        addView(sha, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(4) })

        install.apply { this.label = "Установить"; setOnClickListener { onInstall() } }
        addView(install, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(18) })
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        share.apply { this.label = "Поделиться"; ghost = true; heightDp = 46; setOnClickListener { onShare() } }
        export.apply { this.label = "В Загрузки"; ghost = true; heightDp = 46; setOnClickListener { onExport() } }
        row.addView(share, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(5) })
        row.addView(export, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ctx.px(5) })
        addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(10) })
    }

    fun show(r: Result, withAnim: Boolean) {
        name.text = r.apk.name
        meta.text = Downloader.human(r.size) + "  ·  собрано за " + Engine.fmtMs(r.ms)
        info.text = android.text.SpannableStringBuilder(com.builder.core.L.tr(r.info)).also { sb ->
            var o = 0
            for (line in com.builder.core.L.tr(r.info).split("\n")) {
                val g = line.indexOf('⚠')
                if (g in 0..1) {
                    sb.setSpan(android.text.style.ForegroundColorSpan(Pal.WARN), o, o + line.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(android.text.style.ImageSpan(context.ico(R.drawable.ic_warn, Pal.WARN, 14f), android.text.style.ImageSpan.ALIGN_BASELINE), o + g, o + g + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                o += line.length + 1
            }
        }
        info.visibility = if (r.info.isEmpty()) View.GONE else View.VISIBLE
        sha.text = "sha256 " + r.sha.take(12) + "…" + r.sha.takeLast(6)
        visibility = View.VISIBLE
        anim?.cancel()
        if (!withAnim) { draw = 1f; alpha = 1f; translationY = 0f; install.setFill(1f, false); invalidate(); return }
        draw = 0f; alpha = 0f; translationY = context.pxf(28f)
        animate().alpha(1f).translationY(0f).setDuration(460).setInterpolator(DecelerateInterpolator(2.2f)).start()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; startDelay = 120; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { draw = it.animatedValue as Float; invalidate() }; start()
        }
        install.setFill(0f, false)
        postDelayed({ install.setFill(1f) }, 420)
    }

    fun hideNow() { anim?.cancel(); animate().cancel(); visibility = View.GONE }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val hf = border.strokeWidth / 2
        rect.reset(); rect.addRect(hf, hf, w - hf, h - hf, Path.Direction.CW)
    }

    override fun dispatchDraw(cv: Canvas) {
        super.dispatchDraw(cv)
        border.color = Pal.LINE; cv.drawPath(rect, border)
        if (draw > 0f) {
            val pm = PathMeasure(rect, false)
            seg.reset(); pm.getSegment(0f, pm.length * draw, seg, true)
            border.color = Pal.ACCENT; cv.drawPath(seg, border)
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
}
