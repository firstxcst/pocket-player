package com.silas.pocketplayer

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * 全格式版：NextLib 的 NextRenderersFactory 与 DefaultRenderersFactory 一对一兼容，
 * 在系统 MediaCodec 解不了时回退到打包的 FFmpeg 软解（H.264 / HEVC / VP8 / VP9 + 音频全家族）。
 * 这不是自研解码：FFmpeg 与 dav1d 的预编译产物由 NextLib 维护并发布。
 */
internal fun createRenderersFactory(context: Context): RenderersFactory =
    NextRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
