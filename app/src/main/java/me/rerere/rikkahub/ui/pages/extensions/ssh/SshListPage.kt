package me.rerere.rikkahub.ui.pages.extensions.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ssh.SshConnectionInfo
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ssh.SshAuthType
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

@Composable
fun SshListPage(vm: SshVM = koinViewModel()) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val connections by vm.connections.collectAsStateWithLifecycle()
    val testingIds by vm.testingIds.collectAsStateWithLifecycle()
    val listError by vm.listError.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var deleteTarget by remember { mutableStateOf<SshConnectionInfo?>(null) }

    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            toaster.show(it.text, type = if (it.isError) ToastType.Error else ToastType.Success)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("SSH 服务器") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate(Screen.SshEdit(null)) }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (listError != null) {
                item {
                    CardGroup {
                        item(
                            onClick = { vm.refreshConnections() },
                            headlineContent = { Text("加载服务器列表失败") },
                            supportingContent = { Text("${listError} · 点按重试") },
                            leadingContent = {
                                CardGroupIcon(
                                    HugeIcons.Alert01,
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            },
                        )
                    }
                }
            }

            if (connections.isEmpty()) {
                item {
                    ExtensionEmptyState(
                        message = "还没有 SSH 服务器",
                        description = "添加后，在聊天输入框的「+」里勾选，AI 就能在这台服务器上执行命令",
                        icon = HugeIcons.ComputerTerminal01,
                    )
                }
            }

            items(connections, key = { it.id.toString() }) { connection ->
                SshConnectionItem(
                    connection = connection,
                    testing = connection.id in testingIds,
                    onOpen = { navController.navigate(Screen.SshEdit(connection.id.toString())) },
                    onTest = { vm.testConnection(connection.id) },
                    onDelete = { deleteTarget = connection },
                )
            }

            if (connections.isNotEmpty()) {
                item {
                    Text(
                        text = "密码和私钥只加密保存在本机，不会发给 AI，也不进备份；换手机或重装后需要重新添加。" +
                            "AI 执行命令的输出会发给模型，建议使用权限受限的账号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = "删除服务器",
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text("确定删除「${deleteTarget?.name ?: ""}」吗？保存的密码或私钥会一并删除，聊天中的 AI 将不能再使用它。")
    }
}

@Composable
private fun SshConnectionItem(
    connection: SshConnectionInfo,
    testing: Boolean,
    onOpen: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(HugeIcons.ComputerTerminal01)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = connection.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Tag(
                        type = if (connection.trustedFingerprint != null) TagType.SUCCESS else TagType.WARNING,
                    ) {
                        Text(if (connection.trustedFingerprint != null) "已就绪" else "待确认指纹")
                    }
                }
                Text(
                    text = "${connection.username}@${connection.host}:${connection.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when (connection.authType) {
                            SshAuthType.PASSWORD -> "密码登录"
                            SshAuthType.PRIVATE_KEY -> "私钥登录"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    if (connection.autoApproveCommands) {
                        Tag(type = TagType.WARNING) { Text("免确认") }
                    }
                }
            }
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = "编辑",
                        icon = HugeIcons.PencilEdit01,
                        onClick = onOpen,
                    ),
                    ItemAction(
                        text = if (connection.trustedFingerprint != null) "测试连接" else "去确认指纹",
                        icon = HugeIcons.Connect,
                        enabled = !testing,
                        onClick = if (connection.trustedFingerprint != null) onTest else onOpen,
                    ),
                    ItemAction(
                        text = "删除",
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}
