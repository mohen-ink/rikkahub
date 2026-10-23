package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.data.ai.tools.SSH_EXEC_TOOL
import me.rerere.rikkahub.data.ai.tools.SSH_LIST_CONNECTIONS_TOOL
import me.rerere.rikkahub.data.ai.tools.isExtremelyDangerousSshCommand
import me.rerere.rikkahub.data.repository.SshConnectionRepository
import me.rerere.rikkahub.data.ssh.SshConnectionInfo
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.ui.components.FormItem
import me.rerere.ui.richtext.DiffAddedColor
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TITLE_MAX_CHARS = 40
private const val SUMMARY_MAX_LINES = 8

object SshListConnectionsToolUI : ToolUIRenderer {
    override val toolName: String = SSH_LIST_CONNECTIONS_TOOL

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.CloudServer

    @Composable
    override fun title(context: ToolUIContext): String = "SSH 服务器"

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content?.jsonObjectOrNull?.get("connections") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val names = remember(context.content) {
            context.content?.jsonObjectOrNull
                ?.get("connections")
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { it.getStringContent("name") }
                .orEmpty()
        }
        if (names.isEmpty()) return
        Text(
            text = names.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

object SshExecToolUI : ToolUIRenderer {
    override val toolName: String = SSH_EXEC_TOOL

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command") ?: return "SSH 远程执行"
        val preview = command.replace("\n", " ").trim()
        val truncated = if (preview.length > TITLE_MAX_CHARS) preview.take(TITLE_MAX_CHARS) + "…" else preview
        return "SSH: $truncated"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content
        if (content != null) {
            val combined = remember(content) {
                listOf(content.getStringContent("stdout"), content.getStringContent("stderr"))
                    .filterNot { it.isNullOrBlank() }
                    .joinToString("\n")
                    .trim()
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SshExitStatus(content, MaterialTheme.typography.labelSmall)
                if (combined.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .shimmer(isLoading = context.loading),
                    ) {
                        Text(
                            text = combined.lineSequence().take(SUMMARY_MAX_LINES).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = SUMMARY_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            val connection = rememberSshConnection(context.arguments.getStringContent("connectionId"))
            val revision = context.arguments.getStringContent("connectionRevision")
            val command = context.arguments.getStringContent("command").orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SshTargetLine(connection, revision, MaterialTheme.typography.labelSmall)
                if (context.tool.isPending) {
                    SshApprovalReason(
                        dangerous = remember(command) { isExtremelyDangerousSshCommand(command) },
                        autoApprove = connection?.autoApproveCommands == true,
                    )
                }
                Text(
                    text = "目录：${context.arguments.getStringContent("cwd") ?: "服务器默认目录"} · " +
                        "超时：${context.arguments.getStringContent("timeoutSeconds") ?: "30"} 秒",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val connectionId = context.arguments.getStringContent("connectionId")
        val revision = context.arguments.getStringContent("connectionRevision")
        val connection = rememberSshConnection(connectionId)
        val command = context.arguments.getStringContent("command").orEmpty()
        val cwd = context.arguments.getStringContent("cwd")
        val timeout = context.arguments.getStringContent("timeoutSeconds")
        val content = context.content

        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "SSH 远程执行",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (content != null) {
                    SshExitStatus(content, MaterialTheme.typography.labelMedium)
                }
            }

            FormItem(label = { Text("连接") }) {
                SshTargetLine(connection, revision, MaterialTheme.typography.bodyMedium)
            }

            FormItem(label = { Text("命令") }) {
                HighlightCodeBlock(
                    code = buildString {
                        if (!cwd.isNullOrBlank()) appendLine("# cwd: $cwd")
                        if (!timeout.isNullOrBlank()) appendLine("# timeout: ${timeout}s")
                        append(command)
                    },
                    language = "bash",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (content != null) {
                val stdout = content.getStringContent("stdout").orEmpty()
                val stderr = content.getStringContent("stderr").orEmpty()
                if (stdout.isNotEmpty()) {
                    Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
                    HighlightCodeBlock(
                        code = stdout,
                        language = "plaintext",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (stderr.isNotEmpty()) {
                    Text(
                        text = "stderr",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    HighlightCodeBlock(
                        code = stderr,
                        language = "plaintext",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SshApprovalReason(dangerous: Boolean, autoApprove: Boolean) {
    when {
        dangerous -> Text(
            text = if (autoApprove) {
                "这条命令可能删除数据、格式化磁盘或关机重启，即使开了免确认也需要你确认"
            } else {
                "注意：这条命令可能删除数据、格式化磁盘或关机重启"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        !autoApprove -> Text(
            text = "这台服务器设置为每条命令都要确认",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SshTargetLine(
    connection: SshConnectionInfo?,
    expectedRevision: String?,
    style: androidx.compose.ui.text.TextStyle,
) {
    when {
        connection == null -> Text(
            text = "服务器不存在（可能已删除）",
            style = style,
            color = MaterialTheme.colorScheme.error,
        )

        expectedRevision != null && connection.revision != expectedRevision -> Text(
            text = "${connection.name} — ${connection.username}@${connection.host}:${connection.port} " +
                "（服务器信息已修改，这次调用会被拒绝）",
            style = style,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Text(
            text = "${connection.name} — ${connection.username}@${connection.host}:${connection.port}",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberSshConnection(connectionId: String?): SshConnectionInfo? {
    val repository: SshConnectionRepository = koinInject()
    val connections by repository.listFlow().collectAsState()
    val id = remember(connectionId) { connectionId?.let { runCatching { Uuid.parse(it) }.getOrNull() } }
    return connections.firstOrNull { it.id == id }
}

@Composable
private fun SshExitStatus(content: JsonElement, style: androidx.compose.ui.text.TextStyle) {
    val status = content.getStringContent("status") ?: "completed"
    val exitCode = content.jsonObjectOrNull?.get("exitCode")?.jsonPrimitiveOrNull
    val ok = status == "completed" && exitCode?.content == "0"
    Text(
        text = when (status) {
            "timeout" -> "超时（远程命令可能还在运行）"
            "interrupted" -> "连接中断（不确定命令是否执行完）"
            else -> "退出码 ${exitCode?.content ?: "?"}"
        },
        style = style,
        color = if (ok) DiffAddedColor else MaterialTheme.colorScheme.error,
    )
}
