package com.builder

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.builder.core.Ansi
import com.builder.core.BuildError
import com.builder.core.Downloader
import com.builder.core.Engine
import com.builder.core.Shell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** То, что терминалу нужно от Activity: установка, отправка и экспорт APK, переключение вкладки. */
interface TermActions {
    fun showBuild()
    fun install(f: File)
    fun share(f: File)
    fun export(f: File): String
    fun pickZip()
}

/** Плоская «чип»-кнопка быстрого ввода: нажатие слегка сжимает её. */
private class Chip(ctx: Context, label: String, iconRes: Int = 0) : TTextView(ctx) {
    init {
        text = label; if (iconRes != 0) { text = ""; setCompoundDrawablesRelative(ctx.ico(iconRes, Pal.SOFT, 16f), null, null, null) }; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Pal.SOFT)
        gravity = Gravity.CENTER
        setPadding(ctx.px(13), ctx.px(8), ctx.px(13), ctx.px(8))
        background = GradientDrawable().apply { setColor(Pal.SURFACE); setStroke(ctx.px(1), Pal.LINE) }
        isFocusable = false; isFocusableInTouchMode = false; isClickable = true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) .93f else 1f).scaleY(if (pressed) .9f else 1f).setDuration(if (pressed) 70 else 160).start()
        setTextColor(if (pressed) Pal.TEXT else Pal.SOFT)
    }
}

class TerminalView(ctx: Context, private val engine: Engine, private val act: TermActions) : LinearLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val adapter = LineAdapter()
    private val list = ListView(ctx)
    private val promptView = TTextView(ctx)
    private val input = EditText(ctx)
    private val history = ArrayList<String>()
    private var hIdx = 0
    private val shell = Shell()
    private val pending = ConcurrentLinkedQueue<String>()
    @Volatile private var flushQueued = false
    private val home: File = engine.home
    private var cwd: File = if (engine.projectDir.isDirectory) engine.projectDir else home
    private var spin = 0
    private var attached = false
    private val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

    /** Вызывается, когда в терминал пришли новые строки (для точки-индикатора на вкладке). */
    var onActivity: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Pal.TERM)

        list.apply {
            divider = null; dividerHeight = 0; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(ctx.px(16), ctx.px(12), ctx.px(16), ctx.px(8)); clipToPadding = false
            this.adapter = this@TerminalView.adapter
            transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
        }
        addView(list, LayoutParams(MATCH_PARENT, 0, 1f))

        // строка ввода с линией-акцентом, которая выезжает при фокусе
        val inputRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.px(16), 0, ctx.px(16), 0) }
        promptView.apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(Pal.MUTED); setPadding(0, ctx.px(12), ctx.px(8), ctx.px(12)) }
        input.apply {
            background = null; hint = com.builder.core.L.tr("команда"); setHintTextColor(Pal.MUTED); setTextColor(Pal.TEXT)
            typeface = Typeface.MONOSPACE; textSize = 14f; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, ctx.px(12), 0, ctx.px(12))
            setOnEditorActionListener { _, _, _ -> submit(); true }
            setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { submit(); true } else false }
        }
        inputRow.addView(promptView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        inputRow.addView(input, LayoutParams(0, WRAP_CONTENT, 1f))

        val line = FrameLayout(ctx).apply { setPadding(ctx.px(16), 0, ctx.px(16), 0) }
        line.addView(View(ctx).apply { setBackgroundColor(Pal.LINE) }, FrameLayout.LayoutParams(MATCH_PARENT, ctx.px(1)))
        val acc = View(ctx).apply { setBackgroundColor(Pal.ACCENT); pivotX = 0f; scaleX = 0f }
        line.addView(acc, FrameLayout.LayoutParams(MATCH_PARENT, ctx.px(2)))
        input.setOnFocusChangeListener { _, f ->
            acc.animate().scaleX(if (f) 1f else 0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        addView(line, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(inputRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(buildChips(), LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        renderPrompt()
    }

    // ------------------------------------------------------------------ вывод

    private inner class LineAdapter : BaseAdapter() {
        val items = ArrayList<CharSequence>()
        var animFrom = Int.MAX_VALUE
        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val tv = (cv as? TextView) ?: TTextView(parent.context).apply {
                typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Pal.SOFT)
                setLineSpacing(0f, 1.12f); setPadding(0, parent.context.px(1), 0, parent.context.px(1))
            }
            tv.animate().cancel(); tv.alpha = 1f; tv.translationX = 0f
            tv.text = items[p]
            if (p >= animFrom) {
                tv.alpha = 0f; tv.translationX = tv.context.pxf(10f)
                tv.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }
            return tv
        }
    }

    private fun style(line: String): CharSequence {
        val pr = Ansi.parse(com.builder.core.L.tr(line), Pal.SOFT)
        if (pr.runs.isEmpty()) return pr.text
        val sp = SpannableString(pr.text)
        for (r in pr.runs) {
            sp.setSpan(ForegroundColorSpan(r.fg), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.bold) sp.setSpan(StyleSpan(Typeface.BOLD), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    /** Строки приходят из любых потоков; на экран выводим пачками раз в ~40 мс. */
    private fun enqueue(s: String) {
        pending.add(s)
        if (!flushQueued) { flushQueued = true; ui.postDelayed({ flush() }, 40) }
    }

    private fun flush() {
        flushQueued = false
        if (!attached) return
        val batch = ArrayList<CharSequence>()
        while (batch.size < 500) { val s = pending.poll() ?: break; batch.add(style(s)) }
        if (batch.isEmpty()) return
        val atBottom = adapter.count == 0 || list.lastVisiblePosition >= adapter.count - 2
        adapter.items.addAll(batch)
        if (adapter.items.size > 2500) adapter.items.subList(0, adapter.items.size - 2000).clear()
        adapter.animFrom = if (batch.size <= 40) adapter.items.size - minOf(batch.size, 6) else Int.MAX_VALUE
        adapter.notifyDataSetChanged()
        if (atBottom) list.setSelection(adapter.count - 1)
        ui.postDelayed({ adapter.animFrom = Int.MAX_VALUE }, 400)
        if (pending.isNotEmpty()) { flushQueued = true; ui.postDelayed({ flush() }, 40) }
        onActivity?.invoke()
    }

    private fun out(s: String) = engine.console.add(s)
    private fun c(code: String, s: String) = "\u001B[${code}m$s\u001B[0m"

    private fun reload() {
        pending.clear(); adapter.items.clear()
        engine.console.snapshot().takeLast(1500).forEach { adapter.items.add(style(it)) }
        adapter.animFrom = Int.MAX_VALUE
        adapter.notifyDataSetChanged()
        if (adapter.count > 0) list.setSelection(adapter.count - 1)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        reload()
        engine.console.listener = { enqueue(it) }
        engine.console.onClear = { ui.post { pending.clear(); adapter.items.clear(); adapter.notifyDataSetChanged() } }
        if (adapter.items.isEmpty()) banner()
        ui.post(spinner)
    }

    override fun onDetachedFromWindow() {
        attached = false
        engine.console.listener = null; engine.console.onClear = null
        ui.removeCallbacks(spinner)
        super.onDetachedFromWindow()
    }

    private fun banner() {
        val lines = listOf(
            c("1;34", "APK TBuilder") + c("90", "  · терминал"),
            c("90", "Команды: ") + "help  build  doctor  tools  projects",
            c("90", "Всё остальное выполняется как команда оболочки (ls, cat, unzip …)"),
        )
        lines.forEachIndexed { i, l -> ui.postDelayed({ out(l) }, 140L * i) }
    }

    // ------------------------------------------------------------------ приглашение и спиннер

    private fun cwdLabel(): String {
        val p = cwd.path; val h = home.path
        return if (p == h) "~" else if (p.startsWith("$h/")) "~/" + p.removePrefix("$h/") else p
    }

    private val spinner = object : Runnable {
        override fun run() {
            if (!attached) return
            renderPrompt()
            ui.postDelayed(this, if (shell.running || engine.running) 90 else 400)
        }
    }

    private fun renderPrompt() {
        val busy = shell.running || engine.running
        val glyph = if (busy) frames[spin++ % frames.length].toString() else "❯"
        val path = cwdLabel()
        val sp = SpannableString("$path $glyph")
        sp.setSpan(ForegroundColorSpan(Pal.MUTED), 0, path.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(if (busy) Pal.GLOW else Pal.ACCENT), path.length + 1, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        promptView.text = sp
    }

    // ------------------------------------------------------------------ быстрые кнопки

    private fun buildChips(): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(context.px(12), context.px(8), context.px(12), context.px(10)) }
        fun add(label: String, icon: Int = 0, onClick: () -> Unit) {
            val ch = Chip(context, label, icon)
            ch.setOnClickListener { onClick() }
            row.addView(ch, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.px(6) })
        }
        add("Tab") { complete() }
        add("↑", R.drawable.ic_arrow_up) { historyStep(-1) }
        add("↓", R.drawable.ic_arrow_down) { historyStep(1) }
        add("^C") { ctrlC() }
        for (s in listOf("/", "-", ".", "~", "|", "\"", "$")) add(s) { insert(s) }
        for (s in listOf("build", "doctor", "copy", "ls", "clear")) add(s) { input.setText(s); input.setSelection(s.length); submit() }
        val sv = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Pal.TERM); overScrollMode = View.OVER_SCROLL_NEVER }
        sv.addView(row)
        return sv
    }

    private fun insert(s: String) {
        val e = input.text; val at = input.selectionStart.coerceIn(0, e.length)
        e.insert(at, s)
    }

    private fun historyStep(d: Int) {
        if (history.isEmpty()) return
        hIdx = (hIdx + d).coerceIn(0, history.size)
        val t = if (hIdx >= history.size) "" else history[hIdx]
        input.setText(t); input.setSelection(t.length)
    }

    private fun ctrlC() {
        when {
            shell.running -> { shell.kill(); out(c("33", "^C")) }
            engine.running -> { engine.cancel(); out(c("33", "^C · сборка останавливается")) }
            else -> input.setText("")
        }
    }

    // ------------------------------------------------------------------ Tab

    private val builtins = listOf(
        "help", "build", "stop", "doctor", "tools", "projects", "use", "fix", "pull", "zip", "import", "apk", "install", "share", "export",
        "builds", "pipeline", "env", "clear", "cd", "pwd", "history", "reset", "copy",
    )
    private val shellWords = listOf("ls", "cat", "cp", "mv", "rm", "mkdir", "echo", "unzip", "find", "grep", "head", "tail", "du", "df", "ps", "touch", "chmod", "wc", "sh")

    private fun complete() {
        val text = input.text.toString()
        val cur = input.selectionStart.coerceIn(0, text.length)
        val before = text.substring(0, cur)
        val start = before.lastIndexOf(' ') + 1
        val tok = before.substring(start)
        val first = before.substring(0, start).isBlank()
        val cands = if (first && !tok.contains('/')) (builtins + shellWords).filter { it.startsWith(tok) }.distinct().sorted() else pathCandidates(tok)
        when {
            cands.isEmpty() -> return
            cands.size == 1 -> replaceToken(start, cur, cands[0] + if (cands[0].endsWith("/")) "" else " ")
            else -> {
                var cp = cands[0]
                for (x in cands) { var k = 0; while (k < cp.length && k < x.length && cp[k] == x[k]) k++; cp = cp.substring(0, k) }
                if (cp.length > tok.length) replaceToken(start, cur, cp) else out(c("90", cands.joinToString("  ")))
            }
        }
    }

    private fun replaceToken(start: Int, end: Int, with: String) {
        input.text.replace(start, end, with)
        input.setSelection(start + with.length)
    }

    private fun pathCandidates(tok: String): List<String> {
        val expanded = if (tok.startsWith("~")) home.path + tok.drop(1) else tok
        val slash = expanded.lastIndexOf('/')
        val dirPart = if (slash >= 0) expanded.substring(0, slash + 1) else ""
        val name = expanded.substring(slash + 1)
        val dir = if (dirPart.startsWith("/")) File(dirPart) else File(cwd, dirPart)
        val shownPrefix = tok.substring(0, tok.lastIndexOf('/') + 1)
        return dir.list()?.filter { it.startsWith(name) }?.sorted()?.map { shownPrefix + it + if (File(dir, it).isDirectory) "/" else "" } ?: emptyList()
    }

    // ------------------------------------------------------------------ выполнение

    private fun submit() {
        val line = input.text.toString()
        input.setText("")
        if (shell.running) { shell.write(line); out(c("90", "› $line")); return }
        if (line.isBlank()) return
        if (history.lastOrNull() != line) history.add(line)
        hIdx = history.size
        out(c("90", cwdLabel()) + c("1;34", " ❯ ") + line)
        val t = tokenize(line)
        val meta = line.any { it in "|;&<>$`" }
        if (!meta && t.isNotEmpty() && builtin(t[0], t.drop(1))) return
        runShell(line)
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = '\u0000'; var has = false
        for (ch in s) {
            when {
                q != '\u0000' -> if (ch == q) q = '\u0000' else sb.append(ch)
                ch == '"' || ch == '\'' -> { q = ch; has = true }
                ch.isWhitespace() -> if (sb.isNotEmpty() || has) { out.add(sb.toString()); sb.setLength(0); has = false }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty() || has) out.add(sb.toString())
        return out
    }

    private fun runShell(cmd: String) {
        val t0 = System.currentTimeMillis()
        val env = HashMap(engine.env()); env["PWD"] = cwd.path
        Thread {
            var code = -1
            try { code = shell.run(cmd, cwd, env) { out(it) } }
            catch (e: Exception) { out(c("31", "✗ ${e.message}")) }
            out(c(if (code == 0) "90" else "31", "[код $code · ${Engine.fmtMs(System.currentTimeMillis() - t0)}]"))
        }.start()
        renderPrompt()
    }

    private fun latestApk(arg: String?): File? {
        if (arg != null) return File(engine.outDir, arg).takeIf { it.isFile }
        return engine.outDir.listFiles { f -> f.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
    }

    private fun builtin(cmd: String, a: List<String>): Boolean {
        when (cmd) {
            "help", "?" -> help()
            "clear", "cls" -> engine.console.clear()
            "pwd" -> out(cwd.path)
            "cd" -> {
                val target = when {
                    a.isEmpty() || a[0] == "~" -> home
                    a[0].startsWith("~/") -> File(home, a[0].drop(2))
                    a[0].startsWith("/") -> File(a[0])
                    else -> File(cwd, a[0])
                }.let { try { it.canonicalFile } catch (e: Exception) { it } }
                if (target.isDirectory) { cwd = target; renderPrompt() } else out(c("31", "cd: нет такой папки: ${a.firstOrNull()}"))
            }
            "history" -> history.forEachIndexed { i, s -> out(c("90", "%3d  ".format(i + 1)) + s) }
            "build", "b" -> { if (engine.running) out(c("33", "сборка уже идёт")) else { engine.start(); out(c("90", "→ сборка запущена · вкладка СБОРКА показывает цепочку")) } }
            "stop" -> if (engine.running) engine.cancel() else out(c("90", "нечего останавливать"))
            "doctor" -> Thread {
                try { engine.diagnose(); out(c("32", "✓ окружение в порядке")) }
                catch (e: BuildError) { out(c("1;31", "✗ ${e.message}")); e.tip?.let { out(c("33", "  ↳ $it")) } }
                catch (e: Exception) { out(c("1;31", "✗ ${e.message}")) }
            }.start()
            "tools" -> {
                val t = engine.env()
                if (engine.tools.isEmpty()) out(c("33", "инструменты не найдены — запусти сборку, тулчейн скачается"))
                engine.tools.forEach { (n, f) -> out(c("32", "✓ ") + n.padEnd(10) + c("90", f.path.removePrefix(engine.root.path + "/"))) }
                t["ANDROID_JAR"]?.let { out(c("32", "✓ ") + "android.jar".padEnd(10) + c("90", it.removePrefix(engine.root.path + "/"))) }
            }
            "projects" -> engine.projects().forEach { out((if (it == engine.project) c("1;34", "* ") else "  ") + it) }
            "use" -> when {
                a.isEmpty() -> out(c("31", "use <имя>  — список: projects"))
                engine.running -> out(c("33", "нельзя менять проект во время сборки"))
                engine.useProject(a[0]) -> { cwd = engine.projectDir; renderPrompt(); out(c("32", "✓ проект ${a[0]}")) }
                else -> out(c("31", "нет проекта «${a[0]}»"))
            }
            "pull" -> {
                if (a.isEmpty()) out(c("31", "pull <ссылка-на-zip> [имя]   например: https://github.com/user/repo/archive/refs/heads/main.zip"))
                else Thread {
                    out(c("90", "↓ ${a[0]}"))
                    try {
                        val n = engine.pull(a[0], a.getOrNull(1)) { out(c("90", it)) }
                        out(c("32", "✓ проект «$n» загружен"))
                        if (engine.useProject(n)) { ui.post { cwd = engine.projectDir; renderPrompt() }; out(c("90", "  выбран как текущий")) }
                    } catch (e: Exception) { out(c("31", "✗ ${e.message}")) }
                }.start()
            }
            "fix" -> {
                val fs = engine.fixes
                if (engine.fixing) out(c("90", "докачка идёт…"))
                else if (fs.isEmpty()) out(c("90", "докачивать нечего — предложения появляются после ошибки сборки"))
                else if (a.firstOrNull() == "yes") { out(c("90", "качаю ${fs.size} шт., затем соберу заново")); engine.applyFixes(); ui.post { act.showBuild() } }
                else {
                    out(c("1;33", "Можно докачать:"))
                    fs.forEach { out(c("33", "  • ${it.title}") + c("90", "  ${it.why}" + if (it.bytes > 0) " · ${Downloader.human(it.bytes)}" else "")) }
                    out(c("90", "  fix yes — скачать всё и собрать снова"))
                }
            }
            "zip" -> { out(c("90", "открываю выбор файла…")); ui.post { act.pickZip() } }
            "import" -> {
                val f = a.firstOrNull()?.let { File(if (it.startsWith("/")) it else File(cwd, it).path) }
                if (f == null || !f.isFile) out(c("31", "import <путь-к-zip> [имя]   либо просто zip — выбрать файл из памяти"))
                else Thread {
                    try {
                        val n = engine.importZip(f, a.getOrNull(1)) { out(c("90", "  $it")) }
                        out(c("32", "✓ проект «$n» импортирован"))
                        if (engine.useProject(n)) ui.post { cwd = engine.projectDir; renderPrompt() }
                    } catch (e: Exception) { out(c("31", "✗ ${e.message}")) }
                }.start()
            }
            "apk" -> {
                val l = engine.outDir.listFiles { f -> f.name.endsWith(".apk") }?.sortedByDescending { it.lastModified() }.orEmpty()
                if (l.isEmpty()) out(c("90", "готовых APK пока нет — build"))
                l.forEach { out(c("32", "● ") + it.name + c("90", "  ${Downloader.human(it.length())}  ${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(it.lastModified()))}")) }
            }
            "install", "share", "export" -> {
                val f = latestApk(a.firstOrNull())
                if (f == null) out(c("31", "нет APK — сначала build"))
                else ui.post {
                    when (cmd) {
                        "install" -> act.install(f)
                        "share" -> act.share(f)
                        else -> Thread { val where = try { act.export(f) } catch (e: Exception) { "ошибка: ${e.message}" }; out(c("32", "✓ ") + where) }.start()
                    }
                }
            }
            "copy", "log" -> {
                val lines = engine.console.snapshot().map { Ansi.parse(it, 0).text }
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("apk-builder", lines.joinToString("\n")))
                out(c("32", "✓ журнал скопирован · ${lines.size} строк"))
            }
            "builds" -> {
                val h = engine.history(10)
                if (h.isEmpty()) out(c("90", "история пуста"))
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                h.forEach {
                    val ok = it["ok"] == true
                    out(c(if (ok) "32" else "31", if (ok) "✓ " else "✗ ") + fmt.format(Date((it["t"] as? Number)?.toLong() ?: 0L)) + "  " +
                        (it["project"] ?: "") + c("90", "  ${Engine.fmtMs((it["ms"] as? Number)?.toLong() ?: 0L)}  ${it["apk"] ?: ""}"))
                }
            }
            "pipeline" -> engine.stages.forEachIndexed { i, s ->
                out(c("90", "%2d ".format(i + 1)) + (if (s.group == com.builder.core.Group.TOOLCHAIN) c("36", "тулчейн ") else c("34", "сборка  ")) +
                    s.title + c("90", "  [${s.op}]"))
            }
            "env" -> engine.env().toSortedMap().forEach { (k, v) -> out(c("36", k) + "=" + v) }
            "reset" -> when {
                a.firstOrNull() != "toolchain" -> out(c("31", "reset toolchain --yes   — удалить скачанные инструменты"))
                "--yes" !in a -> out(c("33", "Это удалит скачанный тулчейн. Подтверди: reset toolchain --yes"))
                engine.running -> out(c("33", "идёт сборка"))
                else -> { engine.resetToolchain(); out(c("32", "✓ тулчейн удалён — build скачает его заново")) }
            }
            else -> return false
        }
        return true
    }

    private fun help() {
        val rows = listOf(
            "build" to "собрать APK (ход виден на вкладке СБОРКА)",
            "stop" to "остановить сборку",
            "doctor" to "проверить инструменты, проект, место",
            "tools" to "какие инструменты найдены",
            "projects" to "список проектов · use <имя> — выбрать",
            "fix [yes]" to "что докачать после ошибки · yes — скачать и пересобрать",
            "zip" to "выбрать zip с проектом из памяти телефона и сразу импортировать",
            "import <файл> [имя]" to "импортировать zip по пути",
            "pull <url> [имя]" to "скачать проект из zip (GitHub: …/archive/refs/heads/main.zip)",
            "apk" to "готовые APK · install | share | export [файл]",
            "builds" to "история сборок",
            "copy" to "скопировать весь журнал в буфер (чтобы отправить ошибку)",
            "pipeline" to "шаги сборки (pipeline.json)",
            "env" to "переменные окружения сборки",
            "reset toolchain --yes" to "удалить скачанный тулчейн",
            "clear · cd · pwd · history" to "как в обычном терминале",
        )
        out(c("1", "Команды"))
        rows.forEach { out("  " + c("1;34", it.first.padEnd(24)) + c("90", it.second)) }
        out(c("90", "Остальное идёт в sh: ls, cat, unzip, find …  ·  Tab — дополнение, ↑↓ — история, ^C — стоп"))
    }
}
