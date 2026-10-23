package me.rerere.rikkahub.ui.pages.extensions.ssh

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.ShieldCheck
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ssh.SshConnectionDraft
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ssh.SshAuthType
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

private const val MAX_PRIVATE_KEY_BYTES = 256 * 1024

@Composable
fun SshEditPage(id: String?, vm: SshVM = koinViewModel()) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val trusting by vm.trusting.collectAsStateWithLifecycle()
    val probing by vm.probing.collectAsStateWithLifecycle()
    val testingIds by vm.testingIds.collectAsStateWithLifecycle()
    val probe by vm.probe.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(id) {
        vm.loadConnection(id)
    }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            toaster.show(it.text, type = if (it.isError) ToastType.Error else ToastType.Success)
            vm.consumeMessage()
        }
    }

    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var host by remember(editing) { mutableStateOf(editing?.host ?: "") }
    var port by remember(editing) { mutableStateOf(editing?.port?.toString() ?: "22") }
    var username by remember(editing) { mutableStateOf(editing?.username ?: "") }
    var authType by remember(editing) { mutableStateOf(editing?.authType ?: SshAuthType.PASSWORD) }
    var password by remember(editing) { mutableStateOf("") }
    var privateKey by remember(editing) { mutableStateOf("") }
    var passphrase by remember(editing) { mutableStateOf("") }
    var clearPassphrase by remember(editing) { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var passphraseVisible by remember { mutableStateOf(false) }
    var importingKey by remember { mutableStateOf(false) }
    var autoApproveCommands by remember(editing) { mutableStateOf(editing?.autoApproveCommands ?: false) }

    val editingId = editing?.id
    val testing = editingId != null && editingId in testingIds
    val hasUnsavedChanges = editing?.let {
        name.trim() != it.name || host.trim() != it.host || port.trim().toIntOrNull() != it.port ||
            username.trim() != it.username || authType != it.authType ||
            password.isNotEmpty() || privateKey.isNotEmpty() || passphrase.isNotEmpty() || clearPassphrase ||
            autoApproveCommands != it.autoApproveCommands
    } ?: false

    val keyImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (importingKey) return@rememberLauncherForActivityResult
        importingKey = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(MAX_PRIVATE_KEY_BYTES + 1)
                        try {
                            var total = 0
                            while (total < buffer.size) {
                                val read = input.read(buffer, total, buffer.size - total)
                                if (read <= 0) break
                                total += read
                            }
                            if (total > MAX_PRIVATE_KEY_BYTES) null else buffer.copyOf(total)
                        } finally {
                            buffer.fill(0)
                        }
                    }
                }
                if (bytes == null) {
                    toaster.show("私钥文件过大（上限 256 KiB）", type = ToastType.Error)
                } else {
                    try {
                        privateKey = bytes.decodeToString()
                    } finally {
                        bytes.fill(0)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show("读取私钥失败", type = ToastType.Error)
            } finally {
                importingKey = false
            }
        }
    }

    val portValue = port.trim().toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    val busy = saving || loading || trusting || probing || testing || importingKey
    val waitingForExisting = id != null && editing == null && !loadFailed
    val canSave = !loadFailed && !busy && !waitingForExisting &&
        name.isNotBlank() && host.isNotBlank() && portValid && username.isNotBlank() &&
        when (authType) {
            SshAuthType.PASSWORD -> password.isNotEmpty() || editing?.authType == SshAuthType.PASSWORD
            SshAuthType.PRIVATE_KEY -> privateKey.isNotEmpty() || editing?.authType == SshAuthType.PRIVATE_KEY
        }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (editing == null) "添加 SSH 连接" else "编辑 SSH 连接") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loading) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("正在加载连接…") },
                            trailingContent = { CircularProgressIndicator(modifier = Modifier.size(20.dp)) },
                        )
                    }
                }
            }

            if (loadFailed) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("连接不可用") },
                            supportingContent = { Text("该连接不存在或已被删除，为避免误建重复连接，请先返回列表") },
                            leadingContent = { CardGroupIcon(HugeIcons.Cancel01) },
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("连接信息") }) {
                    formItem {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("名称") },
                            singleLine = true,
                            enabled = !loading && !loadFailed,
                        )
                    }
                    formItem {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("主机地址") },
                            singleLine = true,
                            enabled = !loading && !loadFailed,
                        )
                    }
                    formItem {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                                modifier = Modifier.weight(1f),
                                label = { Text("端口") },
                                singleLine = true,
                                isError = port.isNotBlank() && !portValid,
                                enabled = !loading && !loadFailed,
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                modifier = Modifier.weight(2f),
                                label = { Text("用户名") },
                                singleLine = true,
                                enabled = !loading && !loadFailed,
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("认证方式") }) {
                    formItem {
                        ConnectedChoiceButtons(
                            options = listOf(SshAuthType.PASSWORD, SshAuthType.PRIVATE_KEY),
                            selected = authType,
                            onSelect = { authType = it },
                        ) { option, selected ->
                            ConnectedChoiceLabel(
                                text = when (option) {
                                    SshAuthType.PASSWORD -> "密码"
                                    SshAuthType.PRIVATE_KEY -> "私钥"
                                },
                                selected = selected,
                                icon = if (selected) HugeIcons.Key01 else null,
                            )
                        }
                    }
                    if (authType == SshAuthType.PASSWORD) {
                        formItem {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("密码") },
                                singleLine = true,
                                enabled = !loading && !loadFailed,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                                    }
                                },
                                supportingText = {
                                    if (editing?.authType == SshAuthType.PASSWORD) {
                                        Text("已保存密码；留空保留原密码，填写则替换")
                                    }
                                },
                            )
                        }
                    } else {
                        formItem {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = privateKey,
                                    onValueChange = { privateKey = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("私钥内容") },
                                    minLines = 5,
                                    maxLines = 10,
                                    enabled = !loading && !loadFailed,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { keyVisible = !keyVisible }) {
                                            Icon(if (keyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                                        }
                                    },
                                    supportingText = {
                                        if (editing?.authType == SshAuthType.PRIVATE_KEY) {
                                            Text("已保存私钥；留空保留，填写或导入则替换")
                                        }
                                    },
                                )
                                FilledTonalButton(
                                    onClick = {
                                        keyImportLauncher.launch(arrayOf("*/*"))
                                    },
                                    enabled = !importingKey && !loading && !loadFailed,
                                ) {
                                    if (importingKey) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                    } else {
                                        Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                    Text(" 从文件导入私钥（≤256 KiB）")
                                }
                            }
                        }
                        formItem {
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = {
                                    passphrase = it
                                    if (it.isNotEmpty()) clearPassphrase = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("私钥口令（可选）") },
                                singleLine = true,
                                enabled = !clearPassphrase && !loading && !loadFailed,
                                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                                            Icon(if (passphraseVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                                        }
                                        if (passphrase.isNotEmpty() || clearPassphrase || editing != null) {
                                            IconButton(onClick = {
                                                passphrase = ""
                                                clearPassphrase = !clearPassphrase
                                            }) {
                                                Icon(HugeIcons.Cancel01, contentDescription = null)
                                            }
                                        }
                                    }
                                },
                                supportingText = {
                                    if (clearPassphrase) {
                                        Text("保存时将清除已保存的口令")
                                    } else if (editing != null) {
                                        Text("已保存的口令不回显；留空保留，点 × 标记清除")
                                    }
                                },
                            )
                        }
                    }
                }
            }

            val editingNow = editing
            if (editingNow != null) {
                item {
                    CardGroup(title = { Text("主机指纹") }) {
                        item(
                            headlineContent = { Text("信任的指纹") },
                            supportingContent = {
                                Text(
                                    text = editingNow.trustedFingerprint ?: "尚未信任，保存后请探测并确认",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.ShieldCheck) },
                        )
                        item(
                            onClick = if (!probing && !busy && !hasUnsavedChanges && editingId != null) {
                                { vm.probeFingerprint(editingId) }
                            } else null,
                            headlineContent = { Text("探测并确认指纹") },
                            supportingContent = {
                                Text(
                                    if (hasUnsavedChanges) "操作使用已保存的连接信息，修改后请先保存"
                                    else "首次连接前核对 SHA-256 指纹；指纹变化需重新确认，不会自动信任"
                                )
                            },
                            trailingContent = {
                                if (probing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                        item(
                            onClick = if (!testing && !busy && !hasUnsavedChanges && editingId != null) {
                                { vm.testConnection(editingId) }
                            } else null,
                            headlineContent = { Text("测试连接") },
                            supportingContent = {
                                Text(
                                    if (hasUnsavedChanges) "操作使用已保存的连接信息，修改后请先保存"
                                    else "仅验证认证，不执行远程命令；需要先保存并信任指纹"
                                )
                            },
                            trailingContent = {
                                if (testing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                }
            } else if (!loadFailed && !loading) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("指纹信任") },
                            supportingContent = { Text("保存后在本页探测并确认 SHA-256 主机指纹，之后才可测试连接与授权给 AI") },
                            leadingContent = { CardGroupIcon(HugeIcons.ShieldCheck) },
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("命令确认") }) {
                    switchItem(
                        checked = autoApproveCommands,
                        onCheckedChange = { autoApproveCommands = it },
                        enabled = !loading && !loadFailed,
                        supportingContent = {
                            Text(
                                if (autoApproveCommands) {
                                    "基础命令直接执行；rm -rf、格式化磁盘、写入块设备、关机重启等极度危险命令仍需手动确认"
                                } else {
                                    "每条命令都需要手动确认"
                                }
                            )
                        },
                        headlineContent = { Text("开启自动审批") },
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        if (!canSave) return@Button
                        vm.save(
                            id = editingId,
                            draft = SshConnectionDraft(
                                name = name.trim(),
                                host = host.trim(),
                                port = portValue ?: 22,
                                username = username.trim(),
                                authType = authType,
                                password = password.ifEmpty { null },
                                privateKey = privateKey.ifBlank { null },
                                passphrase = passphrase.ifEmpty { null },
                                clearPassphrase = clearPassphrase,
                                autoApproveCommands = autoApproveCommands,
                            ),
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }
    }

    probe?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { if (!trusting) vm.dismissProbe() },
            title = { Text(if (snapshot.changed) "主机指纹已变化" else "确认主机指纹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "目标：${snapshot.target}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (snapshot.changed) {
                        Text(
                            "新指纹与已信任指纹不同，可能是服务器重装/更换密钥，也可能是中间人攻击。确认无误后再信任。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("请与服务器管理员核对以下 SHA-256 主机指纹：", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        snapshot.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.trustProbe() },
                    enabled = !trusting,
                ) {
                    Text(if (snapshot.changed) "仍要信任" else "信任此指纹")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissProbe() }, enabled = !trusting) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
