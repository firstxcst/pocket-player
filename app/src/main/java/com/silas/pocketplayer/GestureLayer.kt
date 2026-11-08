package com.silas.pocketplayer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 全屏手势层（左 / 中 / 右三分区）：
 *  - 单击：显隐控制条
 *  - 双击左 / 中 / 右：快退 / 暂停 / 快进
 *  - 长按：临时 2x 播放，松手恢复（不再弹倍速面板）
 *  - 水平滑动：连续 seek（带时间预览，松手生效）
 *  - 垂直滑动：左亮度、右软件音量、中间切换视频（整段手势只切一次）
 *
 * 长按一旦触发就吞掉单击派发并清空双击链，避免与 2x 手势误触。
 */
class GestureLayer(context: Context, private val cb: Callback) : View(context) {

    interface Callback {
        fun onSingleTap()
        fun onDoubleTap(forward: Boolean)
        fun onDoubleTapCenter()
        fun onLongPressBegin()
        fun onLongPressEnd()
        fun onSeekStart()
        fun onSeekDelta(deltaMs: Long)
        fun onSeekCommit()
        fun onBrightnessDelta(delta: Float)
        fun onVolumeDelta(delta: Float)
        fun onSwitchVideo(next: Boolean)
    }

    var msPerPixel: Float = 150f

    private enum class Mode { IDLE, TAP_WAIT, SEEK, VERTICAL, LONG }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L
    private var lastTapZone = Int.MIN_VALUE
    private var verticalZone = 0
    private var verticalAccum = 0f

    private val longPressRunnable = Runnable {
        if (mode == Mode.IDLE) {
            mode = Mode.LONG
            handler.removeCallbacks(singleTapRunnable)
            lastTapTime = 0L
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            cb.onLongPressBegin()
        }
    }

    private val singleTapRunnable = Runnable {
        if (mode == Mode.TAP_WAIT) {
            mode = Mode.IDLE
            cb.onSingleTap()
        }
    }

    /** -1 左 / 0 中 / 1 右 */
    private fun zoneOf(x: Float): Int = when {
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(longPressRunnable)
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                downTime = System.currentTimeMillis()
                verticalZone = zoneOf(event.x)
                verticalAccum = 0f
                mode = Mode.IDLE
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE -> {
                // 长按 2x 期间不响应任何其它手势
                if (mode == Mode.LONG) return true
                if (mode == Mode.IDLE) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) < touchSlop && abs(dy) < touchSlop) return true
                    handler.removeCallbacks(longPressRunnable)
                    mode = if (abs(dx) >= abs(dy) * 1.15f) Mode.SEEK else Mode.VERTICAL
                    if (mode == Mode.SEEK) cb.onSeekStart()
                }
                when (mode) {
                    Mode.SEEK -> {
                        val delta = ((event.x - lastX) * msPerPixel).toLong()
                        if (delta != 0L) cb.onSeekDelta(delta)
                    }
                    Mode.VERTICAL -> {
                        val delta = -(event.y - lastY) / height.coerceAtLeast(1)
                        when (verticalZone) {
                            -1 -> cb.onBrightnessDelta(delta)
                            1 -> cb.onVolumeDelta(delta)
                            else -> verticalAccum += delta
                        }
                    }
                    else -> Unit
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val isUp = event.actionMasked == MotionEvent.ACTION_UP
                when {
                    mode == Mode.LONG -> cb.onLongPressEnd()
                    mode == Mode.SEEK && isUp -> cb.onSeekCommit()
                    mode == Mode.VERTICAL && isUp -> {
                        if (verticalZone == 0 && abs(verticalAccum) > 0.08f) {
                            cb.onSwitchVideo(verticalAccum > 0)
                        }
                    }
                    mode == Mode.IDLE && isUp && isTap(event) -> handleTap(event.x)
                }
                if (mode != Mode.TAP_WAIT) mode = Mode.IDLE
            }
        }
        return true
    }

    private fun isTap(e: MotionEvent): Boolean =
        abs(e.x - downX) < touchSlop &&
            abs(e.y - downY) < touchSlop &&
            System.currentTimeMillis() - downTime < 400

    /** 双击要吞掉单击，所以用 300ms 延迟派发单击；按区域分发双击 */
    private fun handleTap(x: Float) {
        val zone = zoneOf(x)
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 300 && zone == lastTapZone) {
            handler.removeCallbacks(singleTapRunnable)
            lastTapTime = 0L
            mode = Mode.IDLE
            when (zone) {
                -1 -> cb.onDoubleTap(false)
                1 -> cb.onDoubleTap(true)
                else -> cb.onDoubleTapCenter()
            }
        } else {
            lastTapTime = now
            lastTapZone = zone
            mode = Mode.TAP_WAIT
            handler.removeCallbacks(singleTapRunnable)
            handler.postDelayed(singleTapRunnable, 300L)
        }
    }
}
