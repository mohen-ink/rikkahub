package me.rerere.rikkahub.ui.pages.extensions.ssh

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
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
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ssh.SshAuthType
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

private const val MAX_PRIVATE_KEY_BYTES = 256 * 1024

private val SecretKeyboard = KeyboardOptions(
    keyboardType = KeyboardType.Password,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)

private val PlainAsciiKeyboard = KeyboardOptions(
    keyboardType = KeyboardType.Uri,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)

@Composable
fun SshEditPage(id: String?, vm: SshVM = koinViewModel()) {
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    val busyStep by vm.busy.collectAsStateWithLifecycle()
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

    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var host by remember(editing?.id) { mutableStateOf(editing?.host ?: "") }
    var port by remember(editing?.id) { mutableStateOf(editing?.port?.toString() ?: "22") }
    var username by remember(editing?.id) { mutableStateOf(editing?.username ?: "") }
    var authType by remember(editing?.id) { mutableStateOf(editing?.authType ?: SshAuthType.PASSWORD) }
    var autoApproveCommands by remember(editing?.id) { mutableStateOf(editing?.autoApproveCommands ?: false) }
    // 凭据输入在每次保存成功后清空 (editing 变成新快照), 已保存的凭据不回显
    var password by remember(editing) { mutableStateOf("") }
    var privateKey by remember(editing) { mutableStateOf("") }
    var passphrase by remember(editing) { mutableStateOf("") }
    var clearPassphrase by remember(editing) { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var passphraseVisible by remember { mutableStateOf(false) }
    var importingKey by remember { mutableStateOf(false) }
    var confirmAutoApprove by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    val editingNow = editing
    val editingId = editingNow?.id
    val testing = editingId != null && editingId in testingIds
    val hasUnsavedChanges = if (editingNow == null) {
        name.isNotBlank() || host.isNotBlank() || username.isNotBlank() ||
            password.isNotEmpty() || privateKey.isNotEmpty() || passphrase.isNotEmpty()
    } else {
        name.trim() != editingNow.name || host.trim() != editingNow.host ||
            port.trim().toIntOrNull() != editingNow.port || username.trim() != editingNow.username ||
            authType != editingNow.authType || password.isNotEmpty() || privateKey.isNotEmpty() ||
            passphrase.isNotEmpty() || clearPassphrase || autoApproveCommands != editingNow.autoApproveCommands
    }

    BackHandler(enabled = hasUnsavedChanges && probe == null) {
        confirmLeave = true
    }

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
                    toaster.show("这个文件太大，不像是私钥，请确认选对了文件", type = ToastType.Error)
                } else {
                    try {
                        val text = bytes.decodeToString()
                        if (describePrivateKey(text) == null) {
                            toaster.show("这个文件看起来不是私钥（应以 -----BEGIN 开头）", type = ToastType.Error)
                        } else {
                            privateKey = text
                        }
                    } finally {
                        bytes.fill(0)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show("读取私钥文件失败", type = ToastType.Error)
            } finally {
                importingKey = false
            }
        }
    }

    val portValue = port.trim().toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    val busy = busyStep != null || loading || testing || importingKey
    val waitingForExisting = id != null && editingNow == null && !loadFailed
    val fieldsEnabled = !loading && !loadFailed && !busy
    val hasSecret = when (authType) {
        SshAuthType.PASSWORD -> password.isNotEmpty() || editingNow?.authType == SshAuthType.PASSWORD
        SshAuthType.PRIVATE_KEY -> privateKey.isNotBlank() || editingNow?.authType == SshAuthType.PRIVATE_KEY
    }
    val canSave = !loadFailed && !busy && !waitingForExisting &&
        name.isNotBlank() && host.isNotBlank() && portValid && username.isNotBlank() && hasSecret

    fun submit() {
        if (!canSave) return
        vm.saveAndConnect(
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
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (id == null && editingNow == null) "添加 SSH 服务器" else "编辑 SSH 服务器") },
                navigationIcon = {
                    if (hasUnsavedChanges) {
                        IconButton(onClick = { confirmLeave = true }) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.back))
                        }
                    } else {
                        BackButton()
                    }
                },
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
                            headlineContent = { Text("正在加载…") },
                            trailingContent = { CircularProgressIndicator(modifier = Modifier.size(20.dp)) },
                        )
                    }
                }
            }

            if (loadFailed) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("这个服务器已经不存在了") },
                            supportingContent = { Text("可能已在别处被删除，请返回列表") },
                            leadingContent = { CardGroupIcon(HugeIcons.Alert01) },
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("服务器") }) {
                    formItem {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("名称") },
                            placeholder = { Text("例如：家里的 NAS") },
                            supportingText = { Text("AI 用这个名称区分服务器") },
                            singleLine = true,
                            enabled = fieldsEnabled,
                        )
                    }
                    formItem {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("地址") },
                            placeholder = { Text("IP 或域名，例如 192.168.1.10") },
                            singleLine = true,
                            enabled = fieldsEnabled,
                            keyboardOptions = PlainAsciiKeyboard,
                        )
                    }
                    formItem {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it.trim() },
                                modifier = Modifier.weight(2f),
                                label = { Text("用户名") },
                                placeholder = { Text("例如 root") },
                                singleLine = true,
                                enabled = fieldsEnabled,
                                keyboardOptions = PlainAsciiKeyboard,
                            )
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                                modifier = Modifier.weight(1f),
                                label = { Text("端口") },
                                singleLine = true,
                                isError = port.isNotBlank() && !portValid,
                                enabled = fieldsEnabled,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("登录方式") }) {
                    formItem {
                        ConnectedChoiceButtons(
                            options = listOf(SshAuthType.PASSWORD, SshAuthType.PRIVATE_KEY),
                            selected = authType,
                            onSelect = { if (fieldsEnabled) authType = it },
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
                                placeholder = {
                                    if (editingNow?.authType == SshAuthType.PASSWORD) Text("已保存，不修改请留空")
                                },
                                singleLine = true,
                                enabled = fieldsEnabled,
                                keyboardOptions = SecretKeyboard,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        val savedKey = editingNow?.authType == SshAuthType.PRIVATE_KEY
                        val keySummary = describePrivateKey(privateKey)
                        item(
                            headlineContent = {
                                Text(
                                    when {
                                        privateKey.isNotBlank() && keySummary != null -> "已导入 $keySummary"
                                        privateKey.isNotBlank() -> "已粘贴的内容不像私钥"
                                        savedKey -> "已保存私钥"
                                        else -> "还没有私钥"
                                    }
                                )
                            },
                            supportingContent = {
                                Text(
                                    when {
                                        privateKey.isNotBlank() && keySummary == null -> "私钥应以 -----BEGIN 开头，请检查内容"
                                        privateKey.isNotBlank() -> "保存后替换原来的私钥"
                                        savedKey -> "不修改就不用重新导入"
                                        else -> "从文件导入，或从剪贴板粘贴"
                                    }
                                )
                            },
                            leadingContent = {
                                CardGroupIcon(
                                    if (privateKey.isNotBlank() && keySummary == null) HugeIcons.Alert01 else HugeIcons.Key01
                                )
                            },
                            trailingContent = if (privateKey.isNotBlank()) {
                                {
                                    IconButton(onClick = { privateKey = "" }, enabled = fieldsEnabled) {
                                        Icon(HugeIcons.Cancel01, contentDescription = "移除刚导入的私钥")
                                    }
                                }
                            } else null,
                        )
                        formItem {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { keyImportLauncher.launch(arrayOf("*/*")) },
                                    enabled = fieldsEnabled,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (importingKey) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                    } else {
                                        Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text("从文件导入")
                                }
                                PastePrivateKeyButton(
                                    enabled = fieldsEnabled,
                                    modifier = Modifier.weight(1f),
                                    onPasted = { text ->
                                        if (describePrivateKey(text) == null) {
                                            toaster.show("剪贴板里不是私钥（应以 -----BEGIN 开头）", type = ToastType.Error)
                                        } else {
                                            privateKey = text
                                        }
                                    },
                                )
                            }
                        }
                        val showPassphraseClear = savedKey && editingNow?.hasPassphrase == true
                        formItem {
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = {
                                    passphrase = it
                                    if (it.isNotEmpty()) clearPassphrase = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("私钥口令（没有就留空）") },
                                placeholder = {
                                    if (showPassphraseClear && !clearPassphrase) Text("已保存，不修改请留空")
                                },
                                singleLine = true,
                                enabled = fieldsEnabled && !clearPassphrase,
                                keyboardOptions = SecretKeyboard,
                                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                                        Icon(
                                            if (passphraseVisible) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = if (passphraseVisible) "隐藏口令" else "显示口令",
                                        )
                                    }
                                },
                                supportingText = if (clearPassphrase) {
                                    { Text("保存时会删除已保存的口令") }
                                } else null,
                            )
                        }
                        if (showPassphraseClear) {
                            switchItem(
                                checked = clearPassphrase,
                                onCheckedChange = {
                                    clearPassphrase = it
                                    if (it) passphrase = ""
                                },
                                enabled = fieldsEnabled,
                                headlineContent = { Text("删除已保存的口令") },
                                supportingContent = { Text("换成了没有口令的私钥时打开") },
                            )
                        }
                    }
                }
            }

            if (editingNow != null) {
                item {
                    CardGroup(title = { Text("安全") }) {
                        item(
                            headlineContent = {
                                Text(if (editingNow.trustedFingerprint != null) "已信任服务器指纹" else "还没有确认服务器指纹")
                            },
                            supportingContent = {
                                Text(
                                    text = editingNow.trustedFingerprint?.let { fp ->
                                        listOfNotNull(editingNow.trustedKeyType, fp).joinToString("\n")
                                    } ?: "点下方「保存并连接」时会请你确认",
                                    fontFamily = if (editingNow.trustedFingerprint != null) FontFamily.Monospace else null,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = { CardGroupIcon(if (editingNow.trustedFingerprint != null) HugeIcons.ShieldCheck else HugeIcons.Alert01) },
                        )
                        if (editingNow.trustedFingerprint != null) {
                            item(
                                onClick = if (!busy && !hasUnsavedChanges) {
                                    { vm.reprobe(editingNow.id) }
                                } else null,
                                headlineContent = { Text("重新确认指纹") },
                                supportingContent = {
                                    Text(if (hasUnsavedChanges) "请先保存修改" else "服务器重装或更换过密钥后使用")
                                },
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("AI 执行命令") }) {
                    switchItem(
                        checked = autoApproveCommands,
                        onCheckedChange = { checked ->
                            if (checked) confirmAutoApprove = true else autoApproveCommands = false
                        },
                        enabled = fieldsEnabled,
                        headlineContent = { Text("不用每次确认") },
                        supportingContent = {
                            Text(
                                if (autoApproveCommands) {
                                    "普通命令直接执行；删除目录、格式化、关机重启等高危命令仍会先问你"
                                } else {
                                    "AI 每执行一条命令都要你点确认"
                                }
                            )
                        },
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { submit() },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busyStep != null || testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(busyStep?.label ?: SshBusyStep.TESTING.label)
                        } else {
                            Text(
                                when {
                                    editingNow == null -> "保存并连接"
                                    hasUnsavedChanges -> "保存并测试连接"
                                    else -> "测试连接"
                                }
                            )
                        }
                    }
                    if (!canSave && !busy && !loadFailed && !waitingForExisting) {
                        val missing = buildList {
                            if (name.isBlank()) add("名称")
                            if (host.isBlank()) add("地址")
                            if (username.isBlank()) add("用户名")
                            if (!portValid) add("正确的端口")
                            if (!hasSecret) add(if (authType == SshAuthType.PASSWORD) "密码" else "私钥")
                        }
                        if (missing.isNotEmpty()) {
                            Text(
                                text = "还需要填写：${missing.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    probe?.let { snapshot ->
        HostKeyDialog(
            probe = snapshot,
            trusting = busyStep == SshBusyStep.TRUSTING,
            onTrust = { vm.trustProbe() },
            onDismiss = { vm.dismissProbe() },
        )
    }

    RikkaConfirmDialog(
        show = confirmAutoApprove,
        title = "让 AI 直接执行命令？",
        confirmText = "开启",
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            autoApproveCommands = true
            confirmAutoApprove = false
        },
        onDismiss = { confirmAutoApprove = false },
    ) {
        Text(
            "开启后，AI 在这台服务器上执行普通命令不再询问你。高危命令（如删除目录、格式化磁盘、关机重启）" +
                "仍会先问你，但这只是尽力识别，换种写法可能识别不出来。\n\n" +
                "建议只对测试机或权限受限的账号开启。保存后生效。"
        )
    }

    RikkaConfirmDialog(
        show = confirmLeave,
        title = "放弃未保存的修改？",
        confirmText = "放弃",
        dismissText = "继续编辑",
        onConfirm = {
            confirmLeave = false
            navController.popBackStack()
        },
        onDismiss = { confirmLeave = false },
    ) {
        Text("刚才的修改还没有保存，离开后会丢失。")
    }
}

@Composable
private fun PastePrivateKeyButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPasted: (String) -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    FilledTonalButton(
        onClick = {
            scope.launch {
                val text = clipboard.getClipEntry()?.clipData
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(null)
                    ?.toString()
                    .orEmpty()
                onPasted(text)
            }
        },
        enabled = enabled,
        modifier = modifier,
    ) {
        Text("从剪贴板粘贴")
    }
}

@Composable
private fun HostKeyDialog(
    probe: SshFingerprintProbe,
    trusting: Boolean,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keyFile = probe.hostKey.type.removePrefix("ssh-").substringBefore('-').ifBlank { "ed25519" }
    AlertDialog(
        onDismissRequest = { if (!trusting) onDismiss() },
        icon = {
            Icon(
                if (probe.changed) HugeIcons.Alert01 else HugeIcons.Key01,
                contentDescription = null,
                tint = if (probe.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        },
        title = { Text(if (probe.changed) "服务器指纹变了" else "确认是这台服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (probe.changed) {
                    Text(
                        "${probe.target} 的指纹和之前信任的不一样。如果你最近重装过系统或更换过密钥，这是正常的；" +
                            "否则可能有人在冒充这台服务器，请不要继续。",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("第一次连接 ${probe.target}。在服务器上运行下面的命令，看输出里的指纹是否一致：")
                    Text(
                        text = "ssh-keygen -lf /etc/ssh/ssh_host_${keyFile}_key.pub",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "如果这台服务器是你自己刚装好的、在你自己的网络里，也可以直接信任。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${probe.hostKey.type}\n${probe.hostKey.fingerprint}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            if (probe.changed) {
                Button(
                    onClick = onTrust,
                    enabled = !trusting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("仍然信任新指纹") }
            } else {
                TextButton(onClick = onTrust, enabled = !trusting) { Text("一致，信任并连接") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !trusting) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** 从 PEM / OpenSSH 私钥头部认出类型, 不是私钥时返回 null; 不解析密钥内容 */
internal fun describePrivateKey(text: String): String? {
    val header = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    if (!header.startsWith("-----BEGIN ") || !header.endsWith("-----")) return null
    val kind = header.removePrefix("-----BEGIN ").removeSuffix("-----").trim()
    if (!kind.contains("PRIVATE KEY")) return null
    return when (kind) {
        "OPENSSH PRIVATE KEY" -> "OpenSSH 私钥"
        "RSA PRIVATE KEY" -> "RSA 私钥"
        "EC PRIVATE KEY" -> "ECDSA 私钥"
        "DSA PRIVATE KEY" -> "DSA 私钥"
        "ENCRYPTED PRIVATE KEY" -> "加密的 PKCS#8 私钥"
        else -> "私钥"
    }
}
