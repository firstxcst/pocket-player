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
 * 全屏透明手势层（参考 MX Player / VLC 的手感）：
 *  - 单击：显隐控制条
 *  - 双击左/右半屏：按设定档位快退/快进
 *  - 长按：调出快速倍速选择，手指不抬起横向滑动即可选档
 *  - 水平滑动：连续 seek（带时间预览，松手生效）
 *  - 左半屏上下滑：亮度；右半屏上下滑：音量
 *
 * 它只负责「识别」，所有副作用由 Callback 交给 PlayerActivity，方便单独测试/替换。
 */
class GestureLayer(context: Context, private val cb: Callback) : View(context) {

    interface Callback {
        fun onSingleTap()
        fun onDoubleTap(forward: Boolean)
        fun onLongPressBegin(x: Float, y: Float)
        fun onLongPressMove(x: Float, y: Float)
        fun onLongPressEnd()
        fun onSeekStart()
        fun onSeekDelta(deltaMs: Long)
        fun onSeekCommit()
        fun onBrightnessDelta(delta: Float)
        fun onVolumeDelta(delta: Float)
    }

    /** 每像素对应毫秒数，由 Activity 按片长动态设置（滑两屏宽约走完整片） */
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
    private var lastTapForward = false

    private val longPressRunnable = Runnable {
        if (mode == Mode.IDLE) {
            mode = Mode.LONG
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            cb.onLongPressBegin(downX, downY)
        }
    }

    private val singleTapRunnable = Runnable {
        if (mode == Mode.TAP_WAIT) {
            mode = Mode.IDLE
            cb.onSingleTap()
        }
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
                mode = Mode.IDLE
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.LONG) {
                    cb.onLongPressMove(event.x, event.y)
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                if (mode == Mode.IDLE) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) < touchSlop && abs(dy) < touchSlop) return true
                    handler.removeCallbacks(longPressRunnable)
                    if (abs(dx) >= abs(dy) * 1.15f) {
                        mode = Mode.SEEK
                        cb.onSeekStart()
                    } else {
                        mode = Mode.VERTICAL
                    }
                }
                when (mode) {
                    Mode.SEEK -> {
                        val delta = ((event.x - lastX) * msPerPixel).toLong()
                        if (delta != 0L) cb.onSeekDelta(delta)
                    }
                    Mode.VERTICAL -> {
                        val delta = -(event.y - lastY) / height.coerceAtLeast(1)
                        if (downX < width / 2f) cb.onBrightnessDelta(delta) else cb.onVolumeDelta(delta)
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
                    mode == Mode.VERTICAL -> Unit
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

    /** 双击要吞掉单击，所以用 300ms 延迟派发单击 */
    private fun handleTap(x: Float) {
        val forward = x >= width / 2f
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 300 && forward == lastTapForward) {
            handler.removeCallbacks(singleTapRunnable)
            lastTapTime = 0L
            mode = Mode.IDLE
            cb.onDoubleTap(forward)
        } else {
            lastTapTime = now
            lastTapForward = forward
            mode = Mode.TAP_WAIT
            handler.removeCallbacks(singleTapRunnable)
            handler.postDelayed(singleTapRunnable, 300L)
        }
    }
}
