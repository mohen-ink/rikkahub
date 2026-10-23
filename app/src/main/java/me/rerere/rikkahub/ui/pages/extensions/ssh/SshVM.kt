package me.rerere.rikkahub.ui.pages.extensions.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.SshConnectionNotFoundException
import me.rerere.rikkahub.data.repository.SshConnectionRepository
import me.rerere.rikkahub.data.repository.SshFingerprintNotTrustedException
import me.rerere.rikkahub.data.repository.SshRevisionMismatchException
import me.rerere.rikkahub.data.ssh.SshConnectionDraft
import me.rerere.rikkahub.data.ssh.SshConnectionInfo
import me.rerere.rikkahub.data.ssh.SshStorageCorruptException
import me.rerere.ssh.SshAuthException
import me.rerere.ssh.SshConnectException
import me.rerere.ssh.SshHostKeyInfo
import me.rerere.ssh.SshHostKeyMismatchException
import me.rerere.ssh.SshHostKeyProbeException
import kotlin.uuid.Uuid

data class SshUiMessage(val text: String, val isError: Boolean)

class SshFingerprintProbe(
    val connectionId: Uuid,
    val revision: String,
    val target: String,
    val hostKey: SshHostKeyInfo,
    val previousFingerprint: String?,
    /** 确认信任后是否接着测试登录 (「保存并连接」流程) */
    val testAfterTrust: Boolean,
) {
    val changed: Boolean get() = previousFingerprint != null && previousFingerprint != hostKey.fingerprint
}

class SshVM(
    private val repository: SshConnectionRepository,
) : ViewModel() {
    val connections = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _editing = MutableStateFlow<SshConnectionInfo?>(null)
    val editing: StateFlow<SshConnectionInfo?> = _editing.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    private val _listError = MutableStateFlow<String?>(null)
    val listError: StateFlow<String?> = _listError.asStateFlow()

    /** 保存、探测指纹、信任、测试中的任意一步在进行 */
    private val _busy = MutableStateFlow<SshBusyStep?>(null)
    val busy: StateFlow<SshBusyStep?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<SshUiMessage?>(null)
    val message: StateFlow<SshUiMessage?> = _message.asStateFlow()

    private val _testingIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val testingIds: StateFlow<Set<Uuid>> = _testingIds.asStateFlow()

    private val _probe = MutableStateFlow<SshFingerprintProbe?>(null)
    val probe: StateFlow<SshFingerprintProbe?> = _probe.asStateFlow()

    private var loadedKey: String? = null

    init {
        refreshConnections()
    }

    fun refreshConnections() {
        viewModelScope.launch {
            try {
                repository.list()
                _listError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _listError.value = e.toUserMessage("加载连接列表失败")
            }
        }
    }

    fun loadConnection(id: String?) {
        val key = id ?: "<new>"
        if (loadedKey == key) return
        loadedKey = key
        _probe.value = null
        if (id == null) {
            _editing.value = null
            _loadFailed.value = false
            _loading.value = false
            return
        }
        viewModelScope.launch {
            _loading.value = true
            try {
                val uuid = runCatching { Uuid.parse(id) }.getOrNull()
                val info = uuid?.let { repository.getById(it) }
                _editing.value = info
                _loadFailed.value = info == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editing.value = null
                _loadFailed.value = true
                _message.value = SshUiMessage(e.toUserMessage("加载连接失败"), isError = true)
            } finally {
                _loading.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun dismissProbe() {
        _probe.value = null
    }

    fun delete(id: Uuid) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SshUiMessage(e.toUserMessage("删除失败"), isError = true)
            }
        }
    }

    /**
     * 保存后直接接上连接流程: 指纹未信任或地址变了就探测并弹窗确认, 确认后测试登录;
     * 已信任时直接测试登录。任一步失败都停下并提示, 已保存的内容不回滚。
     */
    fun saveAndConnect(id: Uuid?, draft: SshConnectionDraft) {
        if (_busy.value != null) return
        _busy.value = SshBusyStep.SAVING
        viewModelScope.launch {
            try {
                val savedId = repository.save(id, draft)
                val saved = repository.getById(savedId) ?: throw SshConnectionNotFoundException(savedId.toString())
                _editing.value = saved
                loadedKey = savedId.toString()
                _loadFailed.value = false
                if (saved.trustedFingerprint == null) {
                    probeLocked(saved, testAfterTrust = true)
                } else {
                    testLocked(saved.id, successText = "已保存，连接正常")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SshUiMessage(e.toUserMessage("保存失败"), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    /** 重新探测并确认主机指纹 (服务器换过密钥时用) */
    fun reprobe(id: Uuid) {
        if (_busy.value != null) return
        viewModelScope.launch {
            try {
                val info = repository.getById(id) ?: throw SshConnectionNotFoundException(id.toString())
                probeLocked(info, testAfterTrust = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SshUiMessage(e.toUserMessage("读取服务器指纹失败"), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    private suspend fun probeLocked(info: SshConnectionInfo, testAfterTrust: Boolean) {
        _busy.value = SshBusyStep.PROBING
        val hostKey = repository.probeHostKey(info.id)
        if (info.trustedFingerprint == hostKey.fingerprint) {
            if (testAfterTrust) testLocked(info.id, successText = "连接正常")
            return
        }
        _probe.value = SshFingerprintProbe(
            connectionId = info.id,
            revision = info.revision,
            target = "${info.username}@${info.host}:${info.port}",
            hostKey = hostKey,
            previousFingerprint = info.trustedFingerprint,
            testAfterTrust = testAfterTrust,
        )
    }

    fun trustProbe() {
        val snapshot = _probe.value ?: return
        if (_busy.value != null) return
        _busy.value = SshBusyStep.TRUSTING
        viewModelScope.launch {
            try {
                repository.trustFingerprint(snapshot.connectionId, snapshot.revision, snapshot.hostKey)
                _editing.value = repository.getById(snapshot.connectionId)
                _probe.value = null
                if (snapshot.testAfterTrust) {
                    testLocked(snapshot.connectionId, successText = "已信任指纹，连接正常")
                } else {
                    _message.value = SshUiMessage("已信任该指纹", isError = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _probe.value = null
                _message.value = SshUiMessage(e.toUserMessage("信任指纹失败"), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    private suspend fun testLocked(id: Uuid, successText: String) {
        _busy.value = SshBusyStep.TESTING
        _testingIds.update { it + id }
        try {
            repository.testConnection(id)
            _message.value = SshUiMessage(successText, isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = SshUiMessage(e.toUserMessage("连接测试失败"), isError = true)
        } finally {
            _testingIds.update { it - id }
        }
    }

    fun testConnection(id: Uuid) {
        if (_busy.value != null || id in _testingIds.value) return
        viewModelScope.launch {
            try {
                testLocked(id, successText = "连接正常")
            } finally {
                _busy.value = null
            }
        }
    }
}

enum class SshBusyStep(val label: String) {
    SAVING("正在保存…"),
    PROBING("正在读取服务器指纹…"),
    TRUSTING("正在保存指纹…"),
    TESTING("正在测试登录…"),
}

internal fun Throwable.toUserMessage(fallback: String): String = when (this) {
    is SshAuthException -> "登录失败：用户名、密码或私钥不正确，也可能是私钥口令错误"
    is SshHostKeyMismatchException -> "服务器指纹与已信任的不一致，已拒绝连接。若服务器重装过，请点「重新确认指纹」"
    is SshHostKeyProbeException -> "没能读取服务器指纹，请检查主机地址和端口"
    is SshConnectException -> "连不上服务器，请检查主机地址、端口和网络"
    is SshFingerprintNotTrustedException -> "还没有信任服务器指纹，请先保存并确认指纹"
    is SshRevisionMismatchException -> "连接信息刚被修改过，请重试"
    is SshConnectionNotFoundException -> "连接不存在或已被删除"
    is SshStorageCorruptException -> "本机保存的 SSH 数据无法读取（可能是重装或系统密钥被清除），需要重新添加连接"
    is IllegalArgumentException -> when {
        message?.startsWith("password is required") == true -> "请填写密码"
        message?.startsWith("private key is required") == true -> "请填写或导入私钥"
        message?.startsWith("private key too large") == true -> "私钥文件过大"
        message?.startsWith("name") == true -> "名称不能为空，且不超过 128 个字符"
        message?.startsWith("host") == true -> "主机地址不能为空，也不能包含空格"
        message?.startsWith("port") == true -> "端口应在 1 到 65535 之间"
        message?.startsWith("username") == true -> "用户名不能为空，也不能包含空格"
        else -> fallback
    }

    else -> fallback
}
