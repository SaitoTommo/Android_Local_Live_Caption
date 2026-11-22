# LocalLiveCaption（安卓本地实时字幕）

一款**完全在安卓手机本地离线运行**的实时字幕与语音转录应用，基于 [`sherpa-onnx`](https://github.com/k2-fsa/sherpa-onnx) 原生引擎，支持 **Qwen3-ASR 0.6B (INT8)** 及多款极速轻量级模型，无需联网、保护隐私。

---

## 核心特性

- **双音源实时采集**：
  - **手机内录**：基于 Android 10+ `MediaProjection` + `AudioPlaybackCapture`，直接转录手机正在播放的视频、网课、播客、会议音频。
  - **麦克风收音**：直接采集外部环境人声，适用于线下会议、课堂、面对面访谈，主界面一键切换。
- **多款离线 ASR 模型自由切换（应用内一键下载 / 本地导入）**：
  - **Paraformer-zh Small (INT8, ~80 MB)**：非自回归中英双语极速模型，单句延迟 < 0.1 秒。
  - **SenseVoice Small (INT8, ~238 MB)**：非自回归多语种（中/英/日/韩/粤），速度与准确率最均衡。
  - **Whisper Tiny (INT8, ~115 MB)**：轻量多语种模型，适合纯英文新闻/视频。
  - **Qwen3-ASR 0.6B (INT8, ~839 MB)**：高精度自回归语音大模型，标点与复杂语境理解更强。
  - 下载与 `.tar.bz2` 解压运行在应用级生命周期内，切换页面不会中断，并实时展示下载/解压/校验进度。
- **说话人区分（Speaker Diarization）**：
  - 结合 **Silero VAD** 自动断句与 **3D-Speaker (ERES2Net)** 声纹提取 + 在线余弦相似度聚类，实时标记 `说话人 1`、`说话人 2`…，并支持在历史详情页批量重命名说话人。
- **可缩放、可滚动悬浮字幕窗**：
  - 固定紧凑高度，不会随长句跳动遮挡视频画面；
  - 内部支持上下滚动回看最近 50 条字幕；
  - 支持**四角自由拖拽缩放**与标题栏拖拽移动，支持一键锁定防误触、调节字号与背景透明度。
- **可靠交互反馈与优雅收尾**：
  - 启动时预热加载本地模型并展示明确加载状态；
  - 主界面与悬浮窗均内置实时 RMS 声波收音动画；
  - 点击停止后自动等待所有缓存语音与 VAD 尾音识别落库完成，再弹出「保存本次录音档案」命名弹窗。
- **快捷入口与后台保活**：
  - 支持**状态栏下拉快捷开关（Quick Settings Tile）**一键启停；
  - 支持**桌面图标长按快捷方式（App Shortcuts）**直接开启悬浮字幕；
  - 设置页内置**悬浮窗权限**、**忽略电池优化（后台保护）**与国产 ROM 后台锁定引导，以及 **CPU / NNAPI（实验性 NPU）** 推理后端切换。
- **历史记录与 SRT / TXT 导出**：
  - Room 本地数据库完整保存每次会话、时间轴、说话人与字幕文本；
  - 支持标题搜索、单条字幕纠错编辑、批量重命名说话人，以及标准 `.srt` / `.txt` 导出与系统分享。

---

## 项目结构

```text
手机实时字幕/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    ├── libs/                       # 放置 sherpa-onnx-*.aar（不入 Git）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/                # strings, themes, shortcuts, file_paths, vector icons
        │   └── java/com/localcaption/app/
        │       ├── LocalCaptionApp.kt
        │       ├── MainActivity.kt
        │       ├── data/           # Room DB (Session/Segment/Speaker), DataStore Settings, Repository
        │       ├── audio/          # PlaybackAudioCapture, MicAudioCapture
        │       ├── asr/            # SherpaOnnxBridge, AsrEngine, VadSegmenter, SpeakerDiarizer, ModelManager, TranscriptionPipeline
        │       ├── service/        # CaptionForegroundService, FloatingOverlayManager, CaptionTileService
        │       ├── export/         # SrtExporter, TxtExporter, ExportHelper
        │       └── ui/             # Compose Material 3 (Live, History, HistoryDetail, Models, Settings)
        └── test/                   # JVM 单元测试 (SRT/TXT 导出、在线说话人聚类、VAD 分段)
```

---

## 环境要求与构建指南

- **最低系统版本**：Android 10（API 29，`AudioPlaybackCapture` 最低要求）
- **目标 ABI**：`arm64-v8a`
- **构建工具**：JDK 17 / 21（Android Studio 内置 JBR）、Android SDK 35、Gradle 8.14

### 1. 准备原生 `sherpa-onnx` AAR（启用端侧推理必需）

项目通过 [SherpaOnnxBridge.kt](app/src/main/java/com/localcaption/app/asr/SherpaOnnxBridge.kt) 与原生库解耦，即使未放入 AAR 也可直接编译；放入后即自动启用完整离线识别、Silero VAD 与 3D-Speaker 声纹提取：

1. 从 [k2-fsa/sherpa-onnx Releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载最新 Android AAR（如 `sherpa-onnx-2.0.2.aar`）。
2. 将其放入 `app/libs/` 目录（如 `app/libs/sherpa-onnx-2.0.2.aar`）。

### 2. 编译与测试

可直接使用 **Android Studio** 打开本项目目录同步构建，或在 Windows PowerShell 中运行：

```powershell
$env:JAVA_HOME = "D:\software\Android_Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# 运行单元测试
& "D:\software\gradle-8.14\bin\gradle.bat" :app:testDebugUnitTest

# 构建 Debug APK
& "D:\software\gradle-8.14\bin\gradle.bat" :app:assembleDebug
```

构建产物位于：`app/build/outputs/apk/debug/app-debug.apk`。

> **Windows 中文路径说明**：`gradle.properties` 中已配置 `android.overridePathCheck=true` 以及 `-Dfile.encoding=GBK`，以兼容包含中文字符的目录路径与 JDK 21 Gradle Worker。

---

## 首次使用建议

1. 安装 APK 后，先前往 **「设置 → 权限与后台保活」** 授予 **悬浮窗权限** 并开启 **后台保护**。
2. 前往 **「模型管理」** 下载至少一款 ASR 模型（推荐优先下载 **SenseVoice Small** 或 **Paraformer-zh Small** 体验极速字幕，或 **Qwen3-ASR 0.6B** 追求更高精度），并下载 **Silero VAD** 与 **3D-Speaker**。
3. 在 **「设置 → 语音识别」** 中将 **推理线程数** 调至 `4` 可显著降低识别延迟。
4. 回到 **「实时字幕」** 页选择「手机内录」或「麦克风收音」，点击 **开始** 即可使用；也可从系统下拉控制栏磁贴或长按 App 图标一键启动。
