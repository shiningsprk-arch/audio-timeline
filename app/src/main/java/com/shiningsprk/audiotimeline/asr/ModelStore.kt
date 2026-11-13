package com.shiningsprk.audiotimeline.asr

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ModelFile(val name: String, val bytes: Long)

data class ModelSpec(
    val id: String,
    val label: String,
    val note: String,
    val repo: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

object Models {

    val BASE = ModelSpec(
        id = "base",
        label = "Whisper Base",
        note = "约 127 MB · 中文效果可用，推荐",
        repo = "onnx-community/whisper-base",
        files = listOf(
            ModelFile("encoder_model_quantized.onnx", 23_201_314),
            ModelFile("decoder_model_quantized.onnx", 53_310_178),
            ModelFile("decoder_with_past_model_quantized.onnx", 50_075_136),
        ),
    )

    val TINY = ModelSpec(
        id = "tiny",
        label = "Whisper Tiny",
        note = "约 70 MB · 更快，精度一般",
        repo = "onnx-community/whisper-tiny",
        files = listOf(
            ModelFile("encoder_model_quantized.onnx", 10_124_990),
            ModelFile("decoder_model_quantized.onnx", 30_461_071),
            ModelFile("decoder_with_past_model_quantized.onnx", 29_220_476),
        ),
    )

    val all = listOf(BASE, TINY)

    fun byId(id: String): ModelSpec = all.firstOrNull { it.id == id } ?: BASE
}

/** Downloads the ONNX graphs once, into app private storage. */
class ModelStore(private val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    fun dir(spec: ModelSpec): File = File(File(filesDir, "models"), spec.id)

    fun isReady(spec: ModelSpec): Boolean {
        val dir = dir(spec)
        if (!File(dir, READY_MARKER).isFile) return false
        return spec.files.all { complete(File(dir, it.name), it) }
    }

    suspend fun ensure(spec: ModelSpec, onProgress: (Float, String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = dir(spec).apply { mkdirs() }
            val total = spec.totalBytes.toDouble().coerceAtLeast(1.0)
            var done = 0L

            for (file in spec.files) {
                val target = File(dir, file.name)
                if (complete(target, file)) {
                    done += file.bytes
                    onProgress((done / total).toFloat(), file.name)
                    continue
                }

                val part = File(dir, file.name + PART_SUFFIX)
                var lastError: Exception? = null
                var ok = false
                for (mirror in MIRRORS) {
                    val url = "$mirror/${spec.repo}/resolve/main/onnx/${file.name}"
                    try {
                        part.delete()
                        download(url, part) { read -> onProgress(((done + read) / total).toFloat(), file.name) }
                        if (complete(part, file)) {
                            ok = true
                            break
                        }
                        lastError = IOException("文件大小不符（${part.length()} / ${file.bytes} 字节）")
                    } catch (e: IOException) {
                        lastError = e
                    } catch (e: SecurityException) {
                        lastError = IOException(e.message)
                    }
                }
                if (!ok) {
                    part.delete()
                    throw IOException("下载 ${file.name} 失败：${lastError?.message ?: "网络不可用"}")
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                done += file.bytes
                onProgress((done / total).toFloat(), file.name)
            }

            // only mark the model as usable once every file matches the manifest exactly
            val broken = spec.files.firstOrNull { !complete(File(dir, it.name), it) }
            if (broken != null) throw IOException("模型文件校验失败：${broken.name}，请重试")
            File(dir, READY_MARKER).writeText("ok")
            dir
        }

    private suspend fun download(url: String, target: File, onBytes: (Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "AudioTimeline-Android/1.0")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val declared = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().buffered(1 shl 16).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onBytes(read)
                    }
                }
            }
            if (declared > 0 && read != declared) {
                throw IOException("下载中断（$read / $declared 字节）")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun remove(spec: ModelSpec) {
        dir(spec).deleteRecursively()
    }

    private fun complete(file: File, spec: ModelFile): Boolean = file.isFile && file.length() == spec.bytes

    companion object {
        private const val READY_MARKER = "ready.marker"
        private const val PART_SUFFIX = ".part"

        private val MIRRORS = listOf(
            "https://hf-mirror.com",
            "https://huggingface.co",
        )
    }
}
