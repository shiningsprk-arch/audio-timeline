package com.shiningsprk.audiotimeline.ctc

/** Line splitting helpers for pasted lyrics. */
object LyricText {

    fun splitLines(raw: String): List<String> {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1) return lines
        val single = lines.firstOrNull() ?: return emptyList()
        if (single.length <= 32) return listOf(single)
        // a single pasted paragraph: fall back to punctuation based splitting
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in single) {
            sb.append(ch)
            if (ch in "。！？!?") {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return if (out.isEmpty()) listOf(single) else out
    }
}
