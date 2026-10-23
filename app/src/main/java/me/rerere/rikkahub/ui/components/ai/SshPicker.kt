package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.SshConnectionRepository
import me.rerere.rikkahub.data.ssh.SshConnectionInfo
import org.koin.compose.koinInject

@Composable
internal fun SshPickerListItem(
    assistant: Assistant,
    connections: List<SshConnectionInfo>,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val extantIds = connections.mapTo(HashSet()) { it.id }
    val selectedCount = assistant.sshConnectionIds.count { it in extantIds }

    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        modifier = modifier,
        leadingContent = {
            Icon(
                imageVector = HugeIcons.ComputerTerminal01,
                contentDescription = null,
            )
        },
        trailingContent = {
            if (selectedCount > 0) {
                CountBadge(count = selectedCount)
            }
        },
    ) {
        Text("SSH")
    }
}

@Composable
internal fun SshPickerPage(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    onBack: () -> Unit,
    onManage: () -> Unit,
) {
    val repository = koinInject<SshConnectionRepository>()
    val connections by repository.listFlow().collectAsStateWithLifecycle(emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = "SSH 服务器",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
        SshPicker(
            assistant = assistant,
            connections = connections,
            onUpdateAssistant = onUpdateAssistant,
            onManage = onManage,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        )
    }
}

@Composable
private fun SshPicker(
    assistant: Assistant,
    connections: List<SshConnectionInfo>,
    onUpdateAssistant: (Assistant) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val extantIds = connections.mapTo(HashSet()) { it.id }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(connections, key = { _, c -> c.id.toString() }) { index, connection ->
            val checked = connection.id in assistant.sshConnectionIds
            SegmentedListItem(
                checked = checked,
                onCheckedChange = { newChecked ->
                    val newIds = assistant.sshConnectionIds.toMutableSet()
                    if (newChecked) newIds.add(connection.id) else newIds.remove(connection.id)
                    newIds.retainAll(extantIds)
                    onUpdateAssistant(assistant.copy(sshConnectionIds = newIds))
                },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = connections.size),
                leadingContent = {
                    Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                },
                supportingContent = {
                    Text(
                        text = "${connection.username}@${connection.host}:${connection.port}" +
                            if (connection.trustedFingerprint == null) " · 还没确认指纹，暂时不能用" else "",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (connection.trustedFingerprint == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            androidx.compose.ui.graphics.Color.Unspecified
                        },
                    )
                },
                trailingContent = {
                    Switch(checked = checked, onCheckedChange = null)
                },
            ) {
                Text(connection.name)
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FilledTonalButton(onClick = onManage) {
                    Text("管理 SSH 服务器")
                }
            }
        }
    }
}
