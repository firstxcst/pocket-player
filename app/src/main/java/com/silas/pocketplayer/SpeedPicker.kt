package com.silas.pocketplayer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

internal fun speedLabel(v: Float): String {
    val num = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    return "${num}x"
}

/**
 * 长按调出的快速倍速面板：2 行 × 5 档。
 * 手指不抬起横向/纵向滑动即可高亮，松手直接生效（MX Player 那套「一指选档」手感）；
 * 没滑动就保留面板，随后点击任一档位也可以。
 */
class SpeedPicker(ctx: Context, private val onPick: (Float) -> Unit) {

    val view: LinearLayout
    private val chips = ArrayList<TextView>(Spec.SPEEDS.size)
    private val location = IntArray(2)
    private var selected = -1
    private var current = 1f
    private var touched = false

    init {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE619202C.toInt())
                cornerRadius = 18.dp(ctx).toFloat()
            }
            setPadding(10.dp(ctx), 10.dp(ctx), 10.dp(ctx), 10.dp(ctx))
            elevation = 12f
            visibility = View.GONE
        }
        repeat(2) { row ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(5) { col ->
                val index = row * 5 + col
                val chip = TextView(ctx).apply {
                    text = speedLabel(Spec.SPEEDS[index])
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, 12.dp(ctx), 0, 12.dp(ctx))
                    setOnClickListener { onPick(Spec.SPEEDS[index]) }
                }
                chips += chip
                line.addView(chip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            root.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        view = root
    }

    val isShowing: Boolean get() = view.visibility == View.VISIBLE

    fun show(currentSpeed: Float) {
        current = currentSpeed
        selected = Spec.SPEEDS.indexOfFirst { it == currentSpeed }.coerceAtLeast(0)
        touched = false
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(120).start()
        render()
    }

    /** x/y 来自全屏手势层，换算到 2×5 网格 */
    fun highlightAt(x: Float, y: Float) {
        if (!isShowing) return
        view.getLocationOnScreen(location)
        val innerW = (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(1)
        val innerH = (view.height - view.paddingTop - view.paddingBottom).coerceAtLeast(1)
        val col = ((x - location[0] - view.paddingLeft) / (innerW / 5f)).toInt().coerceIn(0, 4)
        val row = if (y - location[1] - view.paddingTop > innerH / 2f) 1 else 0
        val index = row * 5 + col
        if (index !in Spec.SPEEDS.indices) return
        touched = true
        if (index != selected) {
            selected = index
            render()
        }
    }

    /** @return true = 滑动选档已生效并关闭面板；false = 保留面板等点击 */
    fun finishByGesture(): Boolean {
        if (!touched || selected !in Spec.SPEEDS.indices) return false
        val picked = Spec.SPEEDS[selected]
        hide()
        onPick(picked)
        return true
    }

    fun hide() {
        view.animate().cancel()
        view.visibility = View.GONE
    }

    private fun render() {
        chips.forEachIndexed { i, chip ->
            val isSelected = i == selected
            val isCurrent = Spec.SPEEDS[i] == current
            chip.background = GradientDrawable().apply {
                cornerRadius = 12.dp(chip.context).toFloat()
                setColor(
                    when {
                        isSelected -> 0xFF2E6BE6.toInt()
                        isCurrent -> 0x33FFFFFF
                        else -> Color.TRANSPARENT
                    }
                )
            }
            chip.setTextColor(
                when {
                    isSelected -> Color.WHITE
                    isCurrent -> 0xFF9DC0FF.toInt()
                    else -> 0xCCFFFFFF.toInt()
                }
            )
        }
    }
}
