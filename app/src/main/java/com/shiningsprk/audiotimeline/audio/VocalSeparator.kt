package com.shiningsprk.audiotimeline.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Vocal isolation with htdemucs exported to ONNX (waveform in, vocals out).
 *
 * The network works on 7.8 s stereo segments at 44.1 kHz; segments overlap by 25 % and
 * are cross-faded with a triangular window, which is what Demucs does internally too.
 */
class VocalSeparator(
    modelFile: File,
    private val threads: Int = 4,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

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
    }

    /**
     * @param left  left channel at 44.1 kHz
     * @param right right channel at 44.1 kHz
     * @return mono vocal estimate at 44.1 kHz
     */
    fun extract(left: FloatArray, right: FloatArray, onProgress: (Float) -> Unit = {}): FloatArray {
        val length = minOf(left.size, right.size)
        val out = FloatArray(length)
        val weight = FloatArray(length)
        var pos = 0
        while (pos < length) {
            val chunkL = FloatArray(SEGMENT)
            val chunkR = FloatArray(SEGMENT)
            val available = minOf(SEGMENT, length - pos)
            System.arraycopy(left, pos, chunkL, 0, available)
            System.arraycopy(right, pos, chunkR, 0, available)

            val vocals = runChunk(chunkL, chunkR)
            for (i in 0 until available) {
                val idx = pos + i
                val w = window(i)
                out[idx] += vocals[i] * w
                weight[idx] += w
            }
            onProgress(minOf(1f, (pos + SEGMENT).toFloat() / length))
            if (pos + SEGMENT >= length) break
            pos += HOP
        }
        for (i in 0 until length) {
            if (weight[i] > 1e-4f) out[i] /= weight[i]
        }
        return out
    }

    private fun runChunk(left: FloatArray, right: FloatArray): FloatArray {
        val interleaved = FloatArray(2 * SEGMENT)
        for (i in 0 until SEGMENT) {
            interleaved[2 * i] = left[i]
            interleaved[2 * i + 1] = right[i]
        }
        val input = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(interleaved),
            longArrayOf(1, 2, SEGMENT.toLong()),
        )
        try {
            val result = session.run(mapOf(inputName to input))
            try {
                val tensor = result.get(0) as OnnxTensor
                val fb = tensor.floatBuffer
                val data = FloatArray(fb.remaining())
                fb.get(data)
                // mono downmix of the two vocal channels
                val mono = FloatArray(SEGMENT)
                for (i in 0 until SEGMENT) {
                    val a = if (i < data.size / 2) data[i] else 0f
                    val b = if (SEGMENT + i < data.size) data[SEGMENT + i] else 0f
                    mono[i] = (a + b) * 0.5f
                }
                return mono
            } finally {
                result.close()
            }
        } finally {
            input.close()
        }
    }

    private fun window(i: Int): Float {
        val half = SEGMENT / 2f
        val d = kotlin.math.abs(i - half) / half
        return (1f - d).coerceAtLeast(0.02f)
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_RATE = 44100
        const val SEGMENT = 343980          // 7.8 s
        private const val HOP = 257985      // 75 % of the segment
    }
}
