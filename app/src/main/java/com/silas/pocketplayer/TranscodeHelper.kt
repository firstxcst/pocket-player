@file:Suppress("UnsafeOptInUsageError", "DEPRECATION")

package com.silas.pocketplayer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * 常见转码场景（全部基于系统 MediaCodec，不需要 FFmpeg .so）：
 *  - 设备解不了某个编码 / 音轨是 AC3、DTS → 重编码成 H.264 + AAC 的 MP4
 *  - 只想留画面 → 去音轨
 *  - 只想验证或分享片段 → 裁剪导出
 * 输出到 app 私有外部目录，不申请额外写权限。
 *
 * 注意：RMVB / WMV 这类连容器都解不开的老格式，Transformer 与 ExoPlayer 共用 extractor，
 * 同样无能为力；官方不发布 FFmpeg 扩展 AAR，需自行用 NDK 编译，或在电脑端预处理。
 */
object TranscodeHelper {

    enum class Preset(val label: String, val removeAudio: Boolean, val trimEndMs: Long?) {
        COMPATIBLE("转码为兼容 MP4（H.264 / AAC）", false, null),
        MUTE("转码并去除音轨", true, null),
        CLIP_60("导出前 60 秒（快速验证）", false, 60_000L)
    }

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(output: File)
        fun onError(message: String)
    }

    fun start(context: Context, input: Uri, displayName: String, preset: Preset, cb: Callback) {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "transcoded")
        if (!dir.exists()) dir.mkdirs()
        val safe = displayName.substringBeforeLast('.')
            .replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
            .take(40)
        val output = File(dir, "${safe}_${preset.name.lowercase()}.mp4")
        if (output.exists()) output.delete()

        val main = Handler(Looper.getMainLooper())
        var finished = false
        fun complete(block: () -> Unit) {
            if (finished) return
            finished = true
            main.post(block)
        }

        // 1.5.x 推荐的新回调；旧 onTransformationCompleted/onTransformationError 已废弃
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                complete { cb.onSuccess(output) }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                complete { cb.onError(exportException.localizedMessage ?: "转码失败") }
            }
        }

        val transformer = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setLooper(Looper.getMainLooper())
            .addListener(listener)
            .build()

        val mediaItem = MediaItem.Builder().setUri(input).apply {
            if (preset.trimEndMs != null) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(preset.trimEndMs)
                        .build()
                )
            }
        }.build()

        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(preset.removeAudio)
            .build()

        try {
            transformer.start(edited, output.absolutePath)
        } catch (t: Throwable) {
            complete { cb.onError(t.message ?: "无法启动转码") }
            return
        }

        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    main.post { cb.onProgress(holder.progress) }
                }
                if (state != Transformer.PROGRESS_STATE_UNAVAILABLE) {
                    main.postDelayed(this, 250)
                }
            }
        }
        main.postDelayed(poll, 250)
    }
}
