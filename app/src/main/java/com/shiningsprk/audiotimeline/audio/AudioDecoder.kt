package com.shiningsprk.audiotimeline.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/** Decodes any container/codec the platform supports into 16 kHz mono floats. */
object AudioDecoder {

    data class Decoded(val samples: FloatArray, val sampleRate: Int) {
        val durationSec: Double get() = samples.size.toDouble() / sampleRate
    }

    /** Stereo channels at the file's native rate, used for vocal separation. */
    data class Stereo(val left: FloatArray, val right: FloatArray, val sampleRate: Int)

    fun decode(context: Context, uri: Uri): Decoded {
        val extractor = MediaExtractor()
        var afd: AssetFileDescriptor? = null
        var codec: MediaCodec? = null
        try {
            afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            val length = afd?.declaredLength ?: -1L
            if (afd != null && length >= 0) {
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, length)
            } else {
                // some content providers (cloud drives) cannot report a length
                extractor.setDataSource(context, uri, null)
            }

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("这个文件里没有音频轨")

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                // wav / raw pcm has no MediaCodec decoder, read the frames directly
                return decodeRawPcm(extractor, format)
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false
            val pcm = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        idle = 0
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            isFloat = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                        }
                        idle = 0
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idle++
                        if (idle > 500) throw IOException("解码超时，请换一个音频文件试试")
                    }

                    else -> {
                        if (outIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = decoder.getOutputBuffer(outIndex)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                if (isFloat) appendFloat(buffer, channels, pcm) else appendShort(buffer, channels, pcm)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            idle = 0
                        }
                    }
                }
            }

            val mono = pcm.toArray()
            if (mono.isEmpty()) throw IOException("没有解码出音频数据")
            val resampled = Resampler.resample(mono, sampleRate, TARGET_RATE)
            return Decoded(resampled, TARGET_RATE)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { afd?.close() }
        }
    }

    fun decodeStereo(context: Context, uri: Uri): Stereo {
        val extractor = MediaExtractor()
        var afd: AssetFileDescriptor? = null
        var codec: MediaCodec? = null
        try {
            afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            val length = afd?.declaredLength ?: -1L
            if (afd != null && length >= 0) {
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, length)
            } else {
                extractor.setDataSource(context, uri, null)
            }
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("这个文件里没有音频轨")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                return decodeRawPcmStereo(extractor, format)
            }

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false
            val leftList = FloatList()
            val rightList = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        idle = 0
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            isFloat = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                        }
                        idle = 0
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idle++
                        if (idle > 500) throw IOException("解码超时，请换一个音频文件试试")
                    }

                    else -> if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendStereo(buffer, channels, isFloat, leftList, rightList)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        idle = 0
                    }
                }
            }
            val l = leftList.toArray()
            val r = rightList.toArray()
            if (l.isEmpty()) throw IOException("没有解码出音频数据")
            return Stereo(l, r, sampleRate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { afd?.close() }
        }
    }

    private fun decodeRawPcm(extractor: MediaExtractor, format: MediaFormat): Decoded {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val buffer = java.nio.ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = FloatList()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> appendFloat(buffer, channels, pcm)
                AudioFormat.ENCODING_PCM_8BIT -> appendUnsigned8(buffer, channels, pcm)
                AudioFormat.ENCODING_PCM_16BIT -> appendShort(buffer, channels, pcm)
                else -> throw IOException("暂不支持这种 PCM 编码（$encoding），请换 mp3/m4a/flac")
            }
            extractor.advance()
        }
        val mono = pcm.toArray()
        if (mono.isEmpty()) throw IOException("没有解码出音频数据")
        return Decoded(Resampler.resample(mono, sampleRate, TARGET_RATE), TARGET_RATE)
    }

    private fun appendShort(buffer: java.nio.ByteBuffer, channels: Int, out: FloatList) {
        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = shorts.remaining() / channels
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += shorts.get(f * channels + c) / 32768f
            out.add(sum / channels)
        }
    }

    private fun decodeRawPcmStereo(extractor: MediaExtractor, format: MediaFormat): Stereo {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val buffer = java.nio.ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        val left = FloatList()
        val right = FloatList()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> appendStereo(buffer, channels, true, left, right)
                AudioFormat.ENCODING_PCM_16BIT -> appendStereo(buffer, channels, false, left, right)
                else -> throw IOException("这种 PCM 编码（$encoding）暂不支持分离模式，请换 mp3/m4a/flac")
            }
            extractor.advance()
        }
        val l = left.toArray()
        val r = right.toArray()
        if (l.isEmpty()) throw IOException("没有解码出音频数据")
        return Stereo(l, r, sampleRate)
    }

    private fun appendStereo(
        buffer: java.nio.ByteBuffer,
        channels: Int,
        isFloat: Boolean,
        left: FloatList,
        right: FloatList,
    ) {
        if (isFloat) {
            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frames = floats.remaining() / channels
            for (f in 0 until frames) {
                left.add(floats.get(f * channels))
                right.add(if (channels > 1) floats.get(f * channels + 1) else floats.get(f * channels))
            }
        } else {
            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val frames = shorts.remaining() / channels
            for (f in 0 until frames) {
                left.add(shorts.get(f * channels) / 32768f)
                right.add(
                    if (channels > 1) shorts.get(f * channels + 1) / 32768f
                    else shorts.get(f * channels) / 32768f
                )
            }
        }
    }

    private fun appendUnsigned8(buffer: java.nio.ByteBuffer, channels: Int, out: FloatList) {
        val frames = buffer.remaining() / channels
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val v = buffer.get(f * channels + c).toInt() and 0xFF
                sum += (v - 128) / 128f
            }
            out.add(sum / channels)
        }
    }

    private fun appendFloat(buffer: java.nio.ByteBuffer, channels: Int, out: FloatList) {
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = floats.remaining() / channels
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += floats.get(f * channels + c)
            out.add(sum / channels)
        }
    }

    fun durationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index) ?: "音频"
            }
        }
        return uri.lastPathSegment ?: "音频"
    }

    const val TARGET_RATE = 16000
}
