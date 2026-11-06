package com.builder

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Тихие звуки только на итог сборки (готово, ошибка, отмена) и на вопрос помощника (файлы .ogg в res/raw). Любая ошибка звука игнорируется — сборку это не трогает. */
class SoundFx(ctx: Context) {
    private val pool: SoundPool? = try {
        SoundPool.Builder().setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
    } catch (_: Throwable) { null }
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()

    init {
        try {
            pool?.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready.add(id) } }
            load(ctx, "build_ok", R.raw.build_ok); load(ctx, "build_error", R.raw.build_error)
            load(ctx, "build_cancel", R.raw.build_cancel); load(ctx, "assistant_ask", R.raw.assistant_ask)
        } catch (_: Throwable) { }
    }

    private fun load(ctx: Context, name: String, res: Int) { pool?.load(ctx, res, 1)?.let { ids[name] = it } }

    fun play(name: String) {
        try {
            val id = ids[name] ?: return
            if (synchronized(ready) { id in ready }) pool?.play(id, 0.65f, 0.65f, 1, 0, 1f)
        } catch (_: Throwable) { }
    }

    fun release() { try { pool?.release() } catch (_: Throwable) { } }
}
