package com.shiningsprk.audiotimeline.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shiningsprk.audiotimeline.align.AlignResult
import com.shiningsprk.audiotimeline.audio.AudioDecoder
import com.shiningsprk.audiotimeline.ctc.CtcAssets
import com.shiningsprk.audiotimeline.ctc.LyricPipeline
import com.shiningsprk.audiotimeline.ctc.LyricText
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
    val separateVocals: Boolean = false,
    val running: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,
    val result: AlignResult? = null,
    val audioDurationSec: Double = 0.0,
    val elapsedSec: Double = 0.0,
    val error: String? = null,
) {
    val canRun: Boolean get() = audioUri != null && text.isNotBlank() && !running
}

class AlignViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AlignUiState())
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

    fun onSeparateChanged(enabled: Boolean) {
        _state.update { it.copy(separateVocals = enabled) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun resetResult() {
        _state.update { it.copy(result = null, progress = 0f, stage = "") }
    }

    fun run() {
        val current = _state.value
        val uri = current.audioUri ?: return
        if (!current.canRun) return
        val context = getApplication<Application>()
        val started = System.currentTimeMillis()

        viewModelScope.launch {
            _state.update {
                it.copy(running = true, error = null, result = null, progress = 0f, stage = "准备中", elapsedSec = 0.0)
            }
            try {
                val lines = LyricText.splitLines(current.text)
                if (lines.isEmpty()) throw IllegalStateException("请先粘贴要打轴的歌词")

                val assets = withContext(Dispatchers.IO) { CtcAssets.load(context) }
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val pipeline = LyricPipeline(assets, threads)
                val output = withContext(Dispatchers.Default) {
                    pipeline.run(
                        context = context,
                        uri = uri,
                        lines = lines,
                        separate = current.separateVocals,
                        onStage = { stage -> _state.update { it.copy(stage = stage) } },
                        onProgress = { p -> _state.update { it.copy(progress = p) } },
                    )
                }
                _state.update {
                    it.copy(
                        running = false,
                        result = output.result,
                        audioDurationSec = output.durationSec,
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
}
