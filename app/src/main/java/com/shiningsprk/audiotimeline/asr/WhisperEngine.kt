package com.shiningsprk.audiotimeline.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer

/** Encoder output, kept together with its shape so tiny/base models both work. */
class Encoded(val data: FloatArray, val shape: LongArray)

/**
 * Thin wrapper around the three ONNX graphs exported for Whisper:
 * `encoder_model`, `decoder_model` (prompt pass) and `decoder_with_past_model`
 * (incremental pass with the KV cache).
 *
 * The cross-attention key/value tensors never change inside a 30 s window, so they are
 * borrowed from the first decoder result instead of being copied on every step (that
 * would be ~37 MB of copies per token for whisper-base).
 */
class WhisperEngine(
    modelDir: File,
    private val tokenizer: WhisperTokenizer,
    suppressTokens: IntArray,
    threads: Int = 4,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val decoderPast: OrtSession
    private val suppress = BooleanArray(WhisperTokenizer.VOCAB_SIZE)

    init {
        val opts = OrtSession.SessionOptions()
        try {
            opts.setIntraOpNumThreads(threads.coerceAtLeast(1))
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            encoder = env.createSession(File(modelDir, "encoder_model_quantized.onnx").absolutePath, opts)
            decoder = try {
                env.createSession(File(modelDir, "decoder_model_quantized.onnx").absolutePath, opts)
            } catch (t: Throwable) {
                encoder.close()
                throw t
            }
            decoderPast = try {
                env.createSession(File(modelDir, "decoder_with_past_model_quantized.onnx").absolutePath, opts)
            } catch (t: Throwable) {
                encoder.close()
                decoder.close()
                throw t
            }
        } finally {
            opts.close()
        }
        for (id in suppressTokens) {
            if (id in suppress.indices) suppress[id] = true
        }
    }

    fun encode(mel: FloatArray): Encoded {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, 80, 3000))
        try {
            val result = encoder.run(mapOf("input_features" to input))
            try {
                val out = result.get(0) as OnnxTensor
                val fb = out.floatBuffer
                val data = FloatArray(fb.remaining())
                fb.get(data)
                return Encoded(data, out.info.shape.copyOf())
            } finally {
                result.close()
            }
        } finally {
            input.close()
        }
    }

    /**
     * Greedy decode of a single 30 s window, returning the window text.
     *
     * Whisper's positional table only has [WhisperTokenizer.MAX_CONTEXT] rows, so the
     * prompt and the generated tokens together must stay below that: feeding position
     * 448 makes the position slice empty and the ONNX graph fails with a reshape error.
     * Timestamps are disabled (`<|notimestamps|>`) because timing comes from the VAD;
     * otherwise the model happily emits timestamp tokens until the context is exhausted.
     */
    fun transcribe(encoded: Encoded, languageToken: Int): String {
        val promptIds = longArrayOf(
            WhisperTokenizer.SOT.toLong(),
            languageToken.toLong(),
            WhisperTokenizer.TRANSCRIBE.toLong(),
            WhisperTokenizer.NO_TIMESTAMPS.toLong(),
        )
        val prompt = OnnxTensor.createTensor(env, arrayOf(promptIds))
        val hidden = OnnxTensor.createTensor(env, FloatBuffer.wrap(encoded.data), encoded.shape)
        val first = try {
            decoder.run(mapOf("input_ids" to prompt, "encoder_hidden_states" to hidden))
        } finally {
            prompt.close()
            hidden.close()
        }

        val encoderKv = HashMap<String, OnnxTensor>()
        val decoderKv = HashMap<String, OnnxTensor>()
        val bytes = ByteArrayOutputStream(256)
        try {
            var logits = readLogits(first)
            for ((name, value) in first) {
                if (name == "logits") continue
                val key = pastName(name)
                if (key.contains("encoder")) {
                    // borrowed from `first`, which stays open until the end of this call
                    encoderKv[key] = value as OnnxTensor
                } else {
                    decoderKv[key] = copyTensor(value as OnnxTensor)
                }
            }

            val maxNewTokens = (WhisperTokenizer.MAX_CONTEXT - promptIds.size).coerceAtLeast(1)
            var previous = -1
            var repeat = 0
            for (step in 0 until maxNewTokens) {
                val best = argmax(logits, step)
                if (best == WhisperTokenizer.EOT) break
                if (best == previous) {
                    repeat++
                    if (repeat > 12) break
                } else {
                    repeat = 0
                    previous = best
                }
                tokenizer.appendBytes(best, bytes)

                val ids = OnnxTensor.createTensor(env, arrayOf(longArrayOf(best.toLong())))
                val feed = HashMap<String, OnnxTensor>(encoderKv.size + decoderKv.size + 1)
                feed["input_ids"] = ids
                feed.putAll(encoderKv)
                feed.putAll(decoderKv)
                val result = try {
                    try {
                        decoderPast.run(feed)
                    } catch (e: ai.onnxruntime.OrtException) {
                        // never lose the text decoded so far because one step failed
                        break
                    }
                } finally {
                    ids.close()
                }
                try {
                    logits = readLogits(result)
                    for ((name, value) in result) {
                        if (name == "logits") continue
                        val key = pastName(name)
                        decoderKv[key]?.close()
                        decoderKv[key] = copyTensor(value as OnnxTensor)
                    }
                } finally {
                    result.close()
                }
            }
        } finally {
            for (tensor in decoderKv.values) tensor.close()
            first.close()
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** Detects the spoken language with a single decoder step on `<|startoftranscript|>`. */
    fun detectLanguage(encoded: Encoded): Int {
        val ids = OnnxTensor.createTensor(env, arrayOf(longArrayOf(WhisperTokenizer.SOT.toLong())))
        val hidden = OnnxTensor.createTensor(env, FloatBuffer.wrap(encoded.data), encoded.shape)
        try {
            val result = decoder.run(mapOf("input_ids" to ids, "encoder_hidden_states" to hidden))
            try {
                val scores = readLogits(result)
                var bestId = WhisperTokenizer.LANGUAGE_TOKENS.getValue("en")
                var bestScore = -Float.MAX_VALUE
                for (id in WhisperTokenizer.LANGUAGE_FIRST..WhisperTokenizer.LANGUAGE_LAST) {
                    if (scores[id] > bestScore) {
                        bestScore = scores[id]
                        bestId = id
                    }
                }
                return bestId
            } finally {
                result.close()
            }
        } finally {
            ids.close()
            hidden.close()
        }
    }

    private fun readLogits(result: OrtSession.Result): FloatArray {
        // `Result.get(String)` returns an Optional, so index based access is used here
        // (logits is the first output of every whisper decoder export)
        val tensor = result.get(0) as OnnxTensor
        val shape = tensor.info.shape
        val vocab = if (shape.isNotEmpty()) shape.last().toInt() else WhisperTokenizer.VOCAB_SIZE
        val fb = tensor.floatBuffer
        val all = FloatArray(fb.remaining())
        fb.get(all)
        if (all.size < vocab) return FloatArray(vocab)
        return all.copyOfRange(all.size - vocab, all.size)
    }

    private fun argmax(scores: FloatArray, step: Int): Int {
        var bestId = WhisperTokenizer.EOT
        var bestScore = -Float.MAX_VALUE
        for (id in scores.indices) {
            if (id < suppress.size && suppress[id]) continue
            // timestamps are disabled for this app, they would only eat the context
            if (id >= WhisperTokenizer.TIMESTAMP_BASE) continue
            if (step == 0 && id == 220) continue
            val s = scores[id]
            if (s > bestScore) {
                bestScore = s
                bestId = id
            }
        }
        return bestId
    }

    private fun copyTensor(tensor: OnnxTensor): OnnxTensor {
        val fb = tensor.floatBuffer
        val data = FloatArray(fb.remaining())
        fb.get(data)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), tensor.info.shape.copyOf())
    }

    private fun pastName(output: String): String = output.replace("present.", "past_key_values.")

    override fun close() {
        encoder.close()
        decoder.close()
        decoderPast.close()
    }
}
