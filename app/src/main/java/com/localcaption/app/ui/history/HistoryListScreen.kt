package com.localcaption.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcaption.app.data.db.SessionEntity
import com.localcaption.app.ui.common.MetaChip
import com.localcaption.app.ui.common.audioSourceLabel
import com.localcaption.app.ui.common.formatDuration
import com.localcaption.app.ui.common.formatSessionDate
import com.localcaption.app.ui.common.rememberAppContainer
import kotlinx.coroutines.launch

/**
 * Searchable list of recorded sessions. Tapping a row opens the detail screen, the
 * trailing button deletes a session after confirmation.
 */
@Composable
fun HistoryListScreen(onOpenSession: (Long) -> Unit) {
    val app = rememberAppContainer()
    val scope = rememberCoroutineScope()

    val sessions by app.transcriptionRepository.observeSessions()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SessionEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val filtered = remember(sessions, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            sessions
        } else {
            sessions.filter { it.title.contains(needle, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "历史记录",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索标题…") },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            },
            shape = RoundedCornerShape(16.dp),
        )

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when {
            sessions.isEmpty() -> EmptyHistory("还没有字幕记录，去「实时字幕」页开始一次录制吧")
            filtered.isEmpty() -> EmptyHistory("没有匹配「${query.trim()}」的记录")
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = filtered, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onClick = { onOpenSession(session.id) },
                        onDelete = { pendingDelete = session },
                    )
                }
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记录？") },
            text = {
                Text("「${session.title}」及其全部字幕、说话人信息都会被删除，且无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            runCatching {
                                app.transcriptionRepository.deleteSession(session.id)
                            }.onSuccess {
                                message = "已删除「${session.title}」"
                            }.onFailure {
                                message = "删除失败：${it.message ?: "未知错误"}"
                            }
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ColumnScope.EmptyHistory(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val durationMs = session.endedAt?.let { it - session.startedAt }
        ?: (System.currentTimeMillis() - session.startedAt)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatSessionDate(session.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Row(
                modifier = Modifier.padding(end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaChip(
                    text = audioSourceLabel(session.audioSource),
                    icon = Icons.Filled.Mic,
                )
                MetaChip(
                    text = session.asrModel.displayName,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                MetaChip(
                    text = formatDuration(durationMs),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
