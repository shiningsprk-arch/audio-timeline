# 音频时间轴 · Audio Timeline

把**已知文本**对齐到音频的小工具：选一段本地音频，粘贴歌词或台词，生成带时间码的字幕（SRT / LRC / TXT）。全程离线，音频和文本不上传。

```
音频 ──▶ 16 kHz 单声道 ──▶ wav2vec2 CTC 声学模型 ──▶ 帧级后验
                                                      │
你的歌词 ──▶ 繁简归一化 ──▶ 字符序列 ──▶ Viterbi 强制对齐 ◀──┘
                                                      │
                                            每行时间码 ──▶ SRT / LRC / TXT
```

## 和上一版的区别

上一版用 Whisper 先「识别」再和你的文本做匹配。在音乐上这条路是死的：实测一首多音色合成曲，Whisper 有个 30 秒窗口只吐出 1 个字，另两个窗口各喷了 444 个重复词，与歌词的字符重合度只有 **7.4%**。

这一版换了问题：不做识别，直接做**强制对齐**——给定你提供的文字，去找音频里最匹配的那条路径。文字不需要被“认对”，旋律、伴奏、拖腔都不影响它找边界。

顺带删掉了整个 Whisper 链路（7 个类、127MB 模型），它的幻觉、重复和 448 上下文崩溃也一起消失了。

## 模型与体积

| 组件 | 体积 | 说明 |
| --- | --- | --- |
| CTC 声学模型 | 609MB | `wav2vec2-large-xlsr-53-chinese-zh-cn` fp16，3503 字词表 |
| App 本体 | ~15MB | Kotlin + Compose + ONNX Runtime |
| **release APK** | **582MB** | arm64-v8a |

模型不放进 git（GitHub 单文件 100MB 限制），由 CI 构建时下载，再以 `jniLibs/arm64-v8a/libctcmodel.so` 的形式打进 APK。**这样装到手机上是真实文件，ONNX Runtime 直接读取，不需要把 609MB 再复制一份**（assets 里的文件不是真实路径，只能复制）。

## 精度

- 语音（TTS 基准，21.6s / 5 句，带真值）：行起点误差 **0.18s**，终点误差主要来自测试音频自带的尾随静音。
- 音乐（多音色合成曲，45 行歌词）：与人工试听认可的版本对照，混音直接对齐与分离人声对齐 **93% 的行起点差在 0.5s 内**，中位数 0.02s。

## 实现要点

| 模块 | 说明 |
| --- | --- |
| `ctc/CtcEngine` | ONNX 会话 + 30s 分块推理（5s 重叠去重），逐块归一化 |
| `ctc/CtcAligner` | CTC Viterbi（空白 + 重复字规则），后验按帧量化到 16 位省内存 |
| `ctc/LyricAligner` | 逐字时间聚成行、词表外生僻字插值、单调与最短时长约束 |
| `audio/AudioDecoder` | MediaCodec 解码，单声道与立体声两条路径 |
| `audio/Resampler` | 16 taps 窗函数 sinc 重采样 |
| `audio/VocalSeparator` | htdemucs ONNX 人声分离（代码就绪，v2 未随包发布） |

## 构建

推送到 `main` 或手动触发 workflow 即可，产物在 Actions 的 `audio-timeline-apk` artifact 里。打 tag（`v2.0.0`）会自动创建 Release 并附上 APK。

```bash
./gradlew assembleRelease   # 需要先把模型放到 app/src/main/jniLibs/arm64-v8a/
```

本地构建前需准备模型：

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
curl -L -o app/src/main/jniLibs/arm64-v8a/libctcmodel.so \
  https://huggingface.co/onnx-community/wav2vec2-large-xlsr-53-chinese-zh-cn-ONNX/resolve/main/onnx/model_fp16.onnx
```

## 校验

`tools/verify/CtcCheck.kt` 用 App 里真正的 Kotlin 代码重跑 Python 参考实现导出的后验，逐字比对起止时间：

```bash
kotlinc app/src/main/java/com/shiningsprk/audiotimeline/{align,ctc}/*.kt \
        tools/verify/CtcCheck.kt -include-runtime -d check.jar
java -jar check.jar <参考数据目录>
```

当前结果：429 个字的时间戳与参考实现**误差 0.000s**。

`tools/` 下还保留了 Python 侧的实验脚本（`ctc_align.py`、`run_ctc.py`），换模型或调参时可以先离线验证。

## 已知限制

- **只支持中文**（含繁简混排）；词表 3503 字，生僻字会被跳过并插值
- 单次音频上限 **10 分钟**（帧级后验常驻内存，10 分钟约 210MB）
- 只处理 arm64 设备
- 人声分离代码就绪但暂未打包，遇到极密集编曲可以先用外部工具分离再喂给 App

## 依赖与许可

- 声学模型：`jonatasgrosman/wav2vec2-large-xlsr-53-chinese-zh-cn`（Apache-2.0），ONNX 导出由 [onnx-community](https://huggingface.co/onnx-community) 提供
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)（MIT）
- 繁简映射表来自 [OpenCC](https://github.com/BYVoid/OpenCC)（Apache-2.0）
- 本项目代码：AGPL-3.0
