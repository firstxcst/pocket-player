package com.silas.pocketplayer

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory

/** 轻量版：只用系统 MediaCodec，不打包任何 native 库。 */
internal fun createRenderersFactory(context: Context): RenderersFactory =
    DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
