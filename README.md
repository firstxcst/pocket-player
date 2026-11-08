# PocketPlayer

轻量级 Android 本地视频播放器。Material 3 + Jetpack Media3，纯 Kotlin、无第三方图片加载库、无 native `.so`，
R8 + 资源压缩后 release APK 约 2.7 MB。

## 功能

- **两阶段扫描**：启动先查 MediaStore（含全部 volume）秒出列表，再后台递归补漏隐藏目录、SD 卡、OTG U 盘。
- **OTG 入口**：工具栏常驻 OTG 按钮，枚举可移除卷并按卷过滤；USB / 媒体挂载广播触发自动重扫。
- **长按倍速**：长按播放界面弹出 2×5 档位面板（0.5x–4x，含 2x / 3x），手指不抬起滑动即选档，松手生效。
- **快进快退**：双击左/右半屏按 10 / 20 / 30 / 60 秒档位跳转；水平滑动连续 seek 带时间预览。
- **其它手势**：单击显隐控制条、左半屏上下滑亮度、右半屏上下滑音量、右上角一键锁定。
- **播放体验**：断点续播、播放队列、倍速与步长记忆、解码器回退、本地文件小缓冲快速起播。
- **转码**：解码失败时一键转码为 H.264 / AAC MP4，另支持去音轨、导出前 60 秒片段（Media3 Transformer）。
- **版本更新**：应用内查询本仓库 GitHub Release，发现新版直接跳下载页；启动静默检查 6 小时节流。

## 构建

Android Studio / AGP 8.7.3 / Gradle 8.9 / **JDK 17** / compileSdk 35 / minSdk 26。

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

命令行直连（不依赖 wrapper）示例，Windows PowerShell：

```powershell
$env:JAVA_HOME = "<JDK 17 路径>"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\<hash>\gradle-8.9\bin\gradle.bat" -p . assembleDebug --no-daemon
```

release 产物未签名，需自行 `zipalign` + `apksigner` 签名；签名密钥不随仓库分发。

## 已知限制

- 覆盖 MP4 / MKV / WebM / AVI / TS / FLV 等容器与设备 MediaCodec 支持的编码；
  AC3 / DTS / RMVB 等冷门编码需打开 `media3-decoder-ffmpeg`（体积 +8~15 MB），默认关闭。
- Transformer 与播放器共用 extractor，RMVB / WMV 这类连容器都解不开的老格式无法靠转码救回。
- 深度扫描需要「所有文件访问权限」；不授予也能用，只是列表可能不全。上架 Play 需按本地播放器类目申报。

## 版本

Releases 页面提供已签名的 `PocketPlayer-release.apk` 与 `PocketPlayer-debug.apk`。
