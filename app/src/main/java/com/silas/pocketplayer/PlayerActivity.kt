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
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
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
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var lockBtn: TextView
    private lateinit var volumeBtn: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var markerView: MarkerView
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var hintView: TextView
    private lateinit var buffering: CircularProgressIndicator
    private val seekStepViews = mutableListOf<TextView>()

    private val uiHandler = Handler(Looper.getMainLooper())

    private var controlsVisible = true
    private var locked = false
    private var seekDragging = false
    private var pendingSeekMs = 0L
    private var seekGestureActive = false
    private var autoAudioRetryDone = false
    private var audioMuted = false

    /** 音量锁：默认静音，必须点右上角解锁；所有音量控制只在软件内生效 */
    private var volumeUnlocked = false
    private var volumeOn = false
    private var softwareVolume = 0.7f

    /** 长按期间的临时 2x，松手恢复常驻倍速 */
    private var tempSpeedActive = false

    private var fastSeek = true
    private var cachedDurationMs = 0L
    private var lastSeekHintMs = 0L
    private var brightness = 0.6f

    private val hideRunnable = Runnable { hideControls() }
    private val hintRunnable = Runnable {
        hintView.animate().alpha(0f).setDuration(180)
            .withEndAction { hintView.visibility = View.GONE }.start()
    }

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
        initBrightness()
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

    /** 音量键完全由本 Activity 消费，绝不修改系统媒体音量 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when {
                        !volumeUnlocked -> showHint("音量已锁定 · 点右上角解锁")
                        event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE -> {
                            volumeOn = !volumeOn
                            applyVolumeState()
                            showHint(if (volumeOn) "音量已恢复" else "已静音")
                        }
                        else -> adjustSoftwareVolume(
                            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.1f else -0.1f
                        )
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- UI（纯代码构建，无 XML 膨胀开销）

    private fun buildUi(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this@PlayerActivity).apply {
            setUseController(false)
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

        val topRight = LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volumeBtn = compactChip("静音 · 解锁") { onVolumeButtonClick() }
        lockBtn = compactChip("锁定") { setLocked(!locked) }
        topRight.addView(
            volumeBtn,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 8.dp(this@PlayerActivity) }
        )
        topRight.addView(lockBtn)
        addView(
            topRight,
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
        setPadding(8.dp(this@PlayerActivity), 16.dp(this@PlayerActivity), 190.dp(this@PlayerActivity), 24.dp(this@PlayerActivity))
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
                    if (fromUser && cachedDurationMs > 0) {
                        timeNow.text = formatTime(cachedDurationMs * p / 1000)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seekDragging = true
                    cancelHide()
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seekDragging = false
                    if (cachedDurationMs > 0) {
                        player.seekTo(cachedDurationMs * (sb?.progress ?: 0) / 1000)
                    }
                    updateTimes()
                    scheduleHide()
                }
            })
        }
        // 标记点覆盖在进度条上，透明可穿透
        val seekFrame = FrameLayout(this@PlayerActivity).apply {
            addView(seekBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            markerView = MarkerView(this@PlayerActivity)
            addView(
                markerView,
                FrameLayout.LayoutParams(MATCH_PARENT, 14.dp(this@PlayerActivity), Gravity.CENTER)
            )
        }
        timeRow.addView(timeNow)
        timeRow.addView(seekFrame, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        timeRow.addView(timeTotal)
        addView(timeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val row = LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 10.dp(this@PlayerActivity), 0, 0)
        }
        fun add(view: View) = row.addView(
            view,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 3.dp(this@PlayerActivity) }
        )

        playBtn = iconButton(android.R.drawable.ic_media_play, "播放") { togglePlay() }
        listOf(-30, -10, 10, 30).forEach { seconds ->
            if (seconds == 10) add(playBtn)
            val button = compactChip(if (seconds > 0) "+${seconds}s" else "${seconds}s") {
                onSeekStepClick(seconds)
            }
            button.tag = seconds
            seekStepViews += button
            add(button)
        }
        add(compactChip("标记") { addMarkAtCurrent() })
        add(compactChip("更多") { showMoreMenu() })
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        highlightSeekStep(prefs.seekStepSec)
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

    private fun compactChip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER
        background = chipBg(0x33FFFFFF)
        setPadding(8.dp(this@PlayerActivity), 8.dp(this@PlayerActivity), 8.dp(this@PlayerActivity), 8.dp(this@PlayerActivity))
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
        val renderers = createRenderersFactory(this)
        val loadControl = DefaultLoadControl.Builder()
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
        player.setSeekParameters(
            if (fastSeek) SeekParameters.CLOSEST_SYNC else SeekParameters.DEFAULT
        )
        player.addListener(playerListener)
        playerView.player = player
        applyVolumeState()

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
                cachedDurationMs = player.duration.coerceAtLeast(0)
                updateTimes()
                updateSeekSensitivity()
                refreshMarkers()
            }
            if (state == Player.STATE_ENDED && !player.hasNextMediaItem()) showControls()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayButton()

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateTitle()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackQueue.index = player.currentMediaItemIndex
            autoAudioRetryDone = false
            cachedDurationMs = 0L
            endTempSpeed()
            if (audioMuted) setAudioDisabled(false)
            updateTitle()
            updateTimes()
            refreshMarkers()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!autoAudioRetryDone &&
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
            ) {
                autoAudioRetryDone = true
                setAudioDisabled(true)
                player.prepare()
                player.playWhenReady = true
                showHint("解码失败，正在忽略音轨重试…")
                return
            }
            handlePlaybackError(error)
        }
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

    private fun duration(): Long =
        if (cachedDurationMs > 0) cachedDurationMs else player.duration.coerceAtLeast(0)

    private fun updateTimes() {
        if (!::player.isInitialized) return
        if (seekDragging || seekGestureActive) return
        val dur = duration()
        val pos = player.currentPosition.coerceAtLeast(0)
        timeTotal.text = formatTime(dur)
        timeNow.text = formatTime(pos)
        seekBar.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        seekBar.secondaryProgress = if (dur > 0) (player.bufferedPosition * 1000 / dur).toInt() else 0
    }

    private fun updateTitle() {
        if (!::player.isInitialized) return
        val item = currentItem()
        val size = player.videoSize
        val resolution = if (size.width > 0) "  ·  ${size.width}×${size.height}" else ""
        val speed = if (tempSpeedActive) "2x 临时" else speedLabel(prefs.speed)
        titleView.text = (item?.title ?: "本地视频") + resolution + "  ·  $speed"
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
            gesture.msPerPixel = (duration() / (resources.displayMetrics.widthPixels * 2f))
                .coerceIn(20f, 600f)
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

    /** sticky = true 时提示常驻（长按 2x 期间），不自动消失 */
    private fun showHint(text: String, sticky: Boolean = false) {
        hintView.text = text
        hintView.alpha = 1f
        hintView.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hintRunnable)
        if (!sticky) uiHandler.postDelayed(hintRunnable, Spec.HINT_DURATION_MS)
    }

    private fun setLocked(value: Boolean) {
        locked = value
        lockBtn.text = if (value) "解锁" else "锁定"
        if (value) {
            endTempSpeed()
            cancelHide()
            hideControls()
            showHint("已锁定，点右上角解锁")
        } else showControls()
    }

    // ---------------------------------------------------------------- 音量锁（纯软件）

    private fun initBrightness() {
        val current = window.attributes.screenBrightness
        brightness = if (current < 0f) 0.6f else current
    }

    private fun applyVolumeState() {
        if (!::player.isInitialized || !::volumeBtn.isInitialized) return
        player.volume = if (volumeUnlocked && volumeOn) softwareVolume else 0f
        volumeBtn.text = when {
            !volumeUnlocked -> "静音 · 解锁"
            volumeOn -> "音量已开"
            else -> "已静音 · 恢复"
        }
    }

    private fun onVolumeButtonClick() {
        if (locked) {
            showHint("请先点右上角解锁画面")
            return
        }
        if (!volumeUnlocked) {
            volumeUnlocked = true
            volumeOn = true
        } else {
            volumeOn = !volumeOn
        }
        applyVolumeState()
        showHint(if (volumeOn) "音量已解锁" else "已静音")
        scheduleHide()
    }

    private fun adjustSoftwareVolume(delta: Float) {
        if (!volumeUnlocked) {
            showHint("音量已锁定 · 点右上角解锁")
            return
        }
        softwareVolume = (softwareVolume + delta).coerceIn(0f, 1f)
        volumeOn = softwareVolume > 0f
        applyVolumeState()
        showHint("软件音量  ${(softwareVolume * 100).toInt()}%")
    }

    private fun changeBrightness(delta: Float) {
        brightness = (brightness + delta).coerceIn(0.02f, 1f)
        window.attributes = window.attributes.also { it.screenBrightness = brightness }
        showHint("亮度  ${(brightness * 100).toInt()}%")
    }

    // ---------------------------------------------------------------- 倍速 / 快进 / 标记

    /** 长按画面：临时 2x，不改变常驻倍速 */
    private fun beginTempSpeed() {
        if (locked) return
        tempSpeedActive = true
        player.setPlaybackParameters(PlaybackParameters(2f))
        showHint("2x 播放中 · 松手恢复", sticky = true)
        updateTitle()
    }

    private fun endTempSpeed() {
        if (!tempSpeedActive) return
        tempSpeedActive = false
        player.setPlaybackParameters(PlaybackParameters(prefs.speed))
        showHint("已恢复 ${speedLabel(prefs.speed)}")
        updateTitle()
    }

    private fun setSpeed(speed: Float) {
        prefs.speed = speed
        if (!tempSpeedActive) player.setPlaybackParameters(PlaybackParameters(speed))
        updateTitle()
        showHint("倍速  ${speedLabel(speed)}")
    }

    private fun showSpeedDialog() {
        val labels = Spec.SPEEDS.map { speedLabel(it) }.toTypedArray()
        val checked = Spec.SPEEDS.indexOfFirst { it == prefs.speed }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("播放倍速 · 长按画面可临时 2x")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                setSpeed(Spec.SPEEDS[which])
                dialog.dismiss()
            }.show()
    }

    private fun onSeekStepClick(seconds: Int) {
        if (locked) {
            showHint("已锁定 · 点右上角解锁")
            return
        }
        prefs.seekStepSec = abs(seconds)
        highlightSeekStep(abs(seconds))
        seekBy(seconds * 1000L)
    }

    private fun highlightSeekStep(seconds: Int) {
        seekStepViews.forEach { view ->
            val selected = (view.tag as? Int)?.let { abs(it) == seconds } == true
            view.background = chipBg(if (selected) 0xFF2E6BE6.toInt() else 0x33FFFFFF)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val dur = duration()
        val target = (player.currentPosition + deltaMs).coerceIn(0L, dur.coerceAtLeast(1))
        player.seekTo(target)
        val sign = if (deltaMs > 0) "+" else "-"
        showHint("$sign${abs(deltaMs) / 1000}s   ${formatTime(target)} / ${formatTime(dur)}")
        scheduleHide()
    }

    private fun addMarkAtCurrent() {
        val item = currentItem() ?: return
        val pos = player.currentPosition
        prefs.addMark(item.path, pos)
        refreshMarkers()
        showHint("已标记 ${formatTime(pos)}")
        scheduleHide()
    }

    private fun refreshMarkers() {
        if (!::markerView.isInitialized || !::player.isInitialized) return
        val item = currentItem()
        markerView.durationMs = duration()
        markerView.marks = item?.let { prefs.marksOf(it.path) } ?: emptyList()
        markerView.refresh()
    }

    private fun showMarksDialog() {
        val item = currentItem() ?: return
        val list = prefs.marksOf(item.path)
        if (list.isEmpty()) {
            showHint("这个视频还没有标记，点底栏「标记」添加")
            return
        }
        val labels = list.map { "跳到 ${formatTime(it)}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("关键节点（${list.size}）")
            .setItems(labels) { _, which ->
                player.seekTo(list[which])
                updateTimes()
            }
            .setNeutralButton("清除全部") { _, _ ->
                prefs.clearMarks(item.path)
                refreshMarkers()
                showHint("标记已清除")
            }.show()
    }

    private fun switchVideo(next: Boolean) {
        val has = if (next) player.hasNextMediaItem() else player.hasPreviousMediaItem()
        if (!has) {
            showHint(if (next) "已经是最后一个" else "已经是第一个")
            return
        }
        endTempSpeed()
        if (next) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
        showHint(if (next) "下一个视频" else "上一个视频")
    }

    private val gestureCallback = object : GestureLayer.Callback {
        override fun onSingleTap() = toggleControls()

        override fun onDoubleTap(forward: Boolean) {
            if (locked) return
            seekBy((if (forward) 1 else -1) * prefs.seekStepSec * 1000L)
        }

        override fun onDoubleTapCenter() {
            if (locked) return
            togglePlay()
        }

        override fun onLongPressBegin() = beginTempSpeed()

        override fun onLongPressEnd() = endTempSpeed()

        override fun onSeekStart() {
            if (locked) return
            pendingSeekMs = player.currentPosition
            seekGestureActive = true
            lastSeekHintMs = 0L
            cancelHide()
        }

        override fun onSeekDelta(deltaMs: Long) {
            if (locked) return
            val dur = duration()
            pendingSeekMs = (pendingSeekMs + deltaMs).coerceIn(0L, dur)
            val now = System.currentTimeMillis()
            if (now - lastSeekHintMs >= 80L) {
                lastSeekHintMs = now
                showHint("${formatTime(pendingSeekMs)} / ${formatTime(dur)}")
                seekBar.progress = if (dur > 0) (pendingSeekMs * 1000 / dur).toInt() else 0
                timeNow.text = formatTime(pendingSeekMs)
            }
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
            if (!locked) adjustSoftwareVolume(delta)
        }

        override fun onSwitchVideo(next: Boolean) {
            if (!locked) switchVideo(next)
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
                highlightSeekStep(Spec.SEEK_STEPS[which])
                dialog.dismiss()
            }.show()
    }

    private fun toggleFastSeek() {
        fastSeek = !fastSeek
        player.setSeekParameters(
            if (fastSeek) SeekParameters.CLOSEST_SYNC else SeekParameters.DEFAULT
        )
        showHint(if (fastSeek) "快速定位（关键帧）" else "精确定位（较慢）")
    }

    private fun showMoreMenu() {
        val labels = arrayOf(
            "播放倍速（当前 ${speedLabel(prefs.speed)}）",
            "标记列表（${currentItem()?.let { prefs.marksOf(it.path).size } ?: 0}）",
            "双击步长（${prefs.seekStepSec}s）",
            if (fastSeek) "改为精确定位（较慢）" else "改为快速定位（推荐）",
            if (volumeUnlocked) "静音 / 恢复音量" else "解锁音量",
            if (audioMuted) "重新启用音轨" else "静音播放（跳过不支持的音轨）",
            if (locked) "解锁画面" else "锁定画面",
            "从头播放",
            "播放列表（${PlaybackQueue.items.size} 项）",
            "转码 / 导出"
        )
        MaterialAlertDialogBuilder(this).setItems(labels) { _, which ->
            when (which) {
                0 -> showSpeedDialog()
                1 -> showMarksDialog()
                2 -> showStepDialog()
                3 -> toggleFastSeek()
                4 -> if (volumeUnlocked) {
                    volumeOn = !volumeOn
                    applyVolumeState()
                } else onVolumeButtonClick()
                5 -> toggleAudioMute()
                6 -> setLocked(!locked)
                7 -> player.seekTo(0L)
                8 -> showPlaylist()
                9 -> showTranscodeMenu(currentItem())
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
            append("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
        }
        val flavorTip = if (BuildConfig.FLAVOR == "full") {
            "当前已是全格式版（FFmpeg 软解已启用）；仍失败说明这条流超出该构建支持的解码器范围。"
        } else {
            "轻量版不含 FFmpeg 软解，改装 Release 里的「全格式版」APK 可直接软解 HEVC 10-bit / 冷门音轨。"
        }
        val advice = if (legacy) {
            "\n\n这个容器的 extractor 系统不认，转码通常也救不了；建议交给带软解的播放器。"
        } else {
            "\n\n常见于 HEVC 10-bit（DJI / HDR 素材）或冷门音轨不被设备解码器支持。$flavorTip"
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

    /** 解码失败时禁掉音频轨重新解码（与「音量锁」是两回事） */
    private fun retryWithoutAudio() {
        setAudioDisabled(true)
        player.prepare()
        player.playWhenReady = true
        showHint("已忽略音轨，重新解码中…")
    }

    private fun setAudioDisabled(disabled: Boolean) {
        audioMuted = disabled
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled)
            .build()
    }

    private fun toggleAudioMute() {
        setAudioDisabled(!audioMuted)
        player.prepare()
        player.playWhenReady = true
        showHint(if (audioMuted) "已忽略音轨" else "已恢复音轨")
    }

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
        endTempSpeed()
        if (::player.isInitialized && player.isPlaying) player.pause()
        super.onPause()
    }

    override fun onDestroy() {
        saveProgress()
        endTempSpeed()
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
