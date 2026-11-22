# Android Local Live Caption

[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Compose-Material%20You-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![sherpa-onnx](https://img.shields.io/badge/Engine-sherpa--onnx-orange)](https://github.com/k2-fsa/sherpa-onnx)

**100% 本地离线运行**的安卓实时字幕与语音转录工具。无需联网与云端 API，支持手机内录与麦克风收音、悬浮字幕、说话人区分、录音回放校对及 SRT / TXT 导出。

## 功能亮点

- **双音源采集**：支持 Android 10+ 手机内录（视频、网课、播客、会议）与麦克风收音一键切换。
- **多模型离线推理**：内置模型管理页，支持一键下载或本地导入，支持 **CPU / NNAPI (NPU)** 切换及实时耗时与实时率（RTF）展示。
- **悬浮字幕窗**：固定高度防遮挡、内部上下滚动回看、四角隐形热区自由缩放、一键最小化为可拖动悬浮球、一键返回主界面。
- **抗 BGM 断句**：Silero VAD 支持自定义人声检测阈值（过滤背景音乐）、句首回溯防丢清辅音及长句强制切段。
- **说话人管理**：支持实时说话人区分、整段录音离线重识说话人（全局聚类）、合并说话人与批量重命名。
- **校对与导出**：历史详情页支持录音回放与字幕同步高亮跟随、点击句子跳转播放、修改错别字、导出/分享 `.srt` 与 `.txt`。
- **系统集成**：状态栏下拉快捷磁贴、桌面长按快捷方式、Material You 动态主题色、悬浮窗与后台保活权限引导。

## 支持的模型

| 模型 | 体积 | 说明 |
| :--- | :--- | :--- |
| **Paraformer-zh Small (INT8)** | ~80 MB | 极速中英双语，单句延迟 < 0.1s |
| **SenseVoice Small (INT8)** *(推荐)* | ~238 MB | 中/英/日/韩/粤多语种，速度与准确率最均衡 |
| **Qwen3-ASR 0.6B (INT8)** | ~839 MB | 高精度语音大模型，标点与复杂语境更强 |
| **Silero VAD** | ~2 MB | 语音端点检测与自动断句 |
| **3D-Speaker (ERES2Net)** | ~40 MB | 说话人声纹提取与区分 |

## 快速安装

前往 [Releases](https://github.com/SaitoTommo/Android_Local_Live_Caption/releases) 下载 `LocalLiveCaption-v1.0.0-arm64.apk` 安装（需 Android 10+ / `arm64-v8a`），打开后在 **「模型管理」** 下载所需模型即可离线使用。

## 从源码构建

1. 克隆仓库：
   ```bash
   git clone https://github.com/SaitoTommo/Android_Local_Live_Caption.git
   ```
2. 从 [k2-fsa/sherpa-onnx Releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载 Android AAR（如 `sherpa-onnx-2.0.2.aar`）放入 `app/libs/`。
3. 使用 Android Studio 打开构建，或运行：
   ```bash
   gradle :app:testDebugUnitTest :app:assembleRelease
   ```

> 注：极少数禁止音频捕获（`allowAudioPlaybackCapture="false"`）的 DRM 应用受系统限制无法内录，可切换至麦克风模式。

## 致谢

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) · [Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR) · [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) · [FunASR](https://github.com/modelscope/FunASR) · [Silero VAD](https://github.com/snakers4/silero-vad) · [3D-Speaker](https://github.com/modelscope/3D-Speaker)
