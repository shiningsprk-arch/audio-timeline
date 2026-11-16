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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.shiningsprk.audiotimeline.export.SubtitleWriter
import com.shiningsprk.audiotimeline.history.HistoryRecord
import com.shiningsprk.audiotimeline.history.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AlignViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val history by vm.history.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                records = history,
                onOpen = { record ->
                    vm.openRecord(record)
                    scope.launch { drawerState.close() }
                },
                onDelete = vm::deleteRecord,
                onClear = vm::clearHistory,
            )
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("音频时间轴", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (result == null) "歌词 / 台词 · 中文 · 离线运行" else "对齐结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Rounded.Menu, contentDescription = "转换记录")
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
}

@Composable
private fun SetupScreen(
    state: AlignUiState,
    modifier: Modifier = Modifier,
    onPickAudio: () -> Unit,
    onTextChange: (String) -> Unit,
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
            "把歌词对准音频",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "选一段本地音频，粘贴歌词或台词，应用会在本机算出每一行的时间码。全程离线。",
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
                        if (state.audioDurationMs > 0) formatDuration(state.audioDurationMs)
                        else "支持 mp3 / m4a / wav / flac 等",
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
                Text("歌词", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 14,
                    placeholder = {
                        Text("每行一句，用换行分隔\n例如：\n主人 为何 你要遵循谬论 向她俯首称臣\n主人 为何 你要吞下悔恨 领受她的委任")
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "共 ${state.text.lines().count { it.isNotBlank() }} 行 · 目前只支持中文（含繁简混排）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.running || state.stage.isNotEmpty()) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(state.stage.ifBlank { "处理中" }, style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${(state.progress * 100).roundToInt()}% · 分离比较慢，请保持前台",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.error?.let { message ->
            SectionCard(background = MaterialTheme.colorScheme.errorContainer) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "出错了",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
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
            "全部计算都在手机上完成，音频和歌词不会上传。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )
    }
}

@Composable
private fun HistoryDrawer(
    records: List<HistoryRecord>,
    onOpen: (HistoryRecord) -> Unit,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("转换记录", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${records.size} 条 · 最多保留 ${HistoryStore.MAX_RECORDS} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (records.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有记录，完成一次对齐后会出现在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(records, key = { it.id }) { record ->
                        HistoryRow(record, onOpen, onDelete)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = onClear,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("清空全部记录")
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    record: HistoryRecord,
    onOpen: (HistoryRecord) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(record) }
            .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(record.audioName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                "${record.lineCount} 行 · ${formatDuration((record.durationSec * 1000).toLong())} · " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(record.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onDelete(record.id) }) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
