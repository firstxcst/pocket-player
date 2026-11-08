package com.silas.pocketplayer

import android.content.Context
import kotlin.math.abs

object Spec {
    /** 双击快进/快退可选档位（秒） */
    val SEEK_STEPS = intArrayOf(10, 20, 30, 60)

    /** 长按弹出的快速倍速档位，覆盖 2x / 3x 等高频需求 */
    val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

    const val DEFAULT_STEP_SEC = 10
    const val DEFAULT_SPEED = 1f
    const val CONTROLS_AUTO_HIDE_MS = 3200L
    const val HINT_DURATION_MS = 900L
    const val SPEED_PICKER_AUTO_HIDE_MS = 3500L
}

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("pocket_player", Context.MODE_PRIVATE)

    var seekStepSec: Int
        get() = sp.getInt("seek_step", Spec.DEFAULT_STEP_SEC)
        set(v) { sp.edit().putInt("seek_step", v).apply() }

    var speed: Float
        get() = sp.getFloat("speed", Spec.DEFAULT_SPEED)
        set(v) { sp.edit().putFloat("speed", v).apply() }

    /** 0=最近添加 1=名称 2=大小 3=时长 */
    var sortMode: Int
        get() = sp.getInt("sort", 0)
        set(v) { sp.edit().putInt("sort", v).apply() }

    var allFilesAsked: Boolean
        get() = sp.getBoolean("all_files_asked", false)
        set(v) { sp.edit().putBoolean("all_files_asked", v).apply() }

    /** 记住横屏偏好，下次打开播放页直接横屏 */
    var landscapePreferred: Boolean
        get() = sp.getBoolean("landscape", false)
        set(v) { sp.edit().putBoolean("landscape", v).apply() }

    /** 启动时静默检查更新的节流，6 小时内不重复请求 GitHub */
    var lastUpdateCheckMs: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) { sp.edit().putLong("last_update_check", v).apply() }

    /** 每个文件独立记住断点，key 用路径 hash，避免超长 SharedPreferences 键 */
    fun positionOf(path: String): Long = sp.getLong("pos_${path.hashCode()}", 0L)
    fun savePosition(path: String, ms: Long) {
        sp.edit().putLong("pos_${path.hashCode()}", ms).apply()
    }

    /** 关键节点标记：按视频路径存毫秒列表，逗号分隔，不引数据库 */
    fun marksOf(path: String): List<Long> =
        sp.getString("marks_${path.hashCode()}", "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .sorted()

    fun addMark(path: String, ms: Long) {
        val list = marksOf(path).toMutableList()
        if (list.any { abs(it - ms) < 1500L }) return   // 1.5 秒内不重复打点
        list += ms
        sp.edit().putString("marks_${path.hashCode()}", list.sorted().joinToString(",")).apply()
    }

    fun clearMarks(path: String) {
        sp.edit().remove("marks_${path.hashCode()}").apply()
    }
}
