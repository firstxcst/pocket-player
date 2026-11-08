package com.silas.pocketplayer

/** 倍速显示文本：1.0 -> "1x"，1.25 -> "1.25x" */
internal fun speedLabel(v: Float): String {
    val num = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    return "${num}x"
}
