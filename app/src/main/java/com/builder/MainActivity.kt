package com.builder

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.builder.core.BuildError
import com.builder.core.Group
import com.builder.core.Fix
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.builder.core.Engine
import com.builder.core.EngineListener
import com.builder.core.L
import com.builder.core.Result
import com.builder.core.St
import java.io.File

private typealias LP = LinearLayout.LayoutParams

private const val REQ_ZIP = 7
private const val REQ_KEY = 8

class MainActivity : Activity(), EngineListener, TermActions {
    private val app get() = application as App
    private val engine get() = app.engine
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var scroll: ScrollView
    private lateinit var chain: ChainView
    private lateinit var start: ActionButton
    private lateinit var pill: StatusPill
    private lateinit var newBtn: IconButton
    private lateinit var tabs: TabBar
    private lateinit var buildPage: FrameLayout
    private lateinit var runPage: LinearLayout
    private lateinit var heroLayer: FrameLayout
    private lateinit var heroProject: TextView
    private lateinit var heroNote: TextView
    private lateinit var zipBtn: ActionButton
    private var heroOn = true
    private var importing = false
    private var noteOverride: String? = null
    private var noteOk = true
    private lateinit var term: TerminalView
    private lateinit var history: HistoryPage
    private lateinit var projectView: TextView
    private lateinit var timerView: TextView
    private lateinit var card: ResultCard
    private lateinit var fixCard: FixCard
    private var fixShown = false
    private var wasFixing = false
    private var page = 0
    private var cancelled = false
    private var rolling = false
    private lateinit var res: ResourceStrip
    private val probe = com.builder.core.SysProbe()
    private val probeThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "probe").apply { isDaemon = true } }
    private val resTick = object : Runnable {
        override fun run() {
            if (!engine.running) return
            probeThread.execute {
                val s = try { probe.sample() } catch (_: Exception) { null }
                if (s != null) ui.post {
                    val gb = 1048576.0
                    res.set(s.cpu, s.ram, "%.1f/%.1f ГБ".format(s.ramUsedKb / gb, s.ramTotalKb / gb), s.procFrac,
                        if (s.procRamKb >= 1048576) "%.1f ГБ".format(s.procRamKb / gb) else "${s.procRamKb / 1024} МБ")
                }
            }
            ui.postDelayed(this, 700)
        }
    }
    private fun startResources() { res.show(true); ui.removeCallbacks(resTick); ui.post(resTick) }
    private var lastTouch = 0L
    private var lastFill = -1f
    private var lastStartAt = 0L
    private var onboarding: Onboarding? = null
    private var morph = -1f
    private var morphAnim: android.animation.ValueAnimator? = null
    private var scrollAnim: android.animation.ValueAnimator? = null

    private val ticker = object : Runnable {
        override fun run() {
            chain.tick(); updateTimer(); updateProgress()
            if (engine.running) ui.postDelayed(this, 100)
        }
    }

    private fun tv(s: String, sp: Float, color: Int, face: String = "sans-serif") = TTextView(this).apply {
        text = s; textSize = sp; setTextColor(color); typeface = Typeface.create(face, Typeface.NORMAL)
    }

    private fun enter(v: View, delay: Long, dy: Float = 16f) {
        v.alpha = 0f; v.translationY = pxf(dy)
        // startDelay у ViewPropertyAnimator «залипает» для всех следующих анимаций вида — сбрасываем по завершении
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(380).setInterpolator(DecelerateInterpolator(2f))
            .withEndAction { v.animate().setStartDelay(0) }.start()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.apply(this); applyWindow()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.BG) }

        // шапка: название, статус, линия-акцент
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(24), px(36), px(24), 0) }
        val title = tv("APK TBuilder", 30f, Pal.TEXT, "sans-serif-medium").apply { maxLines = 1 }
        pill = StatusPill(this)
        newBtn = IconButton(this, R.drawable.ic_plus, "Новый проект").apply { setOnClickListener { pickZip() } }
        val gear = IconButton(this, R.drawable.ic_settings, "Настройки").apply { setOnClickListener { openSettings() } }
        head.addView(title, LP(0, WRAP_CONTENT, 1f))
        head.addView(newBtn, LP(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = px(10) })
        head.addView(gear, LP(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(head)
        val rule = View(this).apply { setBackgroundColor(Pal.ACCENT); pivotX = 0f; scaleX = 0f }
        col.addView(rule, LP(px(40), px(2)).apply { topMargin = px(12); marginStart = px(24) })
        val subRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(24), px(12), px(24), px(10)) }
        val sub = tv("Цепочка сборки APK прямо на телефоне", 13f, Pal.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        subRow.addView(sub, LP(0, WRAP_CONTENT, 1f))
        subRow.addView(pill, LP(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = px(12) })
        col.addView(subRow)
        rule.animate().scaleX(1f).setStartDelay(120).setDuration(520).setInterpolator(DecelerateInterpolator(2f)).withEndAction { rule.animate().setStartDelay(0) }.start()
        enter(title, 0); enter(subRow, 140, 8f); enter(newBtn, 180, 8f); enter(gear, 220, 8f)

        tabs = TabBar(this, listOf("Сборка", "История", "Терминал")) { switchPage(it) }
        col.addView(tabs, LP(MATCH_PARENT, WRAP_CONTENT).apply { marginStart = px(24); marginEnd = px(24) })

        val frame = FrameLayout(this)
        buildPage = buildBuildPage()
        term = TerminalView(this, engine, this)
        term.visibility = View.GONE
        term.onActivity = { if (page != 2) tabs.setBadge(2, true) }
        history = HistoryPage(this, engine); history.visibility = View.GONE
        frame.addView(buildPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(history, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(term, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        col.addView(frame, LP(MATCH_PARENT, 0, 1f))
        val rootFrame = FrameLayout(this)
        rootFrame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(rootFrame)
        rootFrameRef = rootFrame
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarded", false)) {
            if (engine.toolchainReady) prefs.edit().putBoolean("onboarded", true).apply()
            else showOnboarding(false)
        }
        if (b?.getBoolean("settings") == true) openSettings(false)

        chain.setStages(engine.stages, true)
        refreshProject()
        updateControls()
        showHero(wantsHero(), false)
        if (b == null) { handleIncoming(intent); debugHook(intent) }
    }

    /** Служебный запуск для отладочной сборки: `am start … --ez autobuild true [--es project имя]`. */
    private fun debugHook(i: Intent?) {
        i ?: return
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!i.getBooleanExtra("autobuild", false)) return
        i.removeExtra("autobuild")
        engine.debugOffline = i.getBooleanExtra("offline", false)
        val zip = i.getStringExtra("import")
        if (zip != null) {
            // импорт zip из папки приложения и сборка: для автоматических проверок
            Thread {
                val n = try { engine.importZip(File(zip), i.getStringExtra("project")) { } } catch (e: Exception) { engine.console.add("import: ${e.message}"); null }
                ui.post { if (n != null) { engine.useProject(n); ui.postDelayed({ if (!engine.running && !engine.fixing) onStartClick() }, 600) } }
            }.start()
            return
        }
        i.getStringExtra("project")?.let { if (!engine.running) engine.useProject(it) }
        ui.postDelayed({ if (!engine.running && !engine.fixing) onStartClick() }, 600)
    }

    private fun buildBuildPage(): FrameLayout {
        val root = FrameLayout(this)
        runPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        runPage.visibility = View.GONE
        val pg = runPage
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24), px(16) + px(54), px(24), px(2)) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        projectView = tv("", 12f, Pal.SOFT, "monospace").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, px(12), px(16), px(6)); setOnClickListener { pickProject() } }
        timerView = tv("", 12f, Pal.MUTED, "monospace").apply { gravity = Gravity.END; maxLines = 1; minWidth = px(124); setPadding(0, px(12), 0, px(6)) }
        info.addView(projectView, LP(0, WRAP_CONTENT, 1f)); info.addView(timerView, LP(WRAP_CONTENT, WRAP_CONTENT))
        controls.addView(info)
        res = ResourceStrip(this)
        controls.addView(res, LP(MATCH_PARENT, WRAP_CONTENT))
        pg.addView(controls)

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            setOnTouchListener { _, _ -> lastTouch = System.currentTimeMillis(); scrollAnim?.cancel(); false }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(24), 0, px(24), px(32))
            // карточки ошибки/результата «раздвигают» страницу плавно, а не скачком
            layoutTransition = android.animation.LayoutTransition().apply {
                setDuration(280); enableTransitionType(android.animation.LayoutTransition.CHANGING)
                disableTransitionType(android.animation.LayoutTransition.APPEARING); disableTransitionType(android.animation.LayoutTransition.DISAPPEARING)
                disableTransitionType(android.animation.LayoutTransition.CHANGE_APPEARING); disableTransitionType(android.animation.LayoutTransition.CHANGE_DISAPPEARING)
            }
        }
        chain = ChainView(this, { follow(it) }, { tabs.select(2) })
        card = ResultCard(this,
            { latest()?.let { installChecked(it) } },
            { latest()?.let { ApkShare.share(this, it) } },
            { latest()?.let { f -> exportAsync(f) } })
        fixCard = FixCard(this, { sel -> engine.applyFixes(sel) }, { engine.dismissFixes() }, { engine.cancel() })
        content.addView(chain, LP(MATCH_PARENT, WRAP_CONTENT))
        content.addView(fixCard, LP(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(24) })
        content.addView(card, LP(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(24) })
        scroll.addView(content)
        pg.addView(scroll, LP(MATCH_PARENT, 0, 1f))
        root.addView(pg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // экран «до сборки»: снизу — проект и выбор ZIP; сама кнопка — одна на всё приложение
        heroLayer = FrameLayout(this)
        heroNote = tv("", 12f, Pal.MUTED).apply { gravity = Gravity.CENTER }
        heroProject = tv("", 12f, Pal.SOFT, "monospace").apply { gravity = Gravity.CENTER; setPadding(px(16), px(10), px(16), px(10)); setOnClickListener { pickProject() } }
        zipBtn = ActionButton(this).apply { label = "Выбрать ZIP с проектом"; ghost = true; heightDp = 50; setOnClickListener { pickZip() } }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(24), 0, px(24), px(28)) }
        bottom.addView(heroNote, LP(MATCH_PARENT, WRAP_CONTENT))
        bottom.addView(heroProject, LP(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(6) })
        bottom.addView(zipBtn, LP(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(6) })
        heroLayer.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(heroLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // единственная кнопка «Собрать»: большая по центру, при запуске плавно уезжает наверх и становится полосой прогресса
        start = ActionButton(this).apply {
            label = "Собрать"; managed = true
            icon = getDrawable(R.drawable.ic_android)
            setOnClickListener { onStartClick() }
        }
        root.addView(start, FrameLayout.LayoutParams(px(168), px(168), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (morphAnim?.isRunning != true) applyMorph(if (heroOn) 1f else 0f) }
        start.alpha = 0f; start.scaleX = .85f; start.scaleY = .85f
        start.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(240).setDuration(520).setInterpolator(DecelerateInterpolator(2f)).withEndAction { start.animate().setStartDelay(0) }.start()
        enter(bottom, 460, 24f)
        return root
    }

    /** t=1 — большая кнопка по центру, t=0 — полоса вверху. Размер и положение считаются от размеров страницы, поэтому ничего не «прыгает». */
    private fun applyMorph(t: Float) {
        if (t == morph || buildPage.width == 0) return
        morph = t
        val pw = buildPage.width; val ph = buildPage.height
        val sideMargin = px(24)
        val bigW = px(168); val bigH = px(168)
        val barW = pw - 2 * sideMargin; val barH = px(54)
        val w = barW + ((bigW - barW) * t).toInt()
        val h = barH + ((bigH - barH) * t).toInt()
        val topBar = px(16)
        val topBig = ((ph - bigH) / 2 - px(24)).coerceAtLeast(topBar)
        val top = topBar + ((topBig - topBar) * t).toInt()
        val lp = start.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h || lp.topMargin != top) { lp.width = w; lp.height = h; lp.topMargin = top; start.layoutParams = lp }
        start.heroT = t
    }

    private fun runMorph(to: Float, ms: Long) {
        morphAnim?.cancel()
        val from = if (morph < 0f) 1f - to else morph
        morphAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = ms; interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { applyMorph(it.animatedValue as Float) }
            start()
        }
    }

    /** Показывает либо экран с большой кнопкой, либо ход сборки. */
    private fun showHero(on: Boolean, animate: Boolean) {
        if (on == heroOn && heroLayer.visibility == (if (on) View.VISIBLE else View.GONE) && morph == (if (on) 1f else 0f)) return
        heroOn = on
        if (on) {
            runPage.animate().cancel(); heroLayer.animate().cancel()
            heroLayer.visibility = View.VISIBLE; heroLayer.alpha = if (animate) 0f else 1f
            if (animate) { heroLayer.animate().alpha(1f).setStartDelay(160).setDuration(380).start(); runMorph(1f, 480) } else { morphAnim?.cancel(); applyMorph(1f) }
            if (animate) runPage.animate().alpha(0f).setStartDelay(0).setDuration(220).withEndAction { if (heroOn) runPage.visibility = View.GONE }.start()
            else { runPage.visibility = View.GONE }
        } else {
            runPage.visibility = View.VISIBLE
            heroLayer.animate().cancel()
            if (animate) {
                runPage.alpha = 0f; runPage.animate().alpha(1f).setStartDelay(260).setDuration(380).start()
                heroLayer.animate().alpha(0f).setStartDelay(0).setDuration(220).withEndAction { if (!heroOn) heroLayer.visibility = View.GONE }.start()
                runMorph(0f, 520)
            } else { morphAnim?.cancel(); applyMorph(0f); runPage.alpha = 1f; heroLayer.visibility = View.GONE }
        }
    }

    private fun wantsHero() = !engine.running && engine.result == null && engine.lastError == null && engine.stages.none { it.group == Group.BUILD && it.state != St.IDLE }


    // ------------------------------------------------------------------ тема, язык, настройки

    private var rootFrameRef: FrameLayout? = null
    private var settings: SettingsPage? = null

    /** Фон окна, системные панели и «не гасить экран» по текущей теме и настройкам. */
    private fun applyWindow() {
        val w = window
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Pal.BG))
        w.statusBarColor = Pal.BG; w.navigationBarColor = Pal.BG
        var f = w.decorView.systemUiVisibility
        if (Build.VERSION.SDK_INT >= 23) f = if (Pal.dark) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = if (Pal.dark) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.decorView.systemUiVisibility = f
        if (Prefs.keepOn(this)) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        if (Prefs.apply(this)) recreate() // сменилась тема или язык системы
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putBoolean("settings", settings != null) }

    private fun showOnboarding(fromSettings: Boolean) {
        val root = rootFrameRef ?: return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val o = Onboarding(this, engine) {
            prefs.edit().putBoolean("onboarded", true).apply()
            root.removeView(onboarding); onboarding = null
            refreshProject(); updateControls()
        }
        onboarding = o
        root.addView(o, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (fromSettings) o.startAtChoose()
    }

    private fun openSettings(animate: Boolean = true) {
        val root = rootFrameRef ?: return
        if (settings != null) return
        val s = SettingsPage(this, { closeSettings() }, { full ->
            // язык и тема применяются после того, как доиграет анимация переключателя; «не гасить экран» — сразу, без пересоздания
            Prefs.apply(this); applyWindow()
            if (full) ui.postDelayed({ if (!isFinishing) recreate() }, 200)
        }, SettingsPage.Actions(
            installer = { closeSettings(); showOnboarding(true) },
            welcome = { closeSettings(); showOnboarding(false) },
            clearHistory = { engine.clearHistory(); history.onShown() },
            resetToolchain = { engine.resetToolchain(); refreshProject(); updateControls() },
            busy = { engine.running || engine.fixing },
            keyInfo = { engine.keyFingerprint()?.let { "SHA-256 " + it.take(16) + "…" } ?: "создастся при первой сборке" },
            keyMenu = { keyMenu() },
            version = "v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }),
        ))
        settings = s
        root.addView(s, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (animate) {
            s.alpha = 0f
            s.animate().alpha(1f).setDuration(160).start()
        }
    }

    // ------------------------------------------------------------------ ключ подписи

    private fun keyMenu() {
        if (engine.running) { Toast.makeText(this, L.tr("Дождись конца сборки"), Toast.LENGTH_SHORT).show(); return }
        val items = arrayOf(L.tr("Импортировать ключ…"), L.tr("Экспортировать ключ в Загрузки"), L.tr("Создать новый ключ"))
        AlertDialog.Builder(this).setTitle(L.tr("Ключ подписи"))
            .setMessage(L.tr("Android ставит обновление только если APK подписан тем же ключом, что и установленное приложение. Подставь ключ, которым подписано твоё приложение (PKCS12 .p12/.pfx или zip из экспорта)."))
            .setItems(items) { _, i ->
                when (i) {
                    0 -> try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_KEY)
                    } catch (e: Exception) { Toast.makeText(this, L.tr("Нет приложения для выбора файлов"), Toast.LENGTH_LONG).show() }
                    1 -> Thread {
                        val msg = try { "Сохранено: " + ApkShare.export(this, engine.exportKey(), "application/zip") + " — храни как пароль" } catch (e: Exception) { "Не удалось сохранить: ${e.message}" }
                        ui.post { Toast.makeText(this, L.tr(msg), Toast.LENGTH_LONG).show() }
                    }.start()
                    2 -> AlertDialog.Builder(this).setMessage(L.tr("Создать новый ключ? Обновить уже установленные приложения, подписанные старым ключом, будет нельзя."))
                        .setPositiveButton(L.tr("Создать")) { _, _ -> engine.resetKey(); com.builder.core.ApkSigner.loadOrCreate(engine.keysDir); Toast.makeText(this, L.tr("Ключ создан"), Toast.LENGTH_SHORT).show() }
                        .setNegativeButton(L.tr("Отмена"), null).show()
                }
            }.setNegativeButton(L.tr("Закрыть"), null).show()
    }

    private fun importKeyFrom(uri: Uri) {
        Thread {
            val tmp = File(filesDir, "work/incoming.key").also { it.parentFile?.mkdirs() }
            try { contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } } }
            catch (e: Exception) { ui.post { Toast.makeText(this, e.message ?: "ошибка", Toast.LENGTH_LONG).show() }; return@Thread }
            val isZip = tmp.inputStream().use { i -> ByteArray(2).also { i.read(it) } }.let { it[0] == 'P'.code.toByte() && it[1] == 'K'.code.toByte() }
            ui.post {
                if (isZip) finishKeyImport(tmp, "", null) else askKeyPassword(tmp)
            }
        }.start()
    }

    private fun askKeyPassword(f: File) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24), px(8), px(24), 0) }
        val pw = android.widget.EditText(this).apply { hint = L.tr("Пароль хранилища"); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val al = android.widget.EditText(this).apply { hint = L.tr("Псевдоним ключа (можно пусто)"); setSingleLine() }
        box.addView(pw); box.addView(al)
        AlertDialog.Builder(this).setTitle(L.tr("Импорт ключа")).setView(box)
            .setPositiveButton(L.tr("Импортировать")) { _, _ -> finishKeyImport(f, pw.text.toString(), al.text.toString()) }
            .setNegativeButton(L.tr("Отмена")) { _, _ -> f.delete() }.show()
    }

    private fun finishKeyImport(f: File, password: String, alias: String?) {
        Thread {
            val msg = try { "Ключ подставлен: SHA-256 " + engine.importKey(f, password, alias).take(16) + "…" } catch (e: Exception) { "Не получилось: ${e.message}" }
            f.delete()
            ui.post { AlertDialog.Builder(this).setMessage(L.tr(msg)).setPositiveButton(L.tr("Ок"), null).show() }
        }.start()
    }

    private fun closeSettings() {
        val s = settings ?: return
        settings = null
        s.animate().alpha(0f).setDuration(140).withEndAction { rootFrameRef?.removeView(s) }.start()
    }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onStart() {
        super.onStart()
        app.ui = this; app.visible = true
        chain.rebind()
        refreshProject()
        updateControls()
        showHero(wantsHero(), false)
        fixCard.bind(engine.fixes, engine.fixing); if (engine.fixes.isNotEmpty()) fixShown = true
        val r = engine.result
        if (r != null && !engine.running && engine.lastError == null) card.show(r, false)
        if (engine.running) { ui.removeCallbacks(ticker); ui.post(ticker); startResources() }
    }

    override fun onStop() {
        app.ui = null; app.visible = false
        ui.removeCallbacks(ticker); ui.removeCallbacks(resTick)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onboarding?.back() == true) return
        if (settings != null) { closeSettings(); return }
        if (page != 0) tabs.select(0) else super.onBackPressed()
    }

    // ------------------------------------------------------------------ события движка (из рабочего потока)

    override fun onStage(i: Int) { ui.post { chain.onStage(i); updateControls() } }

    override fun onRun(running: Boolean) {
        ui.post {
            if (running) { cancelled = false; lastFill = -1f; card.hideNow(); fixCard.hideNow(); fixShown = false; chain.begin(); showHero(false, true); ui.removeCallbacks(ticker); ui.post(ticker); startResources() }
            updateControls()
        }
    }

    override fun onFinish(result: Result?, error: String?, cancelled: Boolean) {
        ui.post {
            this.cancelled = cancelled
            ui.postDelayed({ if (!engine.running) res.show(false) }, 1400)
            updateControls()
            if (page == 1) history.load(false)
            if (result != null && error == null) {
                val wave = chain.celebrate()
                ui.postDelayed({ card.show(result, true); scrollToEnd(400) }, wave)
            } else if (!cancelled) scrollToEnd(350)
        }
    }

    override fun onFixes() {
        ui.post {
            val list = engine.fixes
            if (wasFixing && !engine.fixing) chain.rebind()
            wasFixing = engine.fixing
            fixCard.bind(list, engine.fixing)
            updateControls()
            if (list.isNotEmpty() && !fixShown) { fixShown = true; scroll.postDelayed({ glideTo(fixCard.top, 520) }, 500) }
            else if (list.isEmpty()) fixShown = false
        }
    }

    override fun onProject() {
        ui.post { chain.rebind(); refreshProject(); card.hideNow(); cancelled = false; updateControls(); showHero(wantsHero(), true) }
    }

    // ------------------------------------------------------------------ управление

    private fun onStartClick() {
        if (engine.fixing) return
        val now = System.currentTimeMillis()
        if (engine.running) { if (now - lastStartAt > 900) engine.cancel(); return }
        lastStartAt = now
        if (rolling) return
        card.hideNow()
        rolling = true
        chain.rollbackThen { rolling = false; if (!engine.running && !engine.fixing) engine.start() }
    }

    private fun latest(): File? = engine.result?.apk?.takeIf { it.isFile }
        ?: engine.outDir.listFiles { f -> f.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }

    private fun exportAsync(f: File) {
        Thread {
            val msg = try { "Сохранено: " + ApkShare.export(this, f) } catch (e: Exception) { "Не удалось сохранить: ${e.message}" }
            ui.post { Toast.makeText(this, com.builder.core.L.tr(msg), Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun pickProject() {
        if (engine.running) { Toast.makeText(this, com.builder.core.L.tr("Проект нельзя менять во время сборки"), Toast.LENGTH_SHORT).show(); return }
        val l = engine.projects()
        val items = (listOf(L.tr("Импортировать ZIP…")) + l).toTypedArray()
        AlertDialog.Builder(this).setTitle(L.tr("Проект"))
            .setItems(items) { _, i -> if (i == 0) pickZip() else engine.useProject(l[i - 1]) }
            .setNegativeButton(L.tr("Закрыть"), null).show()
    }

    // ------------------------------------------------------------------ импорт ZIP

    override fun pickZip() {
        if (engine.running) { Toast.makeText(this, com.builder.core.L.tr("Дождись конца сборки"), Toast.LENGTH_SHORT).show(); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream"))
        try { startActivityForResult(i, REQ_ZIP) } catch (e: Exception) { Toast.makeText(this, com.builder.core.L.tr("Нет приложения для выбора файлов"), Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_ZIP && res == RESULT_OK) data?.data?.let { importUri(it) }
        if (req == REQ_KEY && res == RESULT_OK) data?.data?.let { importKeyFrom(it) }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleIncoming(i); debugHook(i) }

    /** ZIP, присланный из другого приложения («Открыть с помощью» / «Поделиться»). */
    private fun handleIncoming(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) { i.action = null; importUri(uri) }
    }

    private fun importUri(uri: Uri) {
        if (importing) return
        if (engine.running) { Toast.makeText(this, com.builder.core.L.tr("Дождись конца сборки"), Toast.LENGTH_SHORT).show(); return }
        importing = true
        start.shimmer = true; heroNote.text = "импортирую…"
        val work = File(filesDir, "work").apply { mkdirs() }
        Thread {
            val log = ArrayList<String>()
            var shown = ""; var shownOk = true
            try {
                var name = "project"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
                val tmp = File(work, "incoming.zip")
                contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                engine.console.add("\u001B[1mИмпорт\u001B[0m  \u001B[90m$name · ${com.builder.core.Downloader.human(tmp.length())}\u001B[0m")
                val n = engine.importZip(tmp, name) { log.add(it); engine.console.add("\u001B[90m  $it\u001B[0m") }
                tmp.delete()
                shown = "проект «$n» — " + (log.lastOrNull { it.startsWith("файлов") } ?: "готов"); shownOk = true
                ui.post { engine.useProject(n) }
            } catch (e: BuildError) {
                shown = e.message ?: "ошибка"; shownOk = false
                ui.post { AlertDialog.Builder(this).setTitle(L.tr("Не получилось")).setMessage(L.tr((e.message ?: "") + (e.tip?.let { "\n\n$it" } ?: ""))).setPositiveButton(L.tr("Ок"), null).show() }
            } catch (e: Exception) {
                shown = e.message ?: "ошибка"; shownOk = false
                ui.post { AlertDialog.Builder(this).setTitle(L.tr("Не получилось")).setMessage(L.tr(e.message ?: e.toString())).setPositiveButton(L.tr("Ок"), null).show() }
            } finally {
                File(work, "incoming.zip").delete()
            }
            ui.post {
                importing = false; start.shimmer = false
                noteOverride = shown; noteOk = shownOk; refreshProject()
                heroNote.alpha = 0f; heroNote.animate().alpha(1f).setDuration(300).start()
                engine.console.add((if (shownOk) "\u001B[32m✓ \u001B[0m" else "\u001B[31m✗ \u001B[0m") + shown)
                ui.postDelayed({ noteOverride = null; if (!importing) refreshProject() }, 6000)
            }
        }.start()
    }

    private fun refreshProject() {
        projectView.text = "ПРОЕКТ  ${engine.project}"; projectView.iconEnd(ico(R.drawable.ic_chevron_down, Pal.MUTED, 16f), 6)
        heroProject.text = "ПРОЕКТ  ${engine.project}"; heroProject.iconEnd(ico(R.drawable.ic_chevron_down, Pal.MUTED, 16f), 6)
        heroNote.text = noteOverride ?: if (engine.toolchainReady) "инструменты готовы" else "первый запуск скачает инструменты и библиотеки (~100 МБ), дальше всё работает без интернета"
        if (noteOverride != null) heroNote.iconStart(ico(if (noteOk) R.drawable.ic_check else R.drawable.ic_close, if (noteOk) Pal.OK else Pal.ERROR, 14f), 6)
        else heroNote.iconStart(null)
    }

    private fun follow(v: View) {
        if (System.currentTimeMillis() - lastTouch < 3000) return
        glideTo((v.top - px(150)).coerceAtLeast(0), 420)
    }

    /** Докручивает до самого низа после того, как отработают анимации раскрытия (высота контента ещё растёт). */
    private fun scrollToEnd(after: Long) {
        for (d in longArrayOf(after, after + 450)) scroll.postDelayed({ if (!isFinishing) glideTo(Int.MAX_VALUE / 2, 420, true) }, d)
    }

    /** Плавная прокрутка с замедлением в конце; мелкие сдвиги пропускаются, чтобы не дёргать. */
    private fun glideTo(y: Int, ms: Long, force: Boolean = false) {
        val max = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        val to = y.coerceIn(0, max); val from = scroll.scrollY
        if (Math.abs(to - from) < (if (force) 2 else px(24))) return
        scrollAnim?.cancel()
        scrollAnim = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = ms; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { scroll.scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    private fun pageView(i: Int): View = when (i) { 0 -> buildPage; 1 -> history; else -> term }

    private fun switchPage(i: Int) {
        if (i == page) return
        val from = pageView(page); val to = pageView(i)
        val dir = if (i > page) 1 else -1
        page = i
        to.visibility = View.VISIBLE; to.alpha = 0f; to.translationX = px(40) * dir.toFloat()
        to.animate().cancel(); from.animate().cancel()
        to.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        from.animate().alpha(0f).translationX(-px(40) * dir.toFloat()).setStartDelay(0).setDuration(220)
            .withEndAction { if (pageView(page) !== from) { from.visibility = View.GONE; from.alpha = 1f }; from.translationX = 0f }.start()
        if (i == 1) history.onShown()
        if (i != 2) (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(term.windowToken, 0)
    }

    // ------------------------------------------------------------------ вид кнопки, статуса и таймера

    private fun overall(): Float = engine.overall()

    private fun updateProgress() {
        if (!engine.running) return
        val f = overall()
        if (Math.abs(f - lastFill) > 0.002f) { lastFill = f; start.setFill(f); start.label = "Отменить · ${(f * 100).toInt()}%" }
    }

    private fun updateControls() {
        val e = engine
        newBtn.animate().alpha(if (e.running || e.fixing) 0.35f else 1f).setStartDelay(0).setDuration(220).start()
        when {
            e.fixing -> {
                start.label = "Докачка…"; start.shimmer = true
                pill.set("Докачка", Pal.ACCENT, true)
            }
            e.running -> {
                val n = minOf(e.stages.size, e.stages.count { it.state == St.DONE || it.state == St.SKIPPED } + 1)
                start.label = "Отменить · ${(e.overall() * 100).toInt()}%"; start.shimmer = true
                pill.set("%02d/%02d".format(n, e.stages.size), Pal.ACCENT, true)
                updateProgress()
            }
            e.lastError != null -> {
                start.shimmer = false; start.label = "Повторить"; start.setFill(0f); lastFill = -1f
                pill.set("Ошибка", Pal.ERROR, false)
            }
            cancelled -> {
                start.shimmer = false; start.label = "Собрать"; start.setFill(0f); lastFill = -1f
                pill.set("Остановлено", Pal.WARN, false)
            }
            e.result != null -> {
                start.shimmer = false; start.label = "Собрать снова"; start.setFill(1f); lastFill = -1f
                pill.set("Готово", Pal.OK, false)
            }
            else -> {
                start.shimmer = false; start.label = "Собрать"; start.setFill(0f); lastFill = -1f
                pill.set("Готов", Pal.MUTED, false)
            }
        }
        updateTimer()
    }

    private fun clock(ms: Long) = "%02d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)

    private fun updateTimer() {
        val e = engine
        val done = e.stages.count { it.state == St.DONE || it.state == St.SKIPPED }
        timerView.text = when {
            e.running -> {
                val eta = e.etaMs()
                "${(e.overall() * 100).toInt()}%  ·  " + (if (eta >= 0) "ещё ≈" + Engine.fmtMs(eta) + "  ·  " else "") + clock(System.currentTimeMillis() - e.runStartedAt)
            }
            e.result != null -> "$done/${e.stages.size}  ·  ${clock(e.result!!.ms)}"
            else -> "${e.stages.size} шагов"
        }
    }

    // ------------------------------------------------------------------ действия терминала

    override fun showBuild() { ui.post { tabs.select(0) } }
    override fun install(f: File) = installChecked(f)

    /** Установка с предварительной проверкой подписи и версии: причина отказа Android объясняется заранее. */
    private fun installChecked(f: File) {
        val warn = ApkShare.preflight(this, f)
        if (warn == null) { ApkShare.install(this, f); return }
        AlertDialog.Builder(this).setTitle(L.tr("Не установится")).setMessage(L.tr(warn))
            .setPositiveButton(L.tr("Всё равно")) { _, _ -> ApkShare.install(this, f) }
            .setNegativeButton(L.tr("Закрыть"), null).show()
    }
    override fun share(f: File) = ApkShare.share(this, f)
    override fun export(f: File): String = ApkShare.export(this, f)
}
