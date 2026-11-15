package com.shiningsprk.audiotimeline.ctc

import android.content.Context
import android.net.Uri
import com.shiningsprk.audiotimeline.align.AlignResult
import com.shiningsprk.audiotimeline.audio.AudioDecoder
import com.shiningsprk.audiotimeline.audio.Resampler
import com.shiningsprk.audiotimeline.audio.VocalSeparator

/**
 * decode -> (optional vocal separation) -> CTC posteriors -> forced alignment.
 *
 * Separation is what makes music work: the aligner needs to "hear" the singing, and a
 * dense mix buries it. It costs a few extra minutes, so it is optional.
 */
class LyricPipeline(
    private val assets: CtcAssets,
    private val threads: Int = 4,
) {

    companion object {
        /** posteriors for 10 minutes are ~210 MB, beyond that phones start to struggle */
        const val MAX_SECONDS = 600.0
    }

    data class Output(val result: AlignResult, val durationSec: Double, val separated: Boolean)

    fun run(
        context: Context,
        uri: Uri,
        lines: List<String>,
        separate: Boolean,
        onStage: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): Output {
        val useSeparator = separate && assets.hasSeparator
        onStage("解码音频")
        val vocals: FloatArray
        val duration: Double
        if (useSeparator) {
            val stereo = AudioDecoder.decodeStereo(context, uri)
            onProgress(0.08f)
            onStage("分离人声")
            val left = Resampler.resample(stereo.left, stereo.sampleRate, VocalSeparator.MODEL_RATE)
            val right = Resampler.resample(stereo.right, stereo.sampleRate, VocalSeparator.MODEL_RATE)
            val separated = VocalSeparator(assets.separatorModel!!, threads).use { separator ->
                separator.extract(left, right) { p -> onProgress(0.08f + 0.32f * p) }
            }
            duration = separated.size.toDouble() / VocalSeparator.MODEL_RATE
            onStage("整理人声")
            vocals = Resampler.resample(separated, VocalSeparator.MODEL_RATE, CtcEngine.SAMPLE_RATE)
        } else {
            val decoded = AudioDecoder.decode(context, uri)
            vocals = decoded.samples
            duration = decoded.durationSec
        }
        require(duration <= MAX_SECONDS) {
            "音频约 ${(duration / 60).toInt()} 分钟，超过当前版本支持的 ${(MAX_SECONDS / 60).toInt()} 分钟，请先裁剪"
        }
        onProgress(0.4f)

        onStage("计算声学特征")
        val posteriors = CtcEngine(assets.alignerModel, threads).use { engine ->
            engine.posteriors(vocals) { p -> onProgress(0.4f + 0.5f * p) }
        }

        onStage("对齐文本")
        onProgress(0.95f)
        val result = LyricAligner.align(
            lines = lines,
            posteriors = posteriors,
            vocab = assets.vocab,
            t2s = assets.t2s,
            frameSec = CtcEngine.FRAME_SEC,
            duration = duration,
        )
        onProgress(1f)
        return Output(result, duration, useSeparator)
    }
}
