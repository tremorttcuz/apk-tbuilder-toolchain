package com.builder

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import com.builder.core.Engine
import com.builder.core.EngineListener
import com.builder.core.Platform
import com.builder.core.Result
import com.builder.core.St
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Мост между ядром сборки и Android: ABI, assets, папка приложения. */
class AndroidPlatform(private val ctx: Context) : Platform {
    override val files: File get() = ctx.filesDir
    override val abis: List<String> get() = Build.SUPPORTED_ABIS.toList()

    override fun asset(path: String): ByteArray? =
        try { ctx.assets.open(path).use { it.readBytes() } } catch (e: IOException) { null }

    override fun assetStream(path: String): java.io.InputStream? = try { ctx.assets.open(path) } catch (e: IOException) { null }

    override fun assetFiles(dir: String): List<String> {
        val out = ArrayList<String>()
        fun walk(path: String, rel: String) {
            for (name in ctx.assets.list(path) ?: emptyArray()) {
                val p = "$path/$name"; val r = if (rel.isEmpty()) name else "$rel/$name"
                if ((ctx.assets.list(p) ?: emptyArray()).isEmpty()) out.add(r) else walk(p, r)
            }
        }
        walk(dir, "")
        return out
    }
}

/** Процесс-широкий владелец движка: сборка живёт независимо от экрана и переживает пересоздание Activity. */
class App : Application() {
    lateinit var engine: Engine; private set
    @Volatile var ui: EngineListener? = null
    @Volatile var visible = false
    private var lastNotified = -1
    private var wasFixing = false
    private var lastPush = 0L

    /** Обновляет постоянное уведомление: что делаем, на каком шаге и сколько осталось (с полосой прогресса). Не чаще раза в 400 мс. */
    private fun push() {
        if (!Prefs.notify(this)) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPush < 400) return
        val e = engine
        val title: String; val text: String; val pct: Int
        when {
            e.fixing -> {
                val todo = e.fixTodo
                if (todo.isEmpty()) return
                val done = todo.count { it.state == com.builder.core.FixState.DONE }
                val cur = todo.firstOrNull { it.state == com.builder.core.FixState.RUNNING }
                val f = ((done + (cur?.progress?.takeIf { it >= 0f } ?: 0f)) / todo.size).coerceIn(0f, 1f)
                title = "APK TBuilder · загрузка"
                text = (cur?.let { "${it.title} · ${it.detail}" } ?: "Загрузка…") + "  (${minOf(done + 1, todo.size)}/${todo.size})"
                pct = (f * 100).toInt()
            }
            e.running -> {
                val i = e.stages.indexOfFirst { it.state == St.RUNNING }
                val s = e.stages.getOrNull(i)
                title = "APK TBuilder · сборка"
                text = if (s != null) "[${i + 1}/${e.stages.size}] ${s.title}" + (if (s.detail.isNotEmpty()) " · ${s.detail}" else "") else "Сборка…"
                pct = (e.overall() * 100).toInt()
            }
            else -> return
        }
        lastPush = now
        try { getSystemService(android.app.NotificationManager::class.java).notify(Notif.ID_RUN, Notif.build(this, text, true, title = title, progress = pct)) } catch (_: Exception) {}
    }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Prefs.apply(this)
        engine = Engine(AndroidPlatform(this), autoVersion = { Prefs.autoVersion(this) }, freshLibs = { Prefs.freshLibs(this) })
        engine.load()
        engine.listener = object : EngineListener {
            override fun onStage(i: Int) {
                ui?.onStage(i)
                val s = engine.stages.getOrNull(i) ?: return
                if (s.state == St.RUNNING && i != lastNotified) {
                    lastNotified = i
                    KeepAliveService.start(this@App, "[${i + 1}/${engine.stages.size}] ${s.title}")
                }
                push()
            }

            override fun onRun(running: Boolean) {
                ui?.onRun(running)
                if (running) { lastNotified = -1; KeepAliveService.start(this@App, "Сборка запущена") }
                else main.postDelayed({ if (!engine.running) KeepAliveService.stop(this@App) }, 900)
            }

            override fun onFinish(result: Result?, error: String?, cancelled: Boolean) {
                ui?.onFinish(result, error, cancelled)
                if (!visible && !cancelled && Prefs.finishedNote(this@App)) Notif.finished(this@App, error == null, (error?.lineSequence()?.first() ?: result?.let { "${it.apk.name} · ${com.builder.core.Downloader.human(it.size)}" } ?: "Готово") + if (engine.fixes.isNotEmpty()) " · можно докачать" else "")
            }

            override fun onProject() { ui?.onProject() }

            override fun onFixes() {
                ui?.onFixes()
                if (engine.fixing) { wasFixing = true; KeepAliveService.start(this@App, "Загрузка…"); push() }
                else {
                    if (wasFixing) {
                        wasFixing = false
                        // загрузка закончилась, пока экран закрыт — сообщаем в уведомлении
                        if (!visible) {
                            val bad = engine.lastFixFailed
                            Notif.finished(this@App, !bad, if (bad) "Загрузка не удалась — открой приложение" else "Компоненты установлены")
                        }
                    }
                    main.postDelayed({ if (!engine.running && !engine.fixing) KeepAliveService.stop(this@App) }, 900)
                }
            }
        }
    }
}

object Notif {
    private const val CH_RUN = "build_run"
    private const val CH_DONE = "build_done"
    const val ID_RUN = 41
    const val ID_DONE = 42

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_RUN) == null) nm.createNotificationChannel(NotificationChannel(CH_RUN, com.builder.core.L.tr("Ход сборки"), NotificationManager.IMPORTANCE_LOW))
        if (nm.getNotificationChannel(CH_DONE) == null) nm.createNotificationChannel(NotificationChannel(CH_DONE, com.builder.core.L.tr("Результат сборки"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Suppress("DEPRECATION")
    fun build(ctx: Context, text: String, ongoing: Boolean, ok: Boolean = true, title: String? = null, progress: Int = -1): Notification {
        ensure(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, if (ongoing) CH_RUN else CH_DONE) else Notification.Builder(ctx)
        return b.setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(com.builder.core.L.tr(title ?: if (ongoing) "APK TBuilder · сборка" else if (ok) "APK TBuilder · готово" else "APK TBuilder · ошибка"))
            .setContentText(com.builder.core.L.tr(text)).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(!ongoing)
            .apply { if (ongoing) setProgress(100, progress.coerceAtLeast(0), progress < 0) }.build()
    }

    fun finished(ctx: Context, ok: Boolean, text: String) {
        try { ctx.getSystemService(NotificationManager::class.java).notify(ID_DONE, build(ctx, text, false, ok)) } catch (_: Exception) {}
    }
}

/**
 * Пока идёт сборка — служба переднего плана с уведомлением и частичной блокировкой сна:
 * иначе Android усыпляет процесс, как только гаснет экран.
 */
class KeepAliveService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val text = i?.getStringExtra("text") ?: "Сборка…"
        try { startForeground(Notif.ID_RUN, Notif.build(this, text, true)) } catch (_: Exception) {}
        if (wl == null) {
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apkbuilder:build")
                .also { it.acquire(30 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context, text: String) {
            val i = Intent(ctx, KeepAliveService::class.java).putExtra("text", text)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (_: Exception) {} }
    }
}

/** Отдаёт готовые APK из папки out/ системному установщику и другим приложениям. */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val dir = File(context!!.filesDir, "out").canonicalFile
        val f = File(dir, name).canonicalFile
        return if (f.parentFile == dir && f.isFile) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri) ?: throw FileNotFoundException(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(Array<Any?>(cols.size) { k -> when (cols[k]) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        return c
    }

    override fun getType(uri: Uri): String = ApkShare.MIME
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

object ApkShare {
    const val MIME = "application/vnd.android.package-archive"

    fun uri(ctx: Context, f: File): Uri = Uri.Builder().scheme("content").authority(ctx.packageName + ".files").appendPath(f.name).build()

    fun install(act: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, com.builder.core.L.tr("Разреши этому приложению установку APK и нажми «Установить» ещё раз"), Toast.LENGTH_LONG).show()
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
            return
        }
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(act, f), MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Toast.makeText(act, com.builder.core.L.tr("Не удалось открыть установщик: ${e.message}"), Toast.LENGTH_LONG).show() }
    }

    fun share(act: Activity, f: File) {
        val i = Intent(Intent.ACTION_SEND).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri(act, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { act.startActivity(Intent.createChooser(i, "Поделиться APK")) }
        catch (e: Exception) { Toast.makeText(act, com.builder.core.L.tr("Не удалось поделиться: ${e.message}"), Toast.LENGTH_LONG).show() }
    }

    /** Копирует APK туда, где его видно в файловом менеджере. Возвращает человекочитаемый путь. */
    /**
     * Проверка перед установкой: если приложение уже стоит и подписано другим ключом либо у него версия новее,
     * Android откажет («пакет повреждён» / «не удалось установить») — предупреждаем заранее и объясняем причину.
     */
    fun preflight(ctx: Context, apk: File): String? {
        try {
            val pm = ctx.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else 0
            val mine = pm.getPackageArchiveInfo(apk.path, flags) ?: return null
            val installed = try { pm.getPackageInfo(mine.packageName, flags) } catch (_: Exception) { return null }
            @Suppress("DEPRECATION") val newCode = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else mine.versionCode.toLong()
            @Suppress("DEPRECATION") val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
            fun sigs(p: android.content.pm.PackageInfo): Set<String> =
                (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures)
                    ?.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet().orEmpty()
            val a = sigs(mine); val b = sigs(installed)
            if (a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty())
                return "У приложения ${mine.packageName} уже стоит версия, подписанная ДРУГИМ ключом, поэтому Android не даст обновить её («пакет повреждён» / «не удалось установить»).\n\n" +
                    "Что сделать: Настройки → «Ключ подписи» → импортировать ключ, которым подписана установленная версия, и собрать снова. Либо удалить старое приложение (его данные пропадут)."
            if (newCode < oldCode)
                return "Установленная версия новее ($oldCode), а эта — $newCode: Android не разрешает понижать версию. Включи «Растущий номер версии» в настройках и собери заново."
        } catch (_: Exception) {}
        return null
    }

    fun export(ctx: Context, f: File, mime: String = MIME): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/APK TBuilder")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("MediaStore отказал")
            r.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0); r.update(u, v, null, null)
            return "Загрузки/APK TBuilder/${f.name}"
        }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "apk").apply { mkdirs() }
        val dst = File(dir, f.name)
        f.copyTo(dst, overwrite = true)
        return dst.path
    }
}
