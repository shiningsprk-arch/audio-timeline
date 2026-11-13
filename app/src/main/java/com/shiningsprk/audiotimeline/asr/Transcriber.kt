package com.shiningsprk.audiotimeline.asr

import com.shiningsprk.audiotimeline.align.RecChar
import com.shiningsprk.audiotimeline.align.TextAligner
import com.shiningsprk.audiotimeline.dsp.MelFrontend
import com.shiningsprk.audiotimeline.dsp.SpeechRegion
import com.shiningsprk.audiotimeline.dsp.Vad
import kotlin.math.max
import kotlin.math.min

/**
 * Turns audio into a character stream with timings:
 *   1. group speech regions into <=30 s windows so no window cuts through speech,
 *   2. run Whisper on every window,
 *   3. spread the recognised characters over the speech time of that window.
 */
data class Transcript(
    val chars: List<RecChar>,
    val regions: List<SpeechRegion>,
    val windows: List<Pair<Double, Double>>,
)

class Transcriber(
    private val engine: WhisperEngine,
    private val melFilters: FloatArray,
) {

    fun buildWindows(regions: List<SpeechRegion>, duration: Double): List<Pair<Double, Double>> {
        val windows = ArrayList<Pair<Double, Double>>()
        var curStart = Double.NaN
        var curEnd = Double.NaN

        fun flush() {
            if (!curStart.isNaN()) windows.add(curStart to curEnd)
            curStart = Double.NaN
            curEnd = Double.NaN
        }

        for (region in regions) {
            var start = max(0.0, region.start - 0.15)
            var end = min(duration, region.end + 0.15)
            while (end - start > WINDOW_SEC) {
                flush()
                windows.add(start to start + WINDOW_SEC)
                start += WINDOW_SEC
            }
            if (curStart.isNaN()) {
                curStart = start
                curEnd = end
            } else if (end - curStart <= WINDOW_SEC) {
                curEnd = end
            } else {
                flush()
                curStart = start
                curEnd = end
            }
        }
        flush()
        val covered = windows.sumOf { it.second - it.first }
        if (windows.isEmpty() || covered < duration * 0.6) {
            // VAD is not trustworthy (music, constant noise, a single loud click):
            // tile the whole file so no audio is silently skipped
            windows.clear()
            var t = 0.0
            while (t < duration) {
                windows.add(t to min(duration, t + WINDOW_SEC))
                t += WINDOW_SEC
            }
        }
        if (windows.isEmpty()) windows.add(0.0 to max(0.05, duration))
        return windows
    }

    private fun rms(samples: FloatArray, startSec: Double, endSec: Double): Double {
        val from = (startSec * MelFrontend.SAMPLE_RATE).toInt().coerceIn(0, samples.size)
        val to = (endSec * MelFrontend.SAMPLE_RATE).toInt().coerceIn(from, samples.size)
        if (to <= from) return 0.0
        var sum = 0.0
        var i = from
        while (i < to) {
            val v = samples[i].toDouble()
            sum += v * v
            i++
        }
        return kotlin.math.sqrt(sum / (to - from))
    }

    fun transcribe(
        samples: FloatArray,
        duration: Double,
        language: String,
        onStage: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): Transcript {
        val regions = Vad.detect(samples)
        val windows = buildWindows(regions, duration)
        val mel = FloatArray(MelFrontend.N_MEL * MelFrontend.N_FRAMES)
        val texts = ArrayList<String>(windows.size)

        if (windows.none { rms(samples, it.first, it.second) >= SILENCE_RMS }) {
            throw IllegalStateException("这段音频里没有检测到人声，无法对齐")
        }
        var languageToken = WhisperTokenizer.LANGUAGE_TOKENS[language] ?: -1
        for ((index, window) in windows.withIndex()) {
            onStage("识别第 ${index + 1}/${windows.size} 段 · ${formatClock(window.first)}")
            // near-silent windows only produce Whisper hallucinations, skip them
            if (rms(samples, window.first, window.second) < SILENCE_RMS) {
                texts.add("")
                onProgress((index + 1).toFloat() / windows.size)
                continue
            }
            val startSample = (window.first * MelFrontend.SAMPLE_RATE).toInt()
            MelFrontend.compute(samples, startSample, melFilters, mel)
            val encoded = engine.encode(mel)
            if (languageToken < 0) {
                languageToken = engine.detectLanguage(encoded)
            }
            texts.add(engine.transcribe(encoded, languageToken))
            onProgress((index + 1).toFloat() / windows.size)
        }
        onStage("对齐文本时间")
        return Transcript(TextAligner.buildCharTimeline(texts, windows, regions), regions, windows)
    }

    private fun formatClock(seconds: Double): String {
        val total = seconds.toInt()
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
    }

    companion object {
        const val WINDOW_SEC = 30.0
        private const val SILENCE_RMS = 0.0035
    }
}
