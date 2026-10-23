package me.rerere.rikkahub.ui.pages.extensions.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.SshConnectionRepository
import me.rerere.rikkahub.data.ssh.SshConnectionDraft
import me.rerere.rikkahub.data.ssh.SshConnectionInfo
import kotlin.uuid.Uuid

data class SshUiMessage(val text: String, val isError: Boolean)

class SshFingerprintProbe(
    val connectionId: Uuid,
    val revision: String,
    val target: String,
    val fingerprint: String,
    val changed: Boolean,
)

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

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _trusting = MutableStateFlow(false)
    val trusting: StateFlow<Boolean> = _trusting.asStateFlow()

    private val _message = MutableStateFlow<SshUiMessage?>(null)
    val message: StateFlow<SshUiMessage?> = _message.asStateFlow()

    private val _testingIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val testingIds: StateFlow<Set<Uuid>> = _testingIds.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

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
                _listError.value = e.message ?: "加载连接列表失败"
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
                if (info == null) _message.value = SshUiMessage("连接不存在或已被删除", isError = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editing.value = null
                _loadFailed.value = true
                _message.value = SshUiMessage(e.message ?: "加载连接失败", isError = true)
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
                _message.value = SshUiMessage(e.message ?: "删除失败", isError = true)
            }
        }
    }

    fun save(id: Uuid?, draft: SshConnectionDraft, onSaved: () -> Unit = {}) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val savedId = repository.save(id, draft)
                _editing.value = repository.getById(savedId)
                _loadFailed.value = false
                _message.value = SshUiMessage("已保存", isError = false)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SshUiMessage(e.message ?: "保存失败", isError = true)
            } finally {
                _saving.value = false
            }
        }
    }

    fun probeFingerprint(id: Uuid) {
        if (_probing.value) return
        _probing.value = true
        viewModelScope.launch {
            try {
                val info = repository.getById(id)
                if (info == null) {
                    _message.value = SshUiMessage("连接不存在或已被删除", isError = true)
                    return@launch
                }
                val fingerprint = withContext(Dispatchers.IO) {
                    repository.probeFingerprint(id)
                }
                _probe.value = SshFingerprintProbe(
                    connectionId = info.id,
                    revision = info.revision,
                    target = "${info.username}@${info.host}:${info.port}",
                    fingerprint = fingerprint,
                    changed = info.trustedFingerprint != null && info.trustedFingerprint != fingerprint,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SshUiMessage(e.message ?: "指纹探测失败", isError = true)
            } finally {
                _probing.value = false
            }
        }
    }

    fun trustProbe() {
        val snapshot = _probe.value ?: return
        if (_trusting.value) return
        _trusting.value = true
        viewModelScope.launch {
            try {
                repository.trustFingerprint(snapshot.connectionId, snapshot.revision, snapshot.fingerprint)
                _editing.value = repository.getById(snapshot.connectionId)
                _probe.value = null
                _message.value = SshUiMessage("已信任该指纹", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SshUiMessage(e.message ?: "信任失败", isError = true)
            } finally {
                _trusting.value = false
            }
        }
    }

    fun setAutoApprove(id: Uuid, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setAutoApprove(id, enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SshUiMessage(e.message ?: "保存失败", isError = true)
            }
        }
    }

    fun testConnection(id: Uuid) {
        if (id in _testingIds.value) return
        _testingIds.update { it + id }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.testConnection(id)
                }
                _message.value = SshUiMessage("连接测试通过", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SshUiMessage(e.message ?: "连接测试失败", isError = true)
            } finally {
                _testingIds.update { it - id }
            }
        }
    }
}
