package com.shiningsprk.audiotimeline.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Small windowed-sinc resampler (16 taps, Hann window).
 *
 * Audio has to reach Whisper at exactly 16 kHz; a naive linear interpolation would
 * alias badly for 44.1/48 kHz material.
 */
object Resampler {

    private const val TAPS = 16
    private const val HALF = TAPS / 2

    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate || input.isEmpty()) return input
        val ratio = inRate.toDouble() / outRate
        val outLength = (input.size / ratio).roundToInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        // cutoff as a fraction of the input sample rate (never above the input Nyquist)
        val cutoff = 0.5 * minOf(1.0, outRate.toDouble() / inRate) * 0.95

        for (j in 0 until outLength) {
            val center = j * ratio
            val base = kotlin.math.floor(center).toInt()
            var acc = 0.0
            var weightSum = 0.0
            for (k in base - HALF + 1..base + HALF) {
                val d = center - k
                if (abs(d) > HALF) continue
                val x = 2.0 * cutoff * d
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val window = 0.5 + 0.5 * cos(PI * d / HALF)
                val w = sinc * window
                val index = k.coerceIn(0, input.size - 1)
                acc += input[index] * w
                weightSum += w
            }
            out[j] = if (abs(weightSum) < 1e-9) 0f else (acc / weightSum).toFloat()
        }
        return out
    }
}

/** Growable float buffer used while decoding compressed audio. */
class FloatList(initialCapacity: Int = 1 shl 16) {
    private var data = FloatArray(initialCapacity)
    var size: Int = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun addAll(values: FloatArray, count: Int) {
        if (size + count > data.size) {
            var capacity = data.size
            while (capacity < size + count) capacity *= 2
            data = data.copyOf(capacity)
        }
        System.arraycopy(values, 0, data, size, count)
        size += count
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
