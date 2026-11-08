package com.silas.pocketplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 覆盖在 SeekBar 上的关键节点标记：亮橙竖线 + 白心圆点。
 * 纯绘制、不可点击，触摸事件会穿透给下面的 SeekBar。
 */
class MarkerView(context: Context) : View(context) {

    var durationMs: Long = 0L
    var marks: List<Long> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB74D.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB74D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pad = 12f * resources.displayMetrics.density

    init { isClickable = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0L || marks.isEmpty()) return
        val usable = (width - 2f * pad).coerceAtLeast(1f)
        val cy = height / 2f
        for (m in marks) {
            if (m < 0L || m > durationMs) continue
            val x = pad + usable * (m.toFloat() / durationMs.toFloat())
            canvas.drawRoundRect(x - 2f, 1f, x + 2f, height - 1f, 2f, 2f, linePaint)
            canvas.drawCircle(x, cy, 4.5f, ringPaint)
            canvas.drawCircle(x, cy, 2f, dotPaint)
        }
    }

    fun refresh() = invalidate()
}
