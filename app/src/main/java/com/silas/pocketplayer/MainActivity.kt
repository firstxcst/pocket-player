package com.silas.pocketplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: VideoAdapter
    private lateinit var progress: LinearProgressIndicator
    private lateinit var emptyView: TextView
    private lateinit var toolbar: MaterialToolbar

    private var all: List<VideoItem> = emptyList()
    private var query = ""
    private var scanning = false
    private var scanJob: Job? = null

    /** null = 全部视频；否则只显示指定可移除卷（OTG / SD 卡） */
    private var activeVolume: String? = null
    private var volumeLabels: Map<String, String> = emptyMap()

    private val storageWatcher by lazy {
        StorageWatcher(this) {
            toast("检测到存储设备变化，正在重新扫描")
            startScan()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) afterPermission() else showPermissionDenied()
        }

    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            prefs.allFilesAsked = true
            startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 固定深色电影主题，不启用系统动态取色，保持奈飞式红黑配色
        prefs = Prefs(this)
        setContentView(buildUi())
        storageWatcher.register()
        ensurePermission()
        checkUpdates(manual = false)   // 启动静默检查，没新版本不打扰
    }

    override fun onDestroy() {
        storageWatcher.unregister()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.app_name)
            setTitleTextColor(Color.WHITE)
            setBackgroundColor(0xFF0B0B0B.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        toolbar.menu.add(0, MENU_SEARCH, 0, "搜索").apply {
            setIcon(android.R.drawable.ic_menu_search)
            setShowAsActionFlags(
                MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW or MenuItem.SHOW_AS_ACTION_IF_ROOM
            )
            actionView = SearchView(this@MainActivity).apply {
                queryHint = "按文件名搜索"
                setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(q: String?) = true
                    override fun onQueryTextChange(q: String?): Boolean {
                        // SearchView 自身有个 private query，必须显式指向外部属性
                        this@MainActivity.query = q.orEmpty().trim()
                        applyFilter()
                        return true
                    }
                })
            }
        }
        // OTG 常驻入口
        toolbar.menu.add(0, MENU_OTG, 1, "OTG")
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, MENU_STEP, 2, "快进步长").setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_SORT, 3, "排序").setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_RESCAN, 4, "重新扫描").setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_UPDATE, 5, "检查更新").setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OTG -> showOtgDialog()
                MENU_STEP -> showStepDialog()
                MENU_SORT -> showSortDialog()
                MENU_RESCAN -> startScan()
                MENU_UPDATE -> checkUpdates(manual = true)
            }
            true
        }

        progress = LinearProgressIndicator(this).apply {
            setIndeterminate(true)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 3.dp(this@MainActivity))
        }

        adapter = VideoAdapter { item -> openPlayer(item) }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            clipToPadding = false
        }
        emptyView = TextView(this).apply {
            text = "正在扫描本机视频…"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(32.dp(this@MainActivity), 0, 32.dp(this@MainActivity), 0)
        }
        val content = FrameLayout(this).apply {
            addView(recycler, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                emptyView,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            )
        }

        root.addView(toolbar)
        root.addView(progress)
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun openPlayer(item: VideoItem) {
        val list = adapter.snapshot()
        val index = list.indexOfFirst { it.key == item.key }
        if (index < 0) return
        PlaybackQueue.set(list, index)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun applyFilter() {
        val byVolume = activeVolume?.let { vol -> all.filter { it.volumeName == vol } } ?: all
        val base = if (query.isEmpty()) byVolume else byVolume.filter {
            it.title.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true)
        }
        val sorted = when (prefs.sortMode) {
            1 -> base.sortedBy { it.title.lowercase() }
            2 -> base.sortedByDescending { it.sizeBytes }
            3 -> base.sortedByDescending { it.durationMs }
            else -> base.sortedByDescending { it.dateAddedSec }
        }
        adapter.submit(sorted)
        emptyView.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = when {
            scanning -> "正在扫描本机视频…"
            all.isEmpty() -> "没找到视频文件。把片子放进手机存储，或插上 OTG U 盘再点 OTG"
            activeVolume != null -> "这个外置卷里没有匹配的视频"
            else -> "没有匹配的视频"
        }
    }

    private fun updateSubtitle() {
        val vol = activeVolume
        toolbar.setSubtitle(vol?.let { "OTG · ${volumeLabels[vol] ?: vol}（点 OTG 切回全部）" })
    }

    // ---------------------------------------------------------------- OTG 入口

    private fun showOtgDialog() {
        lifecycleScope.launch {
            val volumes = withContext(Dispatchers.IO) {
                runCatching { VideoScanner.volumeSummaries(this@MainActivity) }.getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed) return@launch
            volumeLabels = volumes.associate { it.name to it.label }
            val usb = storageWatcher.usbDeviceCount()
            val labels = buildList {
                add("全部视频（清除过滤）")
                volumes.forEach { add("${it.label} · ${it.videoCount} 个视频") }
                if (volumes.isEmpty()) {
                    add("未检测到 OTG / SD 卡 · 点此重新扫描")
                }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (usb > 0) "OTG / 外置存储（USB 设备 $usb 个）" else "OTG / 外置存储"
                )
                .setItems(labels.toTypedArray()) { _, which ->
                    when {
                        which == 0 -> {
                            activeVolume = null
                            updateSubtitle()
                            applyFilter()
                        }
                        volumes.isEmpty() -> startScan()
                        else -> {
                            activeVolume = volumes[which - 1].name
                            updateSubtitle()
                            applyFilter()
                            toast("已过滤：${volumes[which - 1].label}")
                        }
                    }
                }.show()
        }
    }

    // ---------------------------------------------------------------- 版本更新

    private fun checkUpdates(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.lastUpdateCheckMs < 6 * 60 * 60 * 1000L) return
        prefs.lastUpdateCheckMs = now
        lifecycleScope.launch {
            val release = runCatching { UpdateChecker.latest(BuildConfig.FLAVOR) }.getOrNull()
            if (isFinishing || isDestroyed) return@launch
            if (release == null) {
                if (manual) toast("检查失败：无法连接 GitHub，请检查网络")
                return@launch
            }
            if (!UpdateChecker.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                if (manual) toast("已是最新版本 ${BuildConfig.VERSION_NAME}")
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("发现新版本 ${release.tag}")
                .setMessage(
                    buildString {
                        append(release.name)
                        if (release.notes.isNotBlank()) {
                            append('\n').append(release.notes.take(240))
                        }
                    }
                )
                .setPositiveButton("去下载") { _, _ ->
                    val target = release.apkUrl ?: release.pageUrl
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                        .onFailure { toast("没有可用浏览器打开下载页") }
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- 权限与扫描

    private fun ensurePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ||
            // Android 14+「仅允许访问部分照片和视频」也要能用，剩下交给深度扫描补
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED)
        if (granted) afterPermission() else permissionLauncher.launch(arrayOf(permission))
    }

    private fun afterPermission() {
        val needAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager() && !prefs.allFilesAsked
        if (!needAllFiles) {
            startScan()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("深度扫描")
            .setMessage(
                "系统媒体库会漏掉隐藏目录、OTG U 盘和未被索引的文件。开启「所有文件访问权限」后会递归扫描全部目录；" +
                    "不开启也能正常播放，只是列表可能不全。只问这一次。"
            )
            .setPositiveButton("去开启") { _, _ ->
                prefs.allFilesAsked = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                runCatching { allFilesLauncher.launch(intent) }.onFailure { startScan() }
            }
            .setNegativeButton("不用") { _, _ ->
                prefs.allFilesAsked = true
                startScan()
            }
            .setOnCancelListener {
                prefs.allFilesAsked = true
                startScan()
            }
            .show()
    }

    private fun showPermissionDenied() {
        MaterialAlertDialogBuilder(this)
            .setTitle("需要存储权限")
            .setMessage("没有读取视频的权限就没法列播放列表。")
            .setPositiveButton("再试一次") { _, _ -> ensurePermission() }
            .setNegativeButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun startScan() {
        scanJob?.cancel()
        scanning = true
        progress.visibility = View.VISIBLE
        applyFilter()
        scanJob = lifecycleScope.launch {
            // 阶段一：系统索引（含所有卷），冷启动几十毫秒就有列表
            val fast = withContext(Dispatchers.IO) {
                runCatching { VideoScanner.mediaStore(this@MainActivity) }.getOrDefault(emptyList())
            }
            all = fast
            scanning = false
            progress.visibility = View.GONE
            applyFilter()

            // 阶段二：后台递归补漏（含 OTG 挂载点），结果合并后无感刷新
            val extra = withContext(Dispatchers.IO) {
                runCatching { VideoScanner.deep(this@MainActivity) }.getOrDefault(emptyList())
            }
            if (extra.isNotEmpty()) {
                all = VideoScanner.merge(all, extra)
                applyFilter()
            }
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf("最近添加", "文件名", "文件大小", "时长")
        MaterialAlertDialogBuilder(this)
            .setTitle("排序方式")
            .setSingleChoiceItems(labels, prefs.sortMode) { dialog, which ->
                prefs.sortMode = which
                applyFilter()
                dialog.dismiss()
            }.show()
    }

    private fun showStepDialog() {
        val labels = Spec.SEEK_STEPS.map { "$it 秒" }.toTypedArray()
        val checked = Spec.SEEK_STEPS.indexOf(prefs.seekStepSec).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("双击快进 / 快退步长")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.seekStepSec = Spec.SEEK_STEPS[which]
                dialog.dismiss()
            }.show()
    }

    companion object {
        private const val MENU_SEARCH = 1
        private const val MENU_OTG = 2
        private const val MENU_STEP = 3
        private const val MENU_SORT = 4
        private const val MENU_RESCAN = 5
        private const val MENU_UPDATE = 6
    }
}
