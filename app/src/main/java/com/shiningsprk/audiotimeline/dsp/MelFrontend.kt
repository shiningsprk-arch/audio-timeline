package com.shiningsprk.audiotimeline.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Whisper's log-mel frontend, reimplemented to match `openai/whisper` exactly:
 * 16 kHz mono, 400 point STFT with a periodic Hann window, hop 160, 80 slaney mel
 * filters, log10, dynamic range clamp to -8 and the (x + 4) / 4 normalisation.
 *
 * The 30 s / 3000 frame layout matches what the ONNX encoder expects: [1, 80, 3000].
 */
object MelFrontend {

    const val SAMPLE_RATE = 16000
    const val N_FFT = 400
    const val HOP = 160
    const val N_MEL = 80
    const val N_FRAMES = 3000
    const val CHUNK_SAMPLES = 480000

    private const val N_BINS = N_FFT / 2 + 1

    private val window = FloatArray(N_FFT) {
        (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat()
    }
    private val dft = BluesteinDft(N_FFT)
    private val frame = FloatArray(N_FFT)
    private val power = FloatArray(N_BINS)
    private val chunk = FloatArray(CHUNK_SAMPLES)

    /**
     * @param samples mono float samples at 16 kHz
     * @param startSample index of the first sample of the 30 s chunk
     * @param filters 80 x 201 row major mel filter bank
     * @param out receives 80 * 3000 values, feature major (out[m * N_FRAMES + t])
     */
    fun compute(samples: FloatArray, startSample: Int, filters: FloatArray, out: FloatArray) {
        require(out.size >= N_MEL * N_FRAMES) { "output buffer too small" }
        java.util.Arrays.fill(chunk, 0f)
        val available = min(CHUNK_SAMPLES, max(0, samples.size - startSample))
        if (available > 0) {
            System.arraycopy(samples, startSample, chunk, 0, available)
        }

        java.util.Arrays.fill(out, 0f)
        for (t in 0 until N_FRAMES) {
            val base = t * HOP - N_FFT / 2
            for (k in 0 until N_FFT) {
                frame[k] = chunk[reflect(base + k)] * window[k]
            }
            dft.powerSpectrum(frame, power)
            for (m in 0 until N_MEL) {
                val row = m * N_BINS
                var acc = 0f
                for (k in 0 until N_BINS) {
                    acc += filters[row + k] * power[k]
                }
                out[m * N_FRAMES + t] = acc
            }
        }

        var peak = -1e30f
        for (i in out.indices) {
            val v = log10(max(out[i], 1e-10f))
            out[i] = v
            if (v > peak) peak = v
        }
        val floor = peak - 8.0f
        for (i in out.indices) {
            val v = max(out[i], floor)
            out[i] = (v + 4.0f) / 4.0f
        }
    }

    private fun reflect(index: Int): Int {
        var i = index
        val n = CHUNK_SAMPLES
        while (i < 0 || i >= n) {
            i = if (i < 0) -i else 2 * n - 2 - i
        }
        return i
    }
}
