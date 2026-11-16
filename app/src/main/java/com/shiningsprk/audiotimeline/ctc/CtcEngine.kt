package com.shiningsprk.audiotimeline.ctc

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs the wav2vec2 CTC acoustic model over a whole recording and returns frame
 * posteriors for the forced aligner.
 *
 * The encoder is attention based, so 30 s chunks are used with a 5 s overlap; the
 * overlapping frames are dropped so the concatenated posteriors stay continuous in time.
 */
class CtcEngine(
    modelFile: File,
    private val threads: Int = 4,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputIsFp16: Boolean
    private val vocabSize: Int

    init {
        val opts = OrtSession.SessionOptions()
        session = try {
            opts.setIntraOpNumThreads(threads.coerceAtLeast(1))
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }
        inputName = session.inputNames.first()
        val outInfo = session.outputInfo.values.first().info as ai.onnxruntime.TensorInfo
        outputIsFp16 = outInfo.type == ai.onnxruntime.OnnxJavaType.FLOAT16
        vocabSize = outInfo.shape.lastOrNull()?.toInt()?.takeIf { it > 0 } ?: 3503
    }

    val frameSeconds: Double get() = FRAME_SEC

    fun posteriors(samples: FloatArray, onProgress: (Float) -> Unit = {}): CtcPosteriors {
        val totalSamples = samples.size
        val pieces = ArrayList<ShortArray>()
        var totalFrames = 0
        var startSample = 0
        val chunkSamples = (CHUNK_SEC * SAMPLE_RATE).toInt()
        val strideSamples = (STRIDE_SEC * SAMPLE_RATE).toInt()

        while (startSample < totalSamples) {
            val end = minOf(totalSamples, startSample + chunkSamples)
            val length = end - startSample
            if (length < SAMPLE_RATE / 5) break

            val chunk = FloatArray(length)
            System.arraycopy(samples, startSample, chunk, 0, length)
            var mean = 0.0
            for (v in chunk) mean += v
            mean /= length
            var variance = 0.0
            for (v in chunk) {
                val d = v - mean
                variance += d * d
            }
            val std = kotlin.math.sqrt(variance / length) + 1e-7
            for (i in chunk.indices) chunk[i] = ((chunk[i] - mean) / std).toFloat()

            val logits = runChunk(chunk)
            val frames = logits.size / vocabSize
            val fps = frames.toDouble() / (length.toDouble() / SAMPLE_RATE)
            val first = startSample == 0
            val keepFrom = if (first) 0 else minOf(Math.round(MARGIN_SEC * fps).toInt(), frames)

            val keepFrames = frames - keepFrom
            if (keepFrames > 0) {
                pieces.add(quantise(logits, keepFrom, keepFrames, vocabSize))
                totalFrames += keepFrames
            }
            onProgress(minOf(1f, end.toFloat() / totalSamples))
            if (end >= totalSamples) break
            startSample += strideSamples
        }

        // guard against a silently truncated run: the model outputs 50 frames per second
        val expectedFrames = totalSamples / 320.0
        require(kotlin.math.abs(totalFrames - expectedFrames) <= expectedFrames * 0.05) {
            "声学模型只处理了 $totalFrames 帧（应为 ${expectedFrames.toInt()}），请重试或换一个音频"
        }

        val data = ShortArray(totalFrames * vocabSize)
        var offset = 0
        for (piece in pieces) {
            System.arraycopy(piece, 0, data, offset, piece.size)
            offset += piece.size
        }
        return CtcPosteriors(totalFrames, vocabSize, data)
    }

    private fun runChunk(chunk: FloatArray): FloatArray {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, chunk.size.toLong()))
        try {
            val result = session.run(mapOf(inputName to input))
            try {
                val tensor = result.get(0) as OnnxTensor
                return if (outputIsFp16) {
                    val sb = tensor.getShortBuffer()
                    val out = FloatArray(sb.remaining())
                    for (i in out.indices) out[i] = halfToFloat(sb.get(i))
                    out
                } else {
                    val fb = tensor.floatBuffer
                    val out = FloatArray(fb.remaining())
                    fb.get(out)
                    out
                }
            } finally {
                result.close()
            }
        } finally {
            input.close()
        }
    }

    private fun quantise(logits: FloatArray, fromFrame: Int, frames: Int, vocab: Int): ShortArray {
        val out = ShortArray(frames * vocab)
        for (t in 0 until frames) {
            val base = (fromFrame + t) * vocab
            var maxV = -Float.MAX_VALUE
            for (v in 0 until vocab) {
                val x = logits[base + v]
                if (x > maxV) maxV = x
            }
            for (v in 0 until vocab) {
                val rel = (maxV - logits[base + v]).coerceIn(0f, 40f)
                out[t * vocab + v] = (rel / 40f * 65535f).toInt().toShort()
            }
        }
        return out
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SEC = 0.02
        private const val CHUNK_SEC = 30.0
        private const val STRIDE_SEC = 25.0
        const val MARGIN_SEC = 5.0

        fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = bits shr 15 and 0x1
            val exp = bits shr 10 and 0x1F
            val mant = bits and 0x3FF
            val value = when {
                exp == 0 -> if (mant == 0) 0f else (mant / 1024f) * Math.pow(2.0, -14.0).toFloat()
                exp == 31 -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + mant / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
            }
            return if (sign == 1) -value else value
        }
    }
}
