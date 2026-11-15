package com.shiningsprk.audiotimeline.align

/** One lyric line with its position in the audio. */
data class AlignedLine(val text: String, val start: Double, val end: Double)

data class AlignResult(
    val lines: List<AlignedLine>,
    val matchedChars: Int,
    val totalChars: Int,
) {
    /** share of characters that the acoustic model knows; a hint, not an accuracy score */
    val coverage: Float get() = if (totalChars == 0) 0f else matchedChars.toFloat() / totalChars
}
