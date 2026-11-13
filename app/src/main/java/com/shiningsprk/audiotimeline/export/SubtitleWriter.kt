package com.shiningsprk.audiotimeline.export

import com.shiningsprk.audiotimeline.align.AlignedLine
import java.util.Locale
import kotlin.math.roundToLong

/** Formats an alignment result as SRT / LRC / timestamped plain text. */
object SubtitleWriter {

    fun srt(lines: List<AlignedLine>): String {
        val sb = StringBuilder()
        lines.forEachIndexed { index, line ->
            sb.append(index + 1).append('\n')
            sb.append(srtTime(line.start)).append(" --> ").append(srtTime(line.end)).append('\n')
            sb.append(line.text).append('\n')
            if (index != lines.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }

    fun lrc(lines: List<AlignedLine>): String {
        val sb = StringBuilder()
        for (line in lines) {
            sb.append('[').append(lrcTime(line.start)).append(']').append(line.text).append('\n')
        }
        return sb.toString()
    }

    fun text(lines: List<AlignedLine>): String {
        val sb = StringBuilder()
        for (line in lines) {
            sb.append('[').append(shortTime(line.start)).append(" - ").append(shortTime(line.end)).append("] ")
            sb.append(line.text).append('\n')
        }
        return sb.toString()
    }

    fun srtTime(seconds: Double): String {
        val ms = (seconds * 1000).roundToLong().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    fun lrcTime(seconds: Double): String {
        val cs = (seconds * 100).roundToLong().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d.%02d", cs / 6000, cs / 100 % 60, cs % 100)
    }

    fun shortTime(seconds: Double): String {
        val ms = (seconds * 1000).roundToLong().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d.%02d", ms / 60_000, ms / 1000 % 60, ms % 1000 / 10)
    }
}
