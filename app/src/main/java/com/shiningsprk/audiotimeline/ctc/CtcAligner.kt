package com.shiningsprk.audiotimeline.ctc

/**
 * Frame posteriors stored as one 16 bit value per (frame, token).
 *
 * Each frame is min/max normalised to 0..65535 (0 = most likely). Because every Viterbi
 * path visits exactly one state per frame, a per-frame offset shifts all paths by the
 * same amount and cannot change the best path, so quantising is safe and keeps the
 * memory footprint of a 3 minute song at ~58 MB instead of 115 MB.
 */
class CtcPosteriors(
    val frames: Int,
    val vocabSize: Int,
    private val data: ShortArray,
) {
    fun cost(frame: Int, token: Int): Int = data[frame * vocabSize + token].toInt() and 0xFFFF

    companion object {
        fun fromLogProbs(frames: Int, vocabSize: Int, logProbs: FloatArray): CtcPosteriors {
            require(logProbs.size >= frames * vocabSize) { "log-prob buffer too small" }
            val out = ShortArray(frames * vocabSize)
            for (t in 0 until frames) {
                val base = t * vocabSize
                var maxV = -Float.MAX_VALUE
                for (v in 0 until vocabSize) {
                    val x = logProbs[base + v]
                    if (x > maxV) maxV = x
                }
                for (v in 0 until vocabSize) {
                    // 0 = best, 255 = 40 log units worse (clipped)
                    val rel = (maxV - logProbs[base + v]).coerceIn(0f, 40f)
                    out[base + v] = (rel / 40f * 65535f).toInt().toShort()
                }
            }
            return CtcPosteriors(frames, vocabSize, out)
        }
    }
}

/** One aligned character: [start, end) in seconds plus a 0..255 confidence cost. */
class CharSpan(val start: Double, val end: Double, val cost: Int)

/**
 * CTC forced alignment (Viterbi over a known token sequence).
 *
 * Unlike recognition this does not need the model to *understand* the audio: it only
 * asks which monotonic path through the frames makes the given characters most likely.
 */
object CtcAligner {

    fun align(
        posteriors: CtcPosteriors,
        tokenIds: IntArray,
        frameSec: Double = 0.02,
    ): List<CharSpan?> {
        val n = tokenIds.size
        if (n == 0) return emptyList()
        val frames = posteriors.frames
        val states = 2 * n + 1
        val blank = 0

        val emit = IntArray(states) { if (it % 2 == 0) blank else tokenIds[it / 2] }
        val canSkip = BooleanArray(states)
        for (k in 1 until n) {
            val s = 2 * k + 1
            canSkip[s] = tokenIds[k] != tokenIds[k - 1]
        }

        val INF = Int.MAX_VALUE / 4
        var prev = IntArray(states) { INF }
        var cur = IntArray(states) { INF }
        prev[0] = 0
        if (states > 1) prev[1] = posteriors.cost(0, emit[1])

        val trace = ByteArray(frames * states)
        for (t in 1 until frames) {
            for (s in 0 until states) {
                var best = prev[s]
                var op: Byte = 0
                if (s >= 1) {
                    val c = prev[s - 1]
                    if (c < best) {
                        best = c
                        op = 1
                    }
                }
                if (s >= 2 && canSkip[s]) {
                    val c = prev[s - 2]
                    if (c < best) {
                        best = c
                        op = 2
                    }
                }
                cur[s] = if (best >= INF) INF else best + posteriors.cost(t, emit[s])
                trace[t * states + s] = op
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }

        var endState = if (prev[states - 1] <= prev[states - 2]) states - 1 else states - 2
        var state = endState
        val firstFrame = IntArray(states) { -1 }
        val lastFrame = IntArray(states) { -1 }
        for (t in frames - 1 downTo 0) {
            // walking backwards: the first visit is the state's last frame,
            // the final visit is its first frame
            if (lastFrame[state] < 0) lastFrame[state] = t
            firstFrame[state] = t
            val op = trace[t * states + state]
            state = when (op.toInt()) {
                1 -> state - 1
                2 -> state - 2
                else -> state
            }
            if (state < 0) break
        }

        return (0 until n).map { k ->
            val s = 2 * k + 1
            val from = firstFrame[s]
            if (from < 0) {
                null
            } else {
                CharSpan(from * frameSec, (lastFrame[s] + 1) * frameSec, 0)
            }
        }
    }
}
