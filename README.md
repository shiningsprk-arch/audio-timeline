# 音频时间轴 · Audio Timeline

给本地音频打轴的小工具：**选一段手机里的音频，粘贴它对应的文稿 / 歌词，生成带时间码的文本**（SRT / LRC / TXT）。

全部推理都在手机上完成，音频和文本不会上传到任何服务器。

```
音频文件 ──▶ 解码 + 16 kHz 单声道 ──▶ VAD 切分语音 ──▶ Whisper (ONNX) 识别
                                                            │
用户文稿 ──▶ 繁简归一化 ──▶ 字符级 DP 对齐 ◀── 识别字符流 + 语音时间
                                                            │
                                                 带时间码的行 ──▶ SRT / LRC / TXT
```

## 功能

- **本地音频**：用系统文件选择器打开 mp3 / m4a / wav / flac，自动解码、降混、重采样到 16 kHz
- **文稿对齐**：粘贴文稿后逐行算出开始 / 结束时间；标点不占时长，长段落会自动按句号拆行
- **离线识别**：Whisper `tiny` / `base` 的 ONNX 量化模型，首次使用下载一次，之后完全离线（默认走 `hf-mirror.com`，失败回退 `huggingface.co`）
- **结果预览**：点任意一行跳到对应时间播放，播放时按行高亮
- **导出**：SRT 字幕、LRC 歌词、带时间码的纯文本，可分享或复制

## 为什么不是 faster-whisper

faster-whisper 依赖 Python + CTranslate2，无法直接跑在 Android 上。本项目改用同源 Whisper 权重导出的 **ONNX** 模型（`onnx-community/whisper-*`），用 ONNX Runtime for Android 做纯本地推理，效果对齐 faster-whisper 的 int8 量化档，同时不需要任何 Python 运行时。

## 构建

GitHub Actions 已经配好（`.github/workflows/android.yml`），推送到 `main` 或手动触发 workflow 即可，产物在 Actions 的 `audio-timeline-apk` artifact 里，含 debug 与 release 两个 APK（release 用 CI 的 debug keystore 签名，可直接安装）。

打 tag（`v1.0.0`）会自动创建 Release 并附上 APK。

本地构建需要 JDK 17 + Android SDK 34：

```bash
./gradlew assembleDebug
```

## 实现要点

| 模块 | 说明 |
| --- | --- |
| `audio/AudioDecoder` | `MediaExtractor` + `MediaCodec` 解码，任意声道降混 |
| `audio/Resampler` | 16 taps 窗函数 sinc 重采样到 16 kHz，避免线性插值的混叠 |
| `dsp/MelFrontend` | 复刻 Whisper 的 log-mel：400 点 STFT、周期 Hann、80 维 slaney mel、`(x+4)/4` 归一化 |
| `dsp/Fft` | radix-2 FFT + Bluestein 算法，精确计算 400 点 DFT（非 2 的幂） |
| `dsp/Vad` | 能量 + 分位数自适应阈值的 VAD，用于切窗和排除停顿 |
| `asr/WhisperEngine` | ONNX Runtime 会话管理，KV cache 增量解码；禁用时间戳（`<|notimestamps|>`），token 预算按 448 上下文精确计算 |
| `align/TextAligner` | 字符级 Needleman-Wunsch 对齐 + 语音时间映射 + 行首吸附 |

### 时间是怎么算出来的

Whisper 自带的时间戳在连续语音上很容易「几句并成一段」，误差可达数秒，因此**不使用**模型输出的时间戳，而是：

1. VAD 找出所有说话区间，把静音从时间轴里剔除；
2. 识别出的字符按说话时长均匀铺开，得到每个字符的起止时间；
3. 用 DP 把用户文稿的字符和识别字符对上（允许替换 / 缺失，能容忍识别错误和繁简差异）；
4. 行时间取自匹配到的字符，行首吸附到最近的语音边界。

在一段 21 秒、5 句、带真值边界的中文测试音频上，行级平均绝对误差 **0.33 s**（最大 0.56 s，且误差主要来自测试音频首尾自带的静音）。项目里用 Python 参考实现 + JVM 校验程序把这个流程固定下来（见下）。

## 校验

`tools/` 下有一套可复现的验证流程：

```bash
pip install numpy librosa onnxruntime
python tools/gen_assets.py                 # 生成 mel 滤波器 / 词表 / 繁简表等资源
python tools/ref_pipeline.py <模型目录> <音频> <真值 json>   # 参考实现 + 误差评估
python tools/make_ref.py <模型目录> <音频> <真值 json> <输出目录>  # 导出参考数据
```

`tools/verify/JvmCheck.kt` 会用 App 里真正的 Kotlin 代码重跑同一份数据，逐项比对 mel 频谱、VAD 区间、字符时间、行时间、分词器：

```bash
kotlinc app/src/main/java/com/shiningsprk/audiotimeline/{dsp,align,export,asr}/*.kt \
        tools/verify/JvmCheck.kt -include-runtime -d check.jar
java -jar check.jar app/src/main/assets <参考数据目录>
```

当前结果：mel 最大误差 `5.4e-4`，VAD 区间、字符与行时间误差 `< 1e-5`。

## 已知限制

- 模型较小，中文识别对专有名词、口音、背景音乐较敏感；识别错误只会影响对齐位置，不会改动用户原文
- 说话速度变化很大（如朗读与快速对话混在一起）时，行内时间会有偏差
- 单次音频上限 20 分钟（解码后整段驻留内存，DP 复杂度 O(文本字数 × 识别字数)）
- 纯音乐 / 无人声的音频无法对齐

## 依赖与许可

- [Whisper](https://github.com/openai/whisper) 权重（MIT），ONNX 导出：[onnx-community](https://huggingface.co/onnx-community)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)（MIT）
- 繁简映射表来自 [OpenCC](https://github.com/BYVoid/OpenCC)（Apache-2.0）
- 本项目代码：MIT
