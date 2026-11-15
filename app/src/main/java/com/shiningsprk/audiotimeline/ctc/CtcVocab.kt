package com.shiningsprk.audiotimeline.ctc

import java.io.BufferedReader

/**
 * Character vocabulary of the CTC acoustic model.
 *
 * Only single character entries are addressable; ids 0..3 are the usual
 * `<pad>` / `<s>` / `</s>` / `<unk>` specials and are never emitted as text.
 */
class CtcVocab private constructor(
    private val tokens: Array<String>,
    private val charToId: Map<Char, Int>,
) {

    val size: Int get() = tokens.size
    val blank: Int = 0

    fun idOf(ch: Char): Int? = charToId[ch]

    fun token(id: Int): String? = tokens.getOrNull(id)

    companion object {
        fun load(reader: BufferedReader): CtcVocab {
            val tokens = ArrayList<String>(4096)
            reader.useLines { lines -> lines.forEach { tokens.add(it) } }
            val map = HashMap<Char, Int>(8192)
            tokens.forEachIndexed { id, tok ->
                if (tok.length == 1 && id > 0) map[tok[0]] = id
            }
            return CtcVocab(tokens.toTypedArray(), map)
        }
    }
}
