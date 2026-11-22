package com.localcaption.app.ui.history

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcaption.app.data.db.SegmentEntity
import com.localcaption.app.data.db.SpeakerEntity
import com.localcaption.app.data.prefs.AppSettings
import com.localcaption.app.export.ExportHelper
import com.localcaption.app.export.formatSrt
import com.localcaption.app.export.formatTxt
import com.localcaption.app.ui.common.MetaChip
import com.localcaption.app.ui.common.SpeakerBadge
import com.localcaption.app.ui.common.audioSourceLabel
import com.localcaption.app.ui.common.formatClock
import com.localcaption.app.ui.common.formatDuration
import com.localcaption.app.ui.common.formatSessionDate
import com.localcaption.app.ui.common.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session transcript with renaming, speaker renaming, inline segment editing and
 * SRT / TXT export or sharing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = rememberAppContainer()
    val scope = rememberCoroutineScope()

    val settings by app.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULTS)
    val session by app.transcriptionRepository.observeSession(sessionId)
        .collectAsStateWithLifecycle(initialValue = null)
    val segments by app.transcriptionRepository.observeSegments(sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val speakers by app.transcriptionRepository.observeSpeakers(sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showRenameSession by remember { mutableStateOf(false) }
    var sessionTitleDraft by remember { mutableStateOf("") }
    var speakerToRename by remember { mutableStateOf<SpeakerEntity?>(null) }
    var speakerLabelDraft by remember { mutableStateOf("") }
    var editingSegment by remember { mutableStateOf<SegmentEntity?>(null) }
    var segmentTextDraft by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val speakersMap = remember(speakers) { speakers.associate { it.id to it.label } }

    fun srtContent(): String =
        formatSrt(segments, speakersMap, includeSpeaker = settings.exportIncludeSpeaker)

    fun txtContent(): String = formatTxt(
        segments = segments,
        speakersMap = speakersMap,
        includeTimestamps = settings.exportIncludeTimestamps,
        includeSpeaker = settings.exportIncludeSpeaker,
    )

    fun baseFileName(extension: String): String =
        ExportHelper.buildExportFileName(session?.title ?: "字幕", extension)

    val srtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportHelper.MIME_SRT),
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ExportHelper.writeToUri(context, uri, payload.second)
                }
                message = result.fold(
                    onSuccess = { "已导出 SRT" },
                    onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                )
            }
        }
    }

    val txtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportHelper.MIME_TXT),
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ExportHelper.writeToUri(context, uri, payload.second)
                }
                message = result.fold(
                    onSuccess = { "已导出 TXT" },
                    onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                )
            }
        }
    }

    fun exportSrt() {
        if (segments.isEmpty()) {
            message = "没有可导出的字幕"
            return
        }
        val fileName = baseFileName("srt")
        pendingExport = fileName to srtContent()
        srtLauncher.launch(fileName)
    }

    fun exportTxt() {
        if (segments.isEmpty()) {
            message = "没有可导出的字幕"
            return
        }
        val fileName = baseFileName("txt")
        pendingExport = fileName to txtContent()
        txtLauncher.launch(fileName)
    }

    fun shareSrt() {
        if (segments.isEmpty()) {
            message = "没有可分享的字幕"
            return
        }
        val fileName = baseFileName("srt")
        val content = srtContent()
        val subject = session?.title ?: "字幕"
        scope.launch {
            val intent = withContext(Dispatchers.IO) {
                ExportHelper.createShareIntent(
                    context = context,
                    fileName = fileName,
                    content = content,
                    mimeType = ExportHelper.MIME_SRT,
                    subject = subject,
                )
            }
            val chooser = Intent.createChooser(intent, "分享字幕")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
                .onFailure { message = "分享失败：${it.message ?: "未知错误"}" }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = session?.title ?: "记录详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    session?.let {
                        Text(
                            text = formatSessionDate(it.startedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        sessionTitleDraft = session?.title.orEmpty()
                        showRenameSession = true
                    },
                ) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = "重命名")
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("导出 SRT") },
                            leadingIcon = {
                                Icon(Icons.Filled.Subtitles, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                exportSrt()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 TXT") },
                            leadingIcon = {
                                Icon(Icons.Filled.Description, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                exportTxt()
                            },
                        )
                    }
                }
                IconButton(onClick = { shareSrt() }) {
                    Icon(imageVector = Icons.Filled.Share, contentDescription = "分享")
                }
            },
        )

        session?.let { current ->
            val duration = current.endedAt?.let { it - current.startedAt }
                ?: (System.currentTimeMillis() - current.startedAt)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaChip(
                    text = audioSourceLabel(current.audioSource),
                    icon = Icons.Filled.Mic,
                )
                MetaChip(
                    text = current.asrModel.displayName,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                MetaChip(
                    text = formatDuration(duration),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message?.let { text ->
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (speakers.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = speakers, key = { it.id }) { speaker ->
                    AssistChip(
                        onClick = {
                            speakerToRename = speaker
                            speakerLabelDraft = speaker.label
                        },
                        label = { Text(speaker.label) },
                    )
                }
            }
        }

        HorizontalDivider()

        if (segments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "这次记录还没有字幕",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = segments, key = { it.id }) { segment ->
                    SegmentRow(
                        segment = segment,
                        speakerLabel = segment.speakerId?.let { speakersMap[it] },
                        onClick = {
                            editingSegment = segment
                            segmentTextDraft = segment.text
                        },
                    )
                }
            }
        }
    }

    if (showRenameSession) {
        AlertDialog(
            onDismissRequest = { showRenameSession = false },
            title = { Text("重命名记录") },
            text = {
                OutlinedTextField(
                    value = sessionTitleDraft,
                    onValueChange = { sessionTitleDraft = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = sessionTitleDraft.trim()
                        showRenameSession = false
                        if (title.isNotEmpty()) {
                            scope.launch {
                                runCatching {
                                    app.transcriptionRepository.renameSession(sessionId, title)
                                }.onSuccess {
                                    message = "已重命名"
                                }.onFailure {
                                    message = "重命名失败：${it.message ?: "未知错误"}"
                                }
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameSession = false }) { Text("取消") }
            },
        )
    }

    speakerToRename?.let { speaker ->
        AlertDialog(
            onDismissRequest = { speakerToRename = null },
            title = { Text("重命名说话人") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "该说话人的全部字幕都会使用新名字。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = speakerLabelDraft,
                        onValueChange = { speakerLabelDraft = it },
                        singleLine = true,
                        label = { Text("名称") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val label = speakerLabelDraft.trim()
                        speakerToRename = null
                        if (label.isNotEmpty()) {
                            scope.launch {
                                runCatching {
                                    app.transcriptionRepository.renameSpeaker(speaker.id, label)
                                }.onSuccess {
                                    message = "已重命名说话人"
                                }.onFailure {
                                    message = "重命名失败：${it.message ?: "未知错误"}"
                                }
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { speakerToRename = null }) { Text("取消") }
            },
        )
    }

    editingSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { editingSegment = null },
            title = { Text("编辑字幕") },
            text = {
                OutlinedTextField(
                    value = segmentTextDraft,
                    onValueChange = { segmentTextDraft = it },
                    label = { Text("文本") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = segmentTextDraft.trim()
                        editingSegment = null
                        if (text.isNotEmpty() && text != segment.text) {
                            scope.launch {
                                runCatching {
                                    app.transcriptionRepository.updateSegmentText(segment.id, text)
                                }.onSuccess {
                                    message = "已更新字幕"
                                }.onFailure {
                                    message = "更新失败：${it.message ?: "未知错误"}"
                                }
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSegment = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SegmentRow(
    segment: SegmentEntity,
    speakerLabel: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = formatClock(segment.startMs),
                modifier = Modifier.width(46.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val speaker = speakerLabel?.takeIf { it.isNotBlank() }
                if (speaker != null) {
                    SpeakerBadge(speaker)
                }
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
