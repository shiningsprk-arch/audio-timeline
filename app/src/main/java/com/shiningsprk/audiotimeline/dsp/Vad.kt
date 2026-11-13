package com.shiningsprk.audiotimeline.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** A detected speech region, in seconds. */
data class SpeechRegion(val start: Double, val end: Double)

/**
 * Very small energy based voice activity detector.
 *
 * It is used for two things:
 *  - deciding where to cut ASR windows (never cut inside speech),
 *  - mapping text progress to *speech* time, so long pauses do not stretch timings.
 */
object Vad {

    private const val FRAME = 400      // 25 ms
    private const val HOP = 160        // 10 ms
    private const val MIN_GAP_SEC = 0.30
    private const val MIN_SPEECH_SEC = 0.12
    private const val EDGE_PAD_SEC = 0.05

    fun detect(samples: FloatArray, sampleRate: Int = MelFrontend.SAMPLE_RATE): List<SpeechRegion> {
        if (samples.isEmpty()) return emptyList()
        val frames = if (samples.size >= FRAME) 1 + (samples.size - FRAME) / HOP else 0
        if (frames <= 0) return listOf(SpeechRegion(0.0, samples.size.toDouble() / sampleRate))

        val db = FloatArray(frames)
        for (f in 0 until frames) {
            val off = f * HOP
            var sum = 0.0
            for (i in 0 until FRAME) {
                val v = samples[off + i].toDouble()
                sum += v * v
            }
            db[f] = (20.0 * log10(sqrt(sum / FRAME) + 1e-8)).toFloat()
        }

        val sorted = db.copyOf()
        sorted.sort()
        val noise = sorted[(sorted.size * 0.10).toInt().coerceIn(0, sorted.size - 1)]
        val peak = sorted[(sorted.size * 0.99).toInt().coerceIn(0, sorted.size - 1)]
        val threshold = max(noise + 10f, peak - 38f)

        val runs = ArrayList<IntArray>()
        var start = -1
        for (f in 0 until frames) {
            if (db[f] > threshold) {
                if (start < 0) start = f
            } else if (start >= 0) {
                runs.add(intArrayOf(start, f))
                start = -1
            }
        }
        if (start >= 0) runs.add(intArrayOf(start, frames))

        val merged = ArrayList<IntArray>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && (r[0] - last[1]) * HOP.toDouble() / sampleRate < MIN_GAP_SEC) {
                last[1] = r[1]
            } else {
                merged.add(intArrayOf(r[0], r[1]))
            }
        }

        val pad = EDGE_PAD_SEC
        val total = samples.size.toDouble() / sampleRate
        val regions = ArrayList<SpeechRegion>()
        for (r in merged) {
            val begin = r[0] * HOP.toDouble() / sampleRate
            val end = r[1] * HOP.toDouble() / sampleRate
            if (end - begin < MIN_SPEECH_SEC) continue
            regions.add(SpeechRegion(max(0.0, begin - pad), minOf(total, end + pad)))
        }
        return regions
    }
}
