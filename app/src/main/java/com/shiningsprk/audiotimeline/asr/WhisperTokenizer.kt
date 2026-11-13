package com.shiningsprk.audiotimeline.asr

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Whisper tokenizer: byte level BPE detokenisation only.
 *
 * The app never needs to *encode* text (the user's text is compared character by
 * character), so the vocabulary is stored as `assets/vocab.txt`, one byte-level token
 * per line, where the line number is the token id.
 */
class WhisperTokenizer private constructor(
    private val idToToken: Array<String>,
    private val byteOf: Map<Char, Int>,
) {

    fun appendBytes(id: Int, out: ByteArrayOutputStream) {
        if (id < 0 || id >= idToToken.size) return
        val token = idToToken[id]
        for (ch in token) {
            val b = byteOf[ch] ?: continue
            out.write(b)
        }
    }

    fun decode(ids: IntArray): String {
        val out = ByteArrayOutputStream(ids.size * 3)
        for (id in ids) appendBytes(id, out)
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    companion object {
        const val EOT = 50257
        const val SOT = 50258
        const val LANGUAGE_FIRST = 50259
        const val LANGUAGE_LAST = 50357
        const val TRANSCRIBE = 50359
        const val NO_TIMESTAMPS = 50363
        const val TIMESTAMP_BASE = 50364
        const val TIMESTAMP_MAX = 51864
        const val VOCAB_SIZE = 51865

        /** whisper's positional embedding table has exactly this many rows */
        const val MAX_CONTEXT = 448

        val LANGUAGE_TOKENS = mapOf(
            "en" to 50259,
            "zh" to 50260,
            "ja" to 50266,
            "ko" to 50264,
            "de" to 50261,
            "fr" to 50265,
            "es" to 50262,
            "ru" to 50263,
        )

        fun load(vocabReader: BufferedReader, byteTableReader: BufferedReader): WhisperTokenizer {
            val tokens = ArrayList<String>(50258)
            vocabReader.useLines { lines -> lines.forEach { tokens.add(it) } }
            val byteOf = HashMap<Char, Int>(512)
            byteTableReader.useLines { lines ->
                lines.forEach { line ->
                    val parts = line.trim().split(' ')
                    if (parts.size == 2) {
                        val cp = parts[0].toIntOrNull()
                        val b = parts[1].toIntOrNull()
                        if (cp != null && b != null) byteOf[cp.toChar()] = b
                    }
                }
            }
            return WhisperTokenizer(tokens.toTypedArray(), byteOf)
        }
    }
}
