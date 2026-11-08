# PocketPlayer

轻量级 Android 本地视频播放器。Material 3 + Jetpack Media3 1.5.1，纯 Kotlin，
无图片加载库、无 DI、无数据库。提供 **lite / full 两个版本**，按需选择。

## 两个版本

| | lite（默认） | full（全格式） |
|---|---|---|
| APK 体积 | 约 2.7 MB | 约 8 MB（含 arm64 FFmpeg so） |
| 视频解码 | 系统 MediaCodec | MediaCodec + **NextLib FFmpeg 软解** |
| 能播 | 设备硬解支持的编码 | 追加 HEVC 10-bit / Main10、VP8/VP9、H.264 软解，以及 AC3/EAC3/DTS/TrueHD 等音轨 |
| native 库 | 无 | `arm64-v8a` only |
| 许可证 | Apache-2.0 依赖 | 附加 **GPL-3.0**（NextLib / FFmpeg / libass） |

full 版集成的是成熟方案 [NextLib](https://github.com/anilbeesetti/nextlib) 0.8.4（JitPack），
其 `NextRenderersFactory` 与 `DefaultRenderersFactory` 一对一兼容，本项目只做工厂替换，没有自研解码器。
该版本恰好依赖 `androidx.media3:media3-exoplayer:1.5.1`，与项目版本一致，无需升级 Media3。

> 为什么不用 `media3-decoder-ffmpeg`：Google Maven 不发布该 AAR（1.5.1 的 aar/pom 实测 404），
> 官方要求按 `libraries/decoder_ffmpeg/README.md` 用 NDK 自编；Jellyfin 的预编译 fork 只启用了
> **音频**解码器（`flac alac mp3 aac ac3 eac3 dca mlp truehd`），解不了 HEVC 视频轨。

## 功能

- **两阶段扫描**：启动先查 MediaStore（含全部 volume）秒出列表，再后台递归补漏隐藏目录、SD 卡、OTG U 盘。
- **OTG 入口**：工具栏常驻 OTG 按钮，枚举可移除卷并按卷过滤；USB / 媒体挂载广播触发自动重扫。
- **长按 2x**：长按画面期间临时 2 倍速播放，松手恢复原倍速；长按触发会吞掉单击/双击派发，不与暂停误触。
  其它倍速（0.5x–4x）统一收纳到「更多 → 播放倍速」，标题栏实时显示当前倍速。
- **三分区手势**：双击左侧快退、右侧快进（步长不变，10 / 20 / 30 / 60 秒可设），双击中间暂停；
  控制条另有 `−30s / −10s / +10s / +30s` 直接按钮，点一下就跳；水平滑动连续 seek 带时间预览。
- **中间上下滑切换视频**：上滑下一个、下滑上一个，整段手势只切一次；左侧上下滑亮度，右侧上下滑软件音量。
- **关键节点标记**：播放中点底栏「标记」按当前时间点打点，进度条上以亮橙竖线 + 白心圆点显示；
  「更多 → 标记列表」可跳转或清除；按视频持久化，1.5 秒内不重复打点。
- **默认静音锁死**：进播放页 `player.volume = 0`，点右上角「静音 · 解锁」才出声（画面锁定时需先解锁画面）。
  **硬件音量键被 Activity 完全消费**，解锁前按音量键无任何效果；解锁后音量调整只改 `player.volume`，
  绝不调用 `AudioManager.setStreamVolume`，不改系统媒体音量——只在软件内生效。
- **拖动不卡**：默认 `SeekParameters.CLOSEST_SYNC` 关键帧快速定位；拖动期间完全停止进度条 UI 刷新，
  滑动 seek 提示按 80ms 节流；「更多」菜单可切回精确定位（较慢）。
- **其它**：单击显隐控制条、右上角一键锁定；临时 2x 在切视频 / 锁定 / 退后台时自动恢复，不影响播放流程。
- **播放体验**：断点续播、播放队列、倍速与步长记忆、解码器回退、本地文件小缓冲快速起播。
- **转码**：解码失败时一键转码为 H.264 / AAC MP4，另支持去音轨、导出前 60 秒片段（Media3 Transformer）。
- **版本更新**：应用内查询本仓库 GitHub Release，按当前 flavor 推荐对应 APK；启动静默检查 6 小时节流。
- **诊断弹窗**：解码失败时显示文件名、MIME、错误码、cause、设备型号、Android 版本与 flavor，可复制诊断信息，
  并提供「忽略音轨重试 / 用其他播放器打开 / 转码 / 播放下一个」等自救动作。

## 构建

Android Studio / AGP 8.7.3 / Gradle 8.9 / **JDK 17** / compileSdk 35 / minSdk 26。

```bash
./gradlew assembleLiteRelease    # 轻量版
./gradlew assembleFullRelease    # 全格式版
./gradlew assembleLiteDebug
```

命令行直连（不依赖 wrapper）示例，Windows PowerShell：

```powershell
$env:JAVA_HOME = "<JDK 17 路径>"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\<hash>\gradle-8.9\bin\gradle.bat" -p . assembleFullRelease --no-daemon
```

release 产物未签名，需自行 `zipalign` + `apksigner` 签名；签名密钥不随仓库分发。
`tools/prepare_videos.ps1` 是可选的桌面端音轨预处理脚本（PowerShell 7 / UTF-8，不需要管理员权限），
只转音轨、不重编码视频、不改动原文件，支持 `-DryRun`。

## 已知限制

- lite 版遇到 HEVC 10-bit / Main10 等硬解不支持的流会失败，请改装 full 版或用桌面端转码。
- full 版仅打包 `arm64-v8a`；32 位设备请自行在 `ndk.abiFilters` 增加 `armeabi-v7a`。
- NextLib 为 GPL-3.0，分发 full 版需遵守其开源义务；lite 版不含该依赖。
- Transformer 与播放器共用 extractor，RMVB / WMV 这类连容器都解不开的老格式无法靠转码救回。
- 深度扫描需要「所有文件访问权限」；不授予也能用，只是列表可能不全。上架 Play 需按本地播放器类目申报。

## 版本

Releases 页面提供 `PocketPlayer-release.apk`（lite）、`PocketPlayer-full.apk`（full）与 `PocketPlayer-debug.apk`。
