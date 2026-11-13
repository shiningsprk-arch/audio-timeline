package com.shiningsprk.audiotimeline.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shiningsprk.audiotimeline.asr.Models
import com.shiningsprk.audiotimeline.export.SubtitleWriter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AlignViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.onAudioPicked(uri)
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val lines = state.result?.lines
        if (uri != null && lines != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(SubtitleWriter.srt(lines).toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    val result = state.result
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("音频时间轴", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (result == null) "本地音频 · 文本对齐 · 离线运行" else "对齐结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (result == null) {
            SetupScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onPickAudio = { picker.launch(arrayOf("audio/*")) },
                onTextChange = vm::onTextChanged,
                onModelSelect = vm::onModelSelected,
                onLanguageSelect = vm::onLanguageSelected,
                onDownload = vm::downloadModel,
                onRun = vm::run,
                onDismissError = vm::clearError,
            )
        } else {
            ResultScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onBack = vm::resetResult,
                onShare = { format ->
                    val body = when (format) {
                        "srt" -> SubtitleWriter.srt(result.lines)
                        "lrc" -> SubtitleWriter.lrc(result.lines)
                        else -> SubtitleWriter.text(result.lines)
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, state.audioName.ifBlank { "时间轴" })
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享时间轴"))
                },
                onSave = {
                    val base = state.audioName.substringBeforeLast('.').ifBlank { "timeline" }
                    saver.launch("$base.srt")
                },
            )
        }
    }
}

@Composable
private fun SetupScreen(
    state: AlignUiState,
    modifier: Modifier = Modifier,
    onPickAudio: () -> Unit,
    onTextChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
    onLanguageSelect: (String) -> Unit,
    onDownload: () -> Unit,
    onRun: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "把文稿对准音频",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "选一段本地音频，粘贴它的文稿或歌词，应用会在本机识别语音并算出每一行的时间码。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.audioName.ifBlank { "还没有选择音频" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        if (state.audioDurationMs > 0) formatDuration(state.audioDurationMs) else "支持 mp3 / m4a / wav / flac 等",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onPickAudio) {
                    Text(if (state.audioUri == null) "选择" else "更换")
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文稿", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 7,
                    maxLines = 12,
                    placeholder = {
                        Text("每行一句，用换行分隔\n例如：\n第一句，我们在这条时间轴上开始。\n第二句，风吹过城市的屋顶。")
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "共 ${state.text.lines().count { it.isNotBlank() }} 行 · 建议一句一行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("识别模型", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (spec in Models.all) {
                        SelectablePill(
                            label = "${spec.label} · ${(spec.totalBytes / 1024 / 1024)}MB",
                            selected = state.modelId == spec.id,
                            onClick = { onModelSelect(spec.id) },
                        )
                    }
                }
                Text(
                    Models.byId(state.modelId).note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.modelReady) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "模型已就绪，可离线使用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(state.downloadProgress * 100).roundToInt()}% · ${state.downloadLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "首次使用需要下载模型（只下一次）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDownload) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("下载")
                        }
                    }
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("音频语言", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectablePill("自动检测", state.language == "auto") { onLanguageSelect("auto") }
                    SelectablePill("中文", state.language == "zh") { onLanguageSelect("zh") }
                    SelectablePill("English", state.language == "en") { onLanguageSelect("en") }
                    SelectablePill("日本語", state.language == "ja") { onLanguageSelect("ja") }
                }
            }
        }

        if (state.running || (state.stage.isNotEmpty() && state.result == null)) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(state.stage.ifBlank { "处理中" }, style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        state.error?.let { message ->
            SectionCard(background = MaterialTheme.colorScheme.errorContainer) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("出错了", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onDismissError) { Text("知道了") }
                }
            }
        }

        Button(
            onClick = onRun,
            enabled = state.canRun,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(if (state.running) "处理中…" else "开始对齐", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "全部计算都在手机上完成，音频和文本不会上传。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun SelectablePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = foreground, fontSize = 13.sp)
    }
}

@Composable
fun MonoText(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}
