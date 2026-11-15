package com.shiningsprk.audiotimeline.ctc

import com.shiningsprk.audiotimeline.align.AlignedLine
import com.shiningsprk.audiotimeline.align.AlignResult
import kotlin.math.max
import kotlin.math.min

/**
 * Turns "known lyrics" + frame posteriors into per line time codes.
 *
 * Characters missing from the acoustic vocabulary are skipped for alignment and get an
 * interpolated time afterwards, so the user's text is never altered.
 */
object LyricAligner {

    fun align(
        lines: List<String>,
        posteriors: CtcPosteriors,
        vocab: CtcVocab,
        t2s: Map<Char, Char>,
        frameSec: Double = CtcEngine.FRAME_SEC,
        duration: Double = 0.0,
    ): AlignResult {
        val ids = ArrayList<Int>(lines.sumOf { it.length })
        val perLine = IntArray(lines.size)
        var totalChars = 0
        for ((index, line) in lines.withIndex()) {
            for (ch in line) {
                if (!ch.isLetterOrDigit()) continue
                totalChars++
                val mapped = t2s[ch] ?: ch
                val id = vocab.idOf(mapped) ?: vocab.idOf(ch)
                if (id != null) {
                    ids.add(id)
                    perLine[index]++
                }
            }
        }
        require(ids.isNotEmpty()) { "文本里的字都不在识别词表里，无法对齐" }

        val spans = CtcAligner.align(posteriors, ids.toIntArray(), frameSec)

        val out = arrayOfNulls<DoubleArray>(lines.size)
        var cursor = 0
        for (i in lines.indices) {
            val n = perLine[i]
            if (n == 0) continue
            var start = Double.MAX_VALUE
            var end = -Double.MAX_VALUE
            for (k in cursor until cursor + n) {
                val s = spans[k] ?: continue
                start = min(start, s.start)
                end = max(end, s.end)
            }
            if (start < Double.MAX_VALUE) out[i] = doubleArrayOf(start, end)
            cursor += n
        }

        // lines without any aligned character: spread them between their neighbours
        for (i in lines.indices) {
            if (out[i] != null) continue
            var prev = 0.0
            for (k in i - 1 downTo 0) {
                val s0 = out[k]
                if (s0 != null) {
                    prev = s0[1]
                    break
                }
            }
            var next = Double.NaN
            for (k in i + 1 until lines.size) {
                val s0 = out[k]
                if (s0 != null) {
                    next = s0[0]
                    break
                }
            }
            val lo = prev + 0.02
            val hi = if (next.isNaN()) prev + 1.0 else next - 0.02
            out[i] = doubleArrayOf(lo, max(lo + 0.2, hi))
        }

        val ordered = out.map { it!! }
        for (i in 1 until ordered.size) {
            ordered[i][0] = max(ordered[i][0], ordered[i - 1][0] + 0.05)
            ordered[i - 1][1] = min(ordered[i - 1][1], ordered[i][0] - 0.01)
        }
        for (i in ordered.indices) {
            val minEnd = ordered[i][0] + 0.2
            val cap = if (i + 1 < ordered.size) ordered[i + 1][0] - 0.01 else Double.MAX_VALUE
            ordered[i][1] = min(max(ordered[i][1], minEnd), max(minEnd, cap))
        }
        if (duration > 0) {
            for (s in ordered) {
                s[0] = min(max(0.0, s[0]), max(0.0, duration - 0.2))
                s[1] = min(max(s[1], s[0] + 0.2), max(s[0] + 0.2, duration))
            }
        }

        val aligned = lines.mapIndexed { index, text -> AlignedLine(text, ordered[index][0], ordered[index][1]) }
        return AlignResult(aligned, ids.size, totalChars)
    }
}
