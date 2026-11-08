@file:Suppress("UnsafeOptInUsageError", "DEPRECATION")

package com.silas.pocketplayer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var gesture: GestureLayer
    private lateinit var speedPicker: SpeedPicker
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var lockBtn: TextView
    private lateinit var stepBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var hintView: TextView
    private lateinit var buffering: CircularProgressIndicator

    private val uiHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var controlsVisible = true
    private var locked = false
    private var seekDragging = false
    private var pendingSeekMs = 0L
    private var seekGestureActive = false
    private var brightness = 0.6f
    private var volumeFraction = 0.5f

    private val hideRunnable = Runnable { hideControls() }
    private val hintRunnable = Runnable {
        hintView.animate().alpha(0f).setDuration(180)
            .withEndAction { hintView.visibility = View.GONE }.start()
    }
    private val speedAutoHideRunnable = Runnable { speedPicker.hide() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContentView(buildUi())
        initBrightnessVolume()
        initPlayer()
        startTicker()
        showControls()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) {
                    setLocked(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ---------------------------------------------------------------- UI（纯代码构建，无 XML 膨胀开销）

    private fun buildUi(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this@PlayerActivity).apply {
            setUseController(false)         // 控制器自己写，交互才能完全按 MX/VLC 那套来
            setBackgroundColor(Color.BLACK)
        }
        addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        gesture = GestureLayer(this@PlayerActivity, gestureCallback)
        addView(gesture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        hintView = TextView(this@PlayerActivity).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = chipBg(0xB3000000.toInt(), 16.dp(this@PlayerActivity).toFloat())
            setPadding(20.dp(this@PlayerActivity), 12.dp(this@PlayerActivity), 20.dp(this@PlayerActivity), 12.dp(this@PlayerActivity))
            visibility = View.GONE
        }
        addView(
            hintView,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP)
                .apply { topMargin = 140.dp(this@PlayerActivity) }
        )

        buffering = CircularProgressIndicator(this@PlayerActivity).apply {
            setIndeterminate(true)
            visibility = View.GONE
        }
        addView(buffering, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        topBar = buildTopBar()
        addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        bottomBar = buildBottomBar()
        addView(bottomBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        speedPicker = SpeedPicker(this@PlayerActivity) { speed ->
            setSpeed(speed)
            speedPicker.hide()
            scheduleHide()
        }
        addView(
            speedPicker.view,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply {
                    topMargin = 96.dp(this@PlayerActivity)
                    leftMargin = 24.dp(this@PlayerActivity)
                    rightMargin = 24.dp(this@PlayerActivity)
                }
        )

        lockBtn = TextView(this@PlayerActivity).apply {
            text = "锁定"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = chipBg(0x66000000)
            setPadding(12.dp(this@PlayerActivity), 8.dp(this@PlayerActivity), 12.dp(this@PlayerActivity), 8.dp(this@PlayerActivity))
            setOnClickListener { setLocked(!locked) }
        }
        addView(
            lockBtn,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = 16.dp(this@PlayerActivity)
                marginEnd = 16.dp(this@PlayerActivity)
            }
        )
    }

    private fun buildTopBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xDD000000.toInt(), 0x00000000)
        )
        setPadding(8.dp(this@PlayerActivity), 16.dp(this@PlayerActivity), 88.dp(this@PlayerActivity), 24.dp(this@PlayerActivity))
        addView(iconButton(android.R.drawable.ic_menu_revert, "返回") { finish() })
        titleView = TextView(this@PlayerActivity).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = 8.dp(this@PlayerActivity)
            }
        }
        addView(titleView)
    }

    private fun buildBottomBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE6000000.toInt(), 0x00000000)
        )
        setPadding(20.dp(this@PlayerActivity), 48.dp(this@PlayerActivity), 20.dp(this@PlayerActivity), 16.dp(this@PlayerActivity))

        val timeRow = LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeNow = TextView(this@PlayerActivity).apply {
            text = "00:00"; setTextColor(Color.WHITE); textSize = 12f
        }
        timeTotal = TextView(this@PlayerActivity).apply {
            text = "00:00"; setTextColor(Color.WHITE); textSize = 12f
        }
        seekBar = SeekBar(this@PlayerActivity).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFF4C8BF5.toInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && player.duration > 0) timeNow.text = formatTime(player.duration * p / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seekDragging = true
                    cancelHide()
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seekDragging = false
                    val dur = player.duration
                    if (dur > 0) player.seekTo(dur * (sb?.progress ?: 0) / 1000)
                    updateTimes()
                    scheduleHide()
                }
            })
        }
        timeRow.addView(timeNow)
        timeRow.addView(seekBar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        timeRow.addView(timeTotal)
        addView(timeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val row = LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 10.dp(this@PlayerActivity), 0, 0)
        }
        fun add(view: View) = row.addView(
            view,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 6.dp(this@PlayerActivity) }
        )
        stepBtn = textButton("${prefs.seekStepSec}s") { showStepDialog() }
        speedBtn = textButton(speedLabel(prefs.speed)) { openSpeedPicker() }
        add(stepBtn)
        add(iconButton(android.R.drawable.ic_media_rew, "快退") { seekBy(-prefs.seekStepSec * 1000L) })
        playBtn = iconButton(android.R.drawable.ic_media_play, "播放") { togglePlay() }
        add(playBtn)
        add(iconButton(android.R.drawable.ic_media_ff, "快进") { seekBy(prefs.seekStepSec * 1000L) })
        add(speedBtn)
        add(textButton("更多") { showMoreMenu() })
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = desc
            setBackgroundResource(selectableBg())
            val size = 44.dp(this@PlayerActivity)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }

    private fun textButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        minWidth = 52.dp(this@PlayerActivity)
        background = chipBg(0x33FFFFFF)
        setPadding(10.dp(this@PlayerActivity), 8.dp(this@PlayerActivity), 10.dp(this@PlayerActivity), 8.dp(this@PlayerActivity))
        setOnClickListener { onClick() }
    }

    private fun chipBg(color: Int, radius: Float = 999f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun selectableBg(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return tv.resourceId
    }

    // ---------------------------------------------------------------- 播放器

    private fun initPlayer() {
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)   // 主解码器初始化失败时自动换备用解码器
        val loadControl = DefaultLoadControl.Builder()
            // 本地文件不需要网络播放那种大缓冲：起播更快、内存更省
            .setBufferDurationsMs(1500, 5000, 500, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(prefs.seekStepSec * 1000L)
            .setSeekForwardIncrementMs(prefs.seekStepSec * 1000L)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        player.setPlaybackSpeed(prefs.speed)
        player.addListener(playerListener)
        playerView.player = player

        val queue = PlaybackQueue.items
        if (queue.isEmpty()) {
            finish()
            return
        }
        val start = PlaybackQueue.index
        val items = queue.map { MediaItem.Builder().setUri(it.uri).setMediaId(it.path).build() }
        player.setMediaItems(items, start, prefs.positionOf(queue[start].path))
        player.prepare()
        player.playWhenReady = true
        updateTitle()
        updateSeekSensitivity()
        updatePlayButton()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                updateTimes()
                updateSeekSensitivity()
            }
            if (state == Player.STATE_ENDED && !player.hasNextMediaItem()) showControls()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayButton()

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateTitle()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackQueue.index = player.currentMediaItemIndex
            updateTitle()
            updateTimes()
        }

        override fun onPlayerError(error: PlaybackException) = handlePlaybackError(error)
    }

    private fun startTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    updateTimes()
                    delay(250)
                }
            }
        }
    }

    private fun updateTimes() {
        if (!::player.isInitialized) return
        val dur = player.duration.coerceAtLeast(0)
        val pos = player.currentPosition.coerceAtLeast(0)
        timeTotal.text = formatTime(dur)
        if (!seekDragging && !seekGestureActive) {
            timeNow.text = formatTime(pos)
            seekBar.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
            seekBar.secondaryProgress = if (dur > 0) (player.bufferedPosition * 1000 / dur).toInt() else 0
        }
    }

    private fun updateTitle() {
        if (!::player.isInitialized) return
        val item = currentItem()
        val size = player.videoSize
        val resolution = if (size.width > 0) "  ·  ${size.width}×${size.height}" else ""
        titleView.text = (item?.title ?: "本地视频") + resolution
    }

    private fun updatePlayButton() {
        playBtn.setImageResource(
            if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        playBtn.setColorFilter(Color.WHITE)
        playBtn.contentDescription = if (player.isPlaying) "暂停" else "播放"
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        scheduleHide()
    }

    private fun currentItem(): VideoItem? =
        PlaybackQueue.items.getOrNull(player.currentMediaItemIndex)

    private fun updateSeekSensitivity() {
        gesture.post {
            gesture.msPerPixel = (
                player.duration.coerceAtLeast(0) /
                    (resources.displayMetrics.widthPixels * 2f)
                ).coerceIn(20f, 600f)
        }
    }

    // ---------------------------------------------------------------- 控制条 / 提示

    private fun cancelHide() = uiHandler.removeCallbacks(hideRunnable)
    private fun scheduleHide() {
        cancelHide()
        uiHandler.postDelayed(hideRunnable, Spec.CONTROLS_AUTO_HIDE_MS)
    }

    private fun showControls() {
        if (locked) return
        controlsVisible = true
        topBar.alpha = 1f
        bottomBar.alpha = 1f
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.animate().alpha(0f).setDuration(150)
            .withEndAction { topBar.visibility = View.GONE }.start()
        bottomBar.animate().alpha(0f).setDuration(150)
            .withEndAction { bottomBar.visibility = View.GONE }.start()
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun toggleControls() {
        if (locked) {
            showHint("已锁定 · 点右上角解锁")
            return
        }
        if (controlsVisible) {
            cancelHide()
            hideControls()
        } else showControls()
    }

    private fun showHint(text: String) {
        hintView.text = text
        hintView.alpha = 1f
        hintView.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hintRunnable)
        uiHandler.postDelayed(hintRunnable, Spec.HINT_DURATION_MS)
    }

    private fun setLocked(value: Boolean) {
        locked = value
        lockBtn.text = if (value) "解锁" else "锁定"
        if (value) {
            cancelHide()
            speedPicker.hide()
            hideControls()
            showHint("已锁定，点右上角解锁")
        } else showControls()
    }

    // ---------------------------------------------------------------- 快进 / 倍速 / 亮度音量

    private fun seekBy(deltaMs: Long) {
        val dur = player.duration.coerceAtLeast(0)
        val target = (player.currentPosition + deltaMs).coerceIn(0L, dur.coerceAtLeast(1))
        player.seekTo(target)
        val sign = if (deltaMs > 0) "+" else "-"
        showHint("$sign${abs(deltaMs) / 1000}s   ${formatTime(target)} / ${formatTime(dur)}")
        scheduleHide()
    }

    private fun setSpeed(speed: Float) {
        player.setPlaybackParameters(PlaybackParameters(speed))
        prefs.speed = speed
        speedBtn.text = speedLabel(speed)
        showHint("倍速  ${speedLabel(speed)}")
    }

    private fun openSpeedPicker() {
        cancelHide()
        speedPicker.show(prefs.speed)
        scheduleSpeedAutoHide()
    }

    private fun scheduleSpeedAutoHide() {
        uiHandler.removeCallbacks(speedAutoHideRunnable)
        uiHandler.postDelayed(speedAutoHideRunnable, Spec.SPEED_PICKER_AUTO_HIDE_MS)
    }

    private fun initBrightnessVolume() {
        val current = window.attributes.screenBrightness
        brightness = if (current < 0f) 0.6f else current
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeFraction = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun changeBrightness(delta: Float) {
        brightness = (brightness + delta).coerceIn(0.02f, 1f)
        window.attributes = window.attributes.also { it.screenBrightness = brightness }
        showHint("亮度  ${(brightness * 100).toInt()}%")
    }

    private fun changeVolume(delta: Float) {
        volumeFraction = (volumeFraction + delta).coerceIn(0f, 1f)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (volumeFraction * max).toInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        showHint("音量  $index / $max")
    }

    private val gestureCallback = object : GestureLayer.Callback {
        override fun onSingleTap() {
            if (speedPicker.isShowing) {
                speedPicker.hide()
                return
            }
            toggleControls()
        }

        override fun onDoubleTap(forward: Boolean) {
            if (locked) return
            seekBy((if (forward) 1 else -1) * prefs.seekStepSec * 1000L)
        }

        override fun onLongPressBegin(x: Float, y: Float) {
            if (locked) return
            cancelHide()
            speedPicker.show(prefs.speed)
        }

        override fun onLongPressMove(x: Float, y: Float) = speedPicker.highlightAt(x, y)

        override fun onLongPressEnd() {
            if (!speedPicker.isShowing) return
            if (speedPicker.finishByGesture()) scheduleHide() else scheduleSpeedAutoHide()
        }

        override fun onSeekStart() {
            if (locked) return
            pendingSeekMs = player.currentPosition
            seekGestureActive = true
            cancelHide()
        }

        override fun onSeekDelta(deltaMs: Long) {
            if (locked) return
            val dur = player.duration.coerceAtLeast(0)
            pendingSeekMs = (pendingSeekMs + deltaMs).coerceIn(0L, dur)
            showHint("${formatTime(pendingSeekMs)} / ${formatTime(dur)}")
            seekBar.progress = if (dur > 0) (pendingSeekMs * 1000 / dur).toInt() else 0
            timeNow.text = formatTime(pendingSeekMs)
        }

        override fun onSeekCommit() {
            if (locked) return
            player.seekTo(pendingSeekMs)
            pendingSeekMs = 0L
            seekGestureActive = false
            updateTimes()
            scheduleHide()
        }

        override fun onBrightnessDelta(delta: Float) {
            if (!locked) changeBrightness(delta)
        }

        override fun onVolumeDelta(delta: Float) {
            if (!locked) changeVolume(delta)
        }
    }

    // ---------------------------------------------------------------- 菜单 / 错误 / 转码

    private fun showStepDialog() {
        val labels = Spec.SEEK_STEPS.map { "$it 秒" }.toTypedArray()
        val checked = Spec.SEEK_STEPS.indexOf(prefs.seekStepSec).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("双击快进 / 快退步长")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.seekStepSec = Spec.SEEK_STEPS[which]
                stepBtn.text = "${Spec.SEEK_STEPS[which]}s"
                dialog.dismiss()
            }.show()
    }

    private fun showMoreMenu() {
        val labels = arrayOf(
            "转码 / 导出",
            "快进步长",
            if (locked) "解锁画面" else "锁定画面",
            "从头播放",
            "播放列表（${PlaybackQueue.items.size} 项）"
        )
        MaterialAlertDialogBuilder(this).setItems(labels) { _, which ->
            when (which) {
                0 -> showTranscodeMenu(currentItem())
                1 -> showStepDialog()
                2 -> setLocked(!locked)
                3 -> player.seekTo(0L)
                4 -> showPlaylist()
            }
        }.show()
    }

    private fun showPlaylist() {
        val items = PlaybackQueue.items
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("播放列表")
            .setItems(items.map { it.title }.toTypedArray()) { _, which ->
                player.seekTo(which, 0L)
                updateTitle()
            }.show()
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val item = currentItem()
        val ext = item?.path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val legacy = ext in setOf("rm", "rmvb", "wmv", "asf", "vob", "avi", "flv")
        val size = player.videoSize
        val resolution = if (size.width > 0) "  ·  ${size.width}×${size.height}" else ""
        val cause = error.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "未提供"
        val diagnostic = buildString {
            append("文件：${item?.title ?: "未知"}\n")
            append("格式：${item?.mimeType ?: ext.ifBlank { "未知" }}$resolution\n")
            append("错误：${error.errorCodeName} (${error.errorCode})\n")
            append("原因：$cause\n")
            append("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
        val advice = if (legacy) {
            "\n\n这个容器的 extractor 系统不认，转码通常也救不了；建议交给带软解的播放器，或开启 FFmpeg 扩展。"
        } else {
            "\n\n最常见的原因是音轨编码（AC3 / DTS 等）设备不支持，视频轨本身没问题——先试「忽略音轨重试」，最快。"
        }
        val actions = arrayOf(
            "忽略音轨重试",
            "用其他播放器打开",
            "转码为兼容 MP4",
            "复制诊断信息",
            "播放下一个",
            "关闭"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("无法解码")
            .setMessage(diagnostic + advice)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> retryWithoutAudio()
                    1 -> openWithExternalPlayer(item)
                    2 -> showTranscodeMenu(item)
                    3 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("PocketPlayer 诊断", diagnostic))
                        showHint("诊断信息已复制")
                    }
                    4 -> if (player.hasNextMediaItem()) player.seekToNextMediaItem() else finish()
                }
            }.show()
    }

    /** 多数「解码失败」其实是音轨编码不认，禁掉音频轨直接重解码 */
    private fun retryWithoutAudio() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.prepare()
        player.playWhenReady = true
        showHint("已忽略音轨，重新解码中…")
    }

    /** 交给系统 / 第三方播放器（MX Player、VLC 等带软解的） */
    private fun openWithExternalPlayer(item: VideoItem?) {
        if (item == null) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType?.takeIf { it.startsWith("video/") } ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { showHint("没有能打开该视频的播放器，或读权限未授予") }
    }

    private fun showTranscodeMenu(item: VideoItem?) {
        if (item == null) return
        val presets = TranscodeHelper.Preset.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("转码 / 导出")
            .setItems(presets.map { it.label }.toTypedArray()) { _, which ->
                runTranscode(item, presets[which])
            }.show()
    }

    private fun runTranscode(item: VideoItem, preset: TranscodeHelper.Preset) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("正在转码")
            .setMessage("0%  ·  ${preset.label}")
            .setNegativeButton("后台继续", null)
            .setCancelable(false)
            .show()
        TranscodeHelper.start(this, item.uri, item.title, preset, object : TranscodeHelper.Callback {
            override fun onProgress(percent: Int) {
                if (!isFinishing && !isDestroyed) dialog.setMessage("$percent%  ·  ${preset.label}")
            }

            override fun onSuccess(output: File) {
                dialog.dismiss()
                MediaScannerConnection.scanFile(
                    this@PlayerActivity,
                    arrayOf(output.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                MaterialAlertDialogBuilder(this@PlayerActivity)
                    .setTitle("完成")
                    .setMessage("已导出到：\n${output.absolutePath}")
                    .setPositiveButton("立即播放") { _, _ ->
                        val converted = VideoItem(
                            uri = Uri.fromFile(output),
                            path = output.absolutePath,
                            title = output.name,
                            sizeBytes = output.length()
                        )
                        PlaybackQueue.set(listOf(converted), 0)
                        player.setMediaItem(MediaItem.fromUri(converted.uri))
                        player.prepare()
                        player.play()
                        updateTitle()
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }

            override fun onError(message: String) {
                dialog.dismiss()
                MaterialAlertDialogBuilder(this@PlayerActivity)
                    .setTitle("转码失败")
                    .setMessage(message)
                    .setPositiveButton("好", null)
                    .show()
            }
        })
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSeekSensitivity()
    }

    override fun onPause() {
        saveProgress()
        if (::player.isInitialized && player.isPlaying) player.pause()
        super.onPause()
    }

    override fun onDestroy() {
        saveProgress()
        uiHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player.release()
        super.onDestroy()
    }

    private fun saveProgress() {
        if (!::player.isInitialized) return
        val item = currentItem() ?: return
        if (player.duration > 0 && player.currentPosition > 1000) {
            prefs.savePosition(item.path, player.currentPosition)
        }
    }
}
