package com.shiningsprk.audiotimeline.asr

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Everything the engine needs that ships inside the APK. */
class AssetBundle private constructor(
    val melFilters: FloatArray,
    val tokenizer: WhisperTokenizer,
    val suppressTokens: IntArray,
    val t2s: T2sTable,
) {

    companion object {
        fun load(context: Context): AssetBundle = fromOpener { name ->
            context.assets.open(name)
        }

        /** Used by the JVM verification harness. */
        fun loadFromDirectory(dir: File): AssetBundle = fromOpener { name ->
            FileInputStream(File(dir, name))
        }

        private fun fromOpener(opener: (String) -> InputStream): AssetBundle {
            val mel = opener("mel_filters.bin").use { stream ->
                val bytes = stream.readBytes()
                val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val out = FloatArray(floats.remaining())
                floats.get(out)
                out
            }
            val tokenizer = opener("vocab.txt").use { vocab ->
                opener("byte_table.txt").use { table ->
                    WhisperTokenizer.load(
                        BufferedReader(InputStreamReader(vocab, Charsets.UTF_8)),
                        BufferedReader(InputStreamReader(table, Charsets.UTF_8)),
                    )
                }
            }
            val suppress = opener("suppress_tokens.txt").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    lines.mapNotNull { it.trim().toIntOrNull() }.toList().toIntArray()
                }
            }
            val t2s = opener("t2s.txt").use { stream ->
                T2sTable.load(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
            }
            return AssetBundle(mel, tokenizer, suppress, t2s)
        }

        const val MEL_FILTER_SIZE = 80 * 201
    }
}
