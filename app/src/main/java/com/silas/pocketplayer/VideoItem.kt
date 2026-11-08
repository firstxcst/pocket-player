package com.silas.pocketplayer

import android.content.Context
import android.net.Uri
import java.util.Locale

/**
 * 统一的视频条目。MediaStore 扫描出来的用 content:// URI，深度补漏出来的用 file:// URI，
 * 两者都能直接喂给 ExoPlayer 的 DefaultDataSource。
 */
data class VideoItem(
    val uri: Uri,
    val path: String,
    val title: String,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val dateAddedSec: Long = 0L,
    val mimeType: String? = null,
    val mediaStoreId: Long = -1L,
    /** MediaStore 卷名；主存储为 external/external_primary，OTG / SD 卡为卷序列号 */
    val volumeName: String = ""
) {
    val key: String get() = path.lowercase(Locale.ROOT).trimEnd('/')
}

/** 播放队列放在内存里：不用 Parcelable、不用数据库，冷启动零成本。 */
object PlaybackQueue {
    var items: List<VideoItem> = emptyList()
    var index: Int = 0

    fun set(list: List<VideoItem>, start: Int) {
        items = list
        index = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "--"
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1024f / 1024f / 1024f)
    else -> String.format(Locale.ROOT, "%.0f MB", bytes / 1024f / 1024f)
}

internal fun Int.dp(ctx: Context): Int = (this * ctx.resources.displayMetrics.density + 0.5f).toInt()
