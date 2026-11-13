package com.shiningsprk.audiotimeline.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal, allocation free FFT helpers.
 *
 * [Fft] is a classic iterative radix-2 Cooley-Tukey transform, [BluesteinDft] wraps it
 * to compute the DFT of an arbitrary length (Whisper uses a 400 point STFT, which is
 * 2^4 * 5^2 and therefore not a power of two).
 */
internal object Fft {

    fun forward(re: FloatArray, im: FloatArray, n: Int) = transform(re, im, n, false)

    fun inverse(re: FloatArray, im: FloatArray, n: Int) {
        transform(re, im, n, true)
        val scale = 1f / n
        for (i in 0 until n) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: FloatArray, im: FloatArray, n: Int, inverse: Boolean) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        val sign = if (inverse) 1.0 else -1.0
        while (len <= n) {
            val ang = 2.0 * PI / len * sign
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            val half = len shr 1
            var base = 0
            while (base < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until half) {
                    val a = base + k
                    val b = a + half
                    val vRe = re[b] * curRe - im[b] * curIm
                    val vIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - vRe
                    im[b] = im[a] - vIm
                    re[a] += vRe
                    im[a] += vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                base += len
            }
            len = len shl 1
        }
    }
}

/** Exact DFT of a fixed size via Bluestein's chirp-z algorithm. */
internal class BluesteinDft(val n: Int) {

    private val m: Int
    private val chirpRe = FloatArray(n)
    private val chirpIm = FloatArray(n)
    private val kernelRe: FloatArray
    private val kernelIm: FloatArray
    private val workRe: FloatArray
    private val workIm: FloatArray

    init {
        require(n > 1) { "dft size must be > 1" }
        var size = 1
        while (size < 2 * n - 1) size = size shl 1
        m = size

        val period = 2L * n
        for (k in 0 until n) {
            val kk = (k.toLong() * k) % period
            val angle = -PI * kk / n
            chirpRe[k] = cos(angle).toFloat()
            chirpIm[k] = sin(angle).toFloat()
        }

        kernelRe = FloatArray(m)
        kernelIm = FloatArray(m)
        kernelRe[0] = chirpRe[0]
        kernelIm[0] = -chirpIm[0]
        for (k in 1 until n) {
            kernelRe[k] = chirpRe[k]
            kernelIm[k] = -chirpIm[k]
            kernelRe[m - k] = chirpRe[k]
            kernelIm[m - k] = -chirpIm[k]
        }
        Fft.forward(kernelRe, kernelIm, m)

        workRe = FloatArray(m)
        workIm = FloatArray(m)
    }

    /**
     * Writes |DFT([x])[k]|^2 for k in 0..n/2 into [out].
     * [x] must have exactly [n] samples.
     */
    fun powerSpectrum(x: FloatArray, out: FloatArray) {
        java.util.Arrays.fill(workRe, 0f)
        java.util.Arrays.fill(workIm, 0f)
        for (i in 0 until n) {
            workRe[i] = x[i] * chirpRe[i]
            workIm[i] = x[i] * chirpIm[i]
        }
        Fft.forward(workRe, workIm, m)
        for (i in 0 until m) {
            val re = workRe[i] * kernelRe[i] - workIm[i] * kernelIm[i]
            val im = workRe[i] * kernelIm[i] + workIm[i] * kernelRe[i]
            workRe[i] = re
            workIm[i] = im
        }
        Fft.inverse(workRe, workIm, m)
        for (k in 0..n / 2) {
            val cre = chirpRe[k]
            val cim = chirpIm[k]
            val re = workRe[k] * cre - workIm[k] * cim
            val im = workRe[k] * cim + workIm[k] * cre
            out[k] = re * re + im * im
        }
    }
}
