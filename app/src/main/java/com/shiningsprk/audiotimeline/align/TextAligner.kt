package com.shiningsprk.audiotimeline.align

import com.shiningsprk.audiotimeline.dsp.SpeechRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One recognised character with the time span it occupies in the audio. */
data class RecChar(val ch: Char, val start: Double, val end: Double)

data class AlignedLine(val text: String, val start: Double, val end: Double)

data class AlignResult(
    val lines: List<AlignedLine>,
    val matchedChars: Int,
    val totalChars: Int,
) {
    val matchRate: Float get() = if (totalChars == 0) 0f else matchedChars.toFloat() / totalChars
}

/**
 * Aligns user supplied text lines against the recognised character stream.
 *
 * 1. [buildCharTimeline] spreads the recognised characters over *speech* time (silence
 *    excluded), which keeps long pauses from stretching the timings.
 * 2. A Needleman-Wunsch pass matches the user's characters to the recognised ones, which
 *    tolerates ASR errors, traditional/simplified differences and small text edits.
 * 3. Line times are taken from the matched characters, gaps are interpolated and every
 *    line start is snapped to a nearby speech edge.
 */
object TextAligner {

    private const val SNAP_SEC = 1.0
    private const val PUNCT_WEIGHT = 0.08f
    private const val MAX_DP_CELLS = 40_000_000L

    fun splitLines(raw: String): List<String> {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1) return lines
        val single = lines.firstOrNull() ?: return emptyList()
        if (single.length <= 48) return listOf(single)
        // a single pasted paragraph: fall back to sentence splitting
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in single) {
            sb.append(ch)
            if (ch in "。！？!?；;") {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return if (out.isEmpty()) listOf(single) else out
    }

    /**
     * Spreads [texts] (one per ASR window) over the speech regions inside that window.
     * Every character gets a start and an end time; a character never spans a pause.
     */
    fun buildCharTimeline(
        texts: List<String>,
        windows: List<Pair<Double, Double>>,
        regions: List<SpeechRegion>,
    ): List<RecChar> {
        val out = ArrayList<RecChar>(texts.sumOf { it.length })
        for ((index, text) in texts.withIndex()) {
            if (text.isEmpty()) continue
            val (ws, we) = windows[index]
            val local = regions
                .filter { it.end > ws && it.start < we }
                .map { SpeechRegion(max(ws, it.start), min(we, it.end)) }
                .ifEmpty { listOf(SpeechRegion(ws, we)) }

            val total = local.sumOf { it.end - it.start }
            val weights = FloatArray(text.length) { if (text[it].isLetterOrDigit()) 1f else PUNCT_WEIGHT }
            var weightSum = 0f
            for (w in weights) weightSum += w
            if (weightSum <= 0f) weightSum = 1f

            // cumulative speech time at the start of every region
            val cumStart = DoubleArray(local.size)
            val cumEnd = DoubleArray(local.size)
            var acc = 0.0
            for ((i, r) in local.withIndex()) {
                cumStart[i] = acc
                cumEnd[i] = acc + (r.end - r.start)
                acc = cumEnd[i]
            }

            var walked = 0f
            for ((i, ch) in text.withIndex()) {
                val t0 = speechTime(cumStart, cumEnd, local, total * walked / weightSum)
                walked += weights[i]
                var t1 = speechTime(cumStart, cumEnd, local, total * walked / weightSum)
                // never let one character swallow a pause
                for (k in local.indices) {
                    if (t0 <= local[k].end + 1e-6) {
                        t1 = min(t1, local[k].end)
                        break
                    }
                }
                out.add(RecChar(ch, min(t0, t1), max(t0, t1)))
            }
        }
        return out
    }

    private fun speechTime(
        cumStart: DoubleArray,
        cumEnd: DoubleArray,
        regions: List<SpeechRegion>,
        target: Double,
    ): Double {
        for (k in regions.indices) {
            if (target <= cumEnd[k]) {
                return regions[k].start + max(0.0, target - cumStart[k])
            }
        }
        val k = regions.size - 1
        return regions[k].start + (cumEnd[k] - cumStart[k])
    }

    fun align(
        lines: List<String>,
        chars: List<RecChar>,
        regions: List<SpeechRegion>,
        t2s: Map<Char, Char>,
        duration: Double = 0.0,
    ): AlignResult {
        val uChars = StringBuilder()
        val uLine = ArrayList<Int>(lines.sumOf { it.length })
        for ((li, line) in lines.withIndex()) {
            for (ch in line) {
                val n = normalize(ch, t2s) ?: continue
                uChars.append(n)
                uLine.add(li)
            }
        }
        val rChars = StringBuilder()
        val rStart = ArrayList<Double>(chars.size)
        val rEnd = ArrayList<Double>(chars.size)
        for (c in chars) {
            val n = normalize(c.ch, t2s) ?: continue
            rChars.append(n)
            rStart.add(c.start)
            rEnd.add(c.end)
        }

        val n = uChars.length
        val m = rChars.length
        if (n == 0 || m == 0) {
            throw IllegalStateException("没有可用于对齐的文字（音频或文本为空）")
        }
        require(n.toLong() * m <= MAX_DP_CELLS) {
            "文本过长（${n} 字 / 识别 ${m} 字），请分成更短的片段再试"
        }

        val prev = IntArray(m + 1)
        val cur = IntArray(m + 1)
        for (j in 1..m) prev[j] = -j
        val trace = ByteArray((n + 1) * (m + 1))
        val rowSize = m + 1

        for (i in 1..n) {
            cur[0] = prev[0] - 1
            trace[i * rowSize] = 1
            val uc = uChars[i - 1]
            for (j in 1..m) {
                val diag = prev[j - 1] + if (uc == rChars[j - 1]) 2 else -1
                val up = prev[j] - 1
                val left = cur[j - 1] - 1
                var best = diag
                var op: Byte = 0
                if (up > best) {
                    best = up
                    op = 1
                }
                if (left > best) {
                    best = left
                    op = 2
                }
                cur[j] = best
                trace[i * rowSize + j] = op
            }
            System.arraycopy(cur, 0, prev, 0, rowSize)
        }

        var i = n
        var j = m
        val pairU = IntArray(min(n, m) + 1)
        val pairR = IntArray(min(n, m) + 1)
        var count = 0
        while (i > 0 && j > 0) {
            when (trace[i * rowSize + j].toInt()) {
                0 -> {
                    pairU[count] = i - 1
                    pairR[count] = j - 1
                    count++
                    i--
                    j--
                }
                1 -> i--
                else -> j--
            }
        }

        val minStart = DoubleArray(lines.size) { Double.MAX_VALUE }
        val maxEnd = DoubleArray(lines.size) { -Double.MAX_VALUE }
        val seen = BooleanArray(lines.size)
        var matched = 0
        for (k in count - 1 downTo 0) {
            val ui = pairU[k]
            val ri = pairR[k]
            if (uChars[ui] != rChars[ri]) continue
            matched++
            val li = uLine[ui]
            seen[li] = true
            minStart[li] = min(minStart[li], rStart[ri])
            maxEnd[li] = max(maxEnd[li], rEnd[ri])
        }
        if (matched == 0) {
            throw IllegalStateException("文本与音频内容不匹配：请确认音频里的语言，并在上方把「音频语言」改成对应语言后重试")
        }

        val spans = arrayOfNulls<DoubleArray>(lines.size)
        for (li in lines.indices) {
            if (seen[li]) spans[li] = doubleArrayOf(minStart[li], maxEnd[li])
        }

        // lines without any matched character: interpolate between neighbours
        for (li in lines.indices) {
            if (spans[li] != null) continue
            var prevEnd = 0.0
            for (k in li - 1 downTo 0) {
                val s = spans[k]
                if (s != null) {
                    prevEnd = s[1]
                    break
                }
            }
            var nextStart = Double.NaN
            for (k in li + 1 until lines.size) {
                val s = spans[k]
                if (s != null) {
                    nextStart = s[0]
                    break
                }
            }
            val lo = prevEnd + 0.02
            val hi = if (nextStart.isNaN()) prevEnd + 0.8 else nextStart - 0.02
            spans[li] = doubleArrayOf(lo, max(lo + 0.25, hi))
        }

        // a new line almost always starts where speech (re)starts: snap to the nearest onset
        val edges = ArrayList<Double>(regions.size)
        for (r in regions) edges.add(r.start)
        edges.sort()
        val ordered = spans.map { it!! }
        for (li in 1 until ordered.size) {
            val t = ordered[li][0]
            if (edges.isNotEmpty()) {
                var bestEdge = Double.NaN
                var bestDelta = Double.MAX_VALUE
                for (e in edges) {
                    val d = abs(e - t)
                    if (d < bestDelta) {
                        bestDelta = d
                        bestEdge = e
                    }
                }
                if (bestDelta <= SNAP_SEC) ordered[li][0] = bestEdge
            }
            ordered[li][0] = max(ordered[li][0], ordered[li - 1][0] + 0.05)
            ordered[li - 1][1] = min(ordered[li - 1][1], ordered[li][0] - 0.01)
        }
        // minimum line duration, but never past the next line's start
        for (li in ordered.indices) {
            val minEnd = ordered[li][0] + 0.2
            val cap = if (li + 1 < ordered.size) ordered[li + 1][0] - 0.01 else Double.MAX_VALUE
            ordered[li][1] = min(max(ordered[li][1], minEnd), max(minEnd, cap))
        }
        // keep everything inside the audio
        if (duration > 0.0) {
            for (s in ordered) {
                s[0] = min(max(0.0, s[0]), max(0.0, duration - 0.2))
                s[1] = min(max(s[1], s[0] + 0.2), max(s[0] + 0.2, duration))
            }
        }

        val result = lines.mapIndexed { index, text ->
            AlignedLine(text, ordered[index][0], ordered[index][1])
        }
        return AlignResult(result, matched, n)
    }

    fun normalize(ch: Char, t2s: Map<Char, Char>): Char? {
        val mapped = t2s[ch] ?: ch
        val lower = mapped.lowercaseChar()
        return if (lower.isLetterOrDigit()) lower else null
    }
}
