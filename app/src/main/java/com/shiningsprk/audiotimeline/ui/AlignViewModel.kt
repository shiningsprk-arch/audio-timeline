package com.shiningsprk.audiotimeline.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shiningsprk.audiotimeline.align.AlignResult
import com.shiningsprk.audiotimeline.align.TextAligner
import com.shiningsprk.audiotimeline.asr.AssetBundle
import com.shiningsprk.audiotimeline.asr.ModelStore
import com.shiningsprk.audiotimeline.asr.Models
import com.shiningsprk.audiotimeline.asr.Transcriber
import com.shiningsprk.audiotimeline.asr.WhisperEngine
import com.shiningsprk.audiotimeline.audio.AudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlignUiState(
    val audioUri: Uri? = null,
    val audioName: String = "",
    val audioDurationMs: Long = 0L,
    val text: String = "",
    val modelId: String = Models.BASE.id,
    val language: String = "auto",
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadLabel: String = "",
    val running: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,
    val result: AlignResult? = null,
    val audioDurationSec: Double = 0.0,
    val elapsedSec: Double = 0.0,
    val error: String? = null,
) {
    val canRun: Boolean
        get() = audioUri != null && text.isNotBlank() && !running && !downloading
}

class AlignViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ModelStore(application)
    private val _state = MutableStateFlow(AlignUiState(modelReady = store.isReady(Models.BASE)))
    val state: StateFlow<AlignUiState> = _state.asStateFlow()

    fun onAudioPicked(uri: Uri) {
        val context = getApplication<Application>()
        _state.update { it.copy(audioUri = uri, result = null, error = null) }
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) {
                runCatching {
                    AudioDecoder.displayName(context, uri) to AudioDecoder.durationMs(context, uri)
                }.getOrElse { uri.lastPathSegment.orEmpty() to 0L }
            }
            _state.update { it.copy(audioName = meta.first.ifBlank { "音频" }, audioDurationMs = meta.second) }
        }
    }

    fun onTextChanged(text: String) {
        _state.update { it.copy(text = text) }
    }

    fun onModelSelected(id: String) {
        val ready = store.isReady(Models.byId(id))
        _state.update { it.copy(modelId = id, modelReady = ready) }
    }

    fun onLanguageSelected(code: String) {
        _state.update { it.copy(language = code) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun resetResult() {
        _state.update { it.copy(result = null, progress = 0f, stage = "") }
    }

    fun downloadModel() {
        val spec = Models.byId(_state.value.modelId)
        if (_state.value.downloading) return
        viewModelScope.launch {
            _state.update { it.copy(downloading = true, downloadProgress = 0f, error = null, downloadLabel = "连接中") }
            try {
                store.ensure(spec) { progress, label ->
                    _state.update { it.copy(downloadProgress = progress, downloadLabel = label) }
                }
                _state.update {
                    it.copy(downloading = false, modelReady = true, downloadProgress = 1f)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update {
                    it.copy(downloading = false, error = "模型下载失败：${t.message}")
                }
            }
        }
    }

    fun run() {
        val current = _state.value
        val uri = current.audioUri ?: return
        if (!current.canRun) return
        val context = getApplication<Application>()
        val spec = Models.byId(current.modelId)
        val started = System.currentTimeMillis()

        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, result = null, progress = 0f, stage = "准备中", elapsedSec = 0.0) }
            try {
                if (!store.isReady(spec)) {
                    _state.update { it.copy(stage = "下载模型") }
                    store.ensure(spec) { progress, label ->
                        _state.update {
                            it.copy(
                                stage = "下载模型 ${(progress * 100).toInt()}%",
                                downloadProgress = progress,
                                downloadLabel = label,
                            )
                        }
                    }
                }
                _state.update { it.copy(modelReady = true) }

                _state.update { it.copy(stage = "解码音频") }
                if (current.audioDurationMs > MAX_AUDIO_MS) {
                    throw IllegalStateException(
                        "音频约 ${current.audioDurationMs / 60000} 分钟，超过当前版本支持的 20 分钟，请先裁剪成片段"
                    )
                }
                val decoded = withContext(Dispatchers.Default) { AudioDecoder.decode(context, uri) }
                _state.update { it.copy(progress = 0.05f, audioDurationSec = decoded.durationSec) }
                if (decoded.durationSec * 1000 > MAX_AUDIO_MS) {
                    throw IllegalStateException("音频约 ${(decoded.durationSec / 60).toInt()} 分钟，请先裁剪成 20 分钟以内的片段")
                }

                val bundle = withContext(Dispatchers.IO) { AssetBundle.load(context) }
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val engine = withContext(Dispatchers.IO) {
                    WhisperEngine(store.dir(spec), bundle.tokenizer, bundle.suppressTokens, threads)
                }
                val transcript = try {
                    val transcriber = Transcriber(engine, bundle.melFilters)
                    withContext(Dispatchers.Default) {
                        transcriber.transcribe(
                            samples = decoded.samples,
                            duration = decoded.durationSec,
                            language = current.language,
                            onStage = { stage -> _state.update { it.copy(stage = stage) } },
                            onProgress = { p -> _state.update { it.copy(progress = 0.05f + 0.85f * p) } },
                        )
                    }
                } finally {
                    // free the ONNX sessions before the DP table is allocated
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { engine.close() }
                }

                _state.update { it.copy(stage = "对齐文本", progress = 0.93f) }
                val lines = TextAligner.splitLines(current.text)
                if (lines.isEmpty()) throw IllegalStateException("请先粘贴要打轴的文本")
                val result = withContext(Dispatchers.Default) {
                    TextAligner.align(
                        lines = lines,
                        chars = transcript.chars,
                        regions = transcript.regions,
                        t2s = bundle.t2s.map(),
                        duration = decoded.durationSec,
                    )
                }
                _state.update {
                    it.copy(
                        running = false,
                        result = result,
                        progress = 1f,
                        stage = "完成",
                        elapsedSec = (System.currentTimeMillis() - started) / 1000.0,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        progress = 0f,
                        stage = "",
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    companion object {
        /** keeps memory use sane: decoding holds the whole clip as float samples */
        private const val MAX_AUDIO_MS = 20 * 60 * 1000L
    }
}
