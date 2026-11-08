package com.silas.pocketplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.os.Build
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 极简列表适配器：不引 Glide/Coil/Room，缩略图自己用 ContentResolver + LruCache 搞定。 */
class VideoAdapter(private val onClick: (VideoItem) -> Unit) :
    RecyclerView.Adapter<VideoAdapter.VH>() {

    private var data: List<VideoItem> = emptyList()

    fun submit(list: List<VideoItem>) {
        data = list
        notifyDataSetChanged()
    }

    fun snapshot(): List<VideoItem> = data

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(RowView(parent.context))
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position], onClick)
    override fun getItemCount(): Int = data.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: VideoItem, onClick: (VideoItem) -> Unit) {
            val row = itemView as RowView
            row.title.text = item.title
            row.meta.text = "${if (item.durationMs > 0) formatTime(item.durationMs) else "--:--"}   ·   ${formatSize(item.sizeBytes)}"
            row.path.text = item.path.substringBeforeLast('/', item.path)
            Thumbnails.load(row.context, item, row.thumb)
            itemView.setOnClickListener { onClick(item) }
        }
    }

    class RowView(ctx: Context) : LinearLayout(ctx) {
        val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF232A3A.toInt())
            val radius = 10.dp(ctx).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
        }
        val title = TextView(ctx).apply {
            textSize = 15f
            setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val meta = TextView(ctx).apply {
            textSize = 12f
            setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            maxLines = 1
        }
        val path = TextView(ctx).apply {
            textSize = 11f
            setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(ctx), 8.dp(ctx), 16.dp(ctx), 8.dp(ctx))
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            isClickable = true
            isFocusable = true

            addView(thumb, LayoutParams(112.dp(ctx), 66.dp(ctx)).apply { marginEnd = 14.dp(ctx) })
            val column = LinearLayout(ctx).apply { orientation = VERTICAL }
            column.addView(title, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            column.addView(meta, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 3.dp(ctx) })
            column.addView(path, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 2.dp(ctx) })
            addView(column, LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }
}

private fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
}

private object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun load(ctx: Context, item: VideoItem, into: ImageView) {
        val key = item.path.ifBlank { item.uri.toString() }
        into.tag = key
        cache.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageBitmap(null)
        scope.launch {
            val bmp = runCatching { decode(ctx, item) }.getOrNull() ?: return@launch
            cache.put(key, bmp)
            withContext(Dispatchers.Main) {
                if (into.tag == key) into.setImageBitmap(bmp)
            }
        }
    }

    private fun decode(ctx: Context, item: VideoItem): Bitmap? {
        // 有 MediaStore id 的走系统缩略图，比 MediaMetadataRetriever 快一个数量级
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.mediaStoreId >= 0) {
            val quick = runCatching {
                ctx.contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
            }.getOrNull()
            if (quick != null) return quick
        }
        val retriever = MediaMetadataRetriever()
        return try {
            if (item.path.isNotEmpty() && File(item.path).canRead()) {
                retriever.setDataSource(item.path)
            } else {
                retriever.setDataSource(ctx, item.uri)
            }
            retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (t: Throwable) {
            null
        } finally {
            retriever.release()
        }
    }
}
