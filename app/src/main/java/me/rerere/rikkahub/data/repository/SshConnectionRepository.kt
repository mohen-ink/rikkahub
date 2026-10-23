package me.rerere.rikkahub.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ssh.SshConnectionDraft
import me.rerere.rikkahub.data.ssh.SshConnectionInfo
import me.rerere.rikkahub.data.ssh.SshConnectionStore
import me.rerere.rikkahub.data.ssh.SshStoredConnection
import me.rerere.ssh.JschSshClient
import me.rerere.ssh.SshAuth
import me.rerere.ssh.SshAuthType
import me.rerere.ssh.SshCommandResult
import me.rerere.ssh.SshHostKeyInfo
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

/**
 * Named SSH connections with locally stored credentials.
 *
 * Secret material (password / private key / passphrase) lives only inside the
 * encrypted store and is resolved into [SshAuth] right before use; it never
 * appears on [SshConnectionInfo], in logs, or in errors.
 *
 * Concurrency: storage mutations are serialized inside [SshConnectionStore];
 * network operations run *outside* the storage mutex on an immutable snapshot
 * taken after validation. A concurrent edit or delete therefore does not
 * interrupt an operation that has already passed its checks — it only blocks
 * new operations from using stale state (the in-flight operation may continue
 * against the old target, which is documented and bounded by its timeout).
 */
class SshConnectionRepository(
    context: Context,
) {
    private val store = SshConnectionStore(context)
    private val client = JschSshClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    private val _connections = MutableStateFlow<List<SshConnectionInfo>>(emptyList())
    val connections: StateFlow<List<SshConnectionInfo>> = _connections

    // Serializes read+flow-publish pairs with mutations so a concurrent list()
    // can't publish a snapshot taken before an already-completed save/delete.
    private val flowMutex = Mutex()

    init {
        scope.launch {
            runCatching { refreshFlow() }
                .onFailure { Log.e(TAG, "failed to load SSH connections") }
        }
    }

    suspend fun list(): List<SshConnectionInfo> = flowMutex.withLock {
        val items = store.read().connections.map { it.toInfo() }.sortedBy { it.name.lowercase() }
        _connections.value = items
        items
    }

    fun listFlow(): StateFlow<List<SshConnectionInfo>> = connections

    suspend fun getById(id: Uuid): SshConnectionInfo? =
        store.read().connections.firstOrNull { it.id == id.toString() }?.toInfo()

    /**
     * Creates or updates a connection. Returns the connection id.
     *
     * - A null [SshConnectionDraft.password]/[SshConnectionDraft.privateKey]
     *   retains the stored secret when the auth type is unchanged; switching
     *   auth type always requires the matching new secret.
     * - [SshConnectionDraft.clearPassphrase] explicitly drops a stored key
     *   passphrase; a null passphrase otherwise retains it.
     * - Changing `host` or `port` invalidates the trusted fingerprint. The
     *   opaque revision rotates whenever the execution target changes (host,
     *   port, username, auth type or any secret) so pending approvals cannot be
     *   replayed against it; renaming or toggling auto-approval keeps it.
     */
    suspend fun save(id: Uuid?, draft: SshConnectionDraft): Uuid {
        validateDraftBasics(draft)
        val updated = store.mutate { file ->
            val existing = id?.let { target -> file.connections.firstOrNull { it.id == target.toString() } }
            if (id != null && existing == null) {
                throw SshConnectionNotFoundException(id.toString())
            }
            val merged = mergeDraft(existing, draft)
            val newList = if (existing == null) {
                file.connections + merged
            } else {
                file.connections.map { if (it.id == merged.id) merged else it }
            }
            file.copy(connections = newList) to merged
        }
        refreshFlow()
        return Uuid.parse(updated.id)
    }

    suspend fun delete(id: Uuid) {
        store.mutate { file ->
            file.copy(connections = file.connections.filterNot { it.id == id.toString() }) to Unit
        }
        refreshFlow()
    }

    /**
     * Connects to the server and returns its host key type and `SHA256:`
     * fingerprint without authenticating or sending any credentials.
     */
    suspend fun probeHostKey(id: Uuid): SshHostKeyInfo {
        val connection = requireConnection(id)
        return client.probeHostKey(connection.host, connection.port)
    }

    /**
     * Pins [fingerprint] after the user confirms it. Fails when
     * [expectedRevision] no longer matches (the connection was edited since
     * the fingerprint was presented) so a probe→trust pair can't be replayed
     * across edits.
     */
    suspend fun trustFingerprint(id: Uuid, expectedRevision: String, hostKey: SshHostKeyInfo) {
        val normalized = normalizeFingerprint(hostKey.fingerprint)
        store.mutate { file ->
            val current = file.connections.firstOrNull { it.id == id.toString() }
                ?: throw SshConnectionNotFoundException(id.toString())
            if (current.revision != expectedRevision) {
                throw SshRevisionMismatchException()
            }
            val updated = current.copy(
                trustedFingerprint = normalized,
                trustedKeyType = hostKey.type,
                revision = newRevision(),
            )
            file.copy(
                connections = file.connections.map { if (it.id == updated.id) updated else it }
            ) to Unit
        }
        refreshFlow()
    }

    /** Authenticates against the server; runs no command. Fails on untrusted/mismatched keys. */
    suspend fun testConnection(id: Uuid) {
        val connection = requireConnection(id)
        client.testAuthentication(
            host = connection.host,
            port = connection.port,
            username = connection.username,
            auth = connection.toAuth(),
            expectedFingerprint = requireTrustedFingerprint(connection),
        )
    }

    /**
     * Executes [command] as the stored user. [expectedRevision] must match the
     * revision observed when the call was approved — it is re-checked against
     * the store immediately before credentials are resolved and any network
     * connection is opened, so an edit, delete or fingerprint change between
     * approval and execution aborts instead of running against changed state.
     */
    suspend fun execute(
        id: Uuid,
        expectedRevision: String,
        command: String,
        cwd: String? = null,
        timeoutSeconds: Int = 30,
    ): SshCommandResult {
        validateCommand(command, cwd)
        val connection = requireConnection(id)
        if (connection.revision != expectedRevision) {
            throw SshRevisionMismatchException()
        }
        return client.execute(
            host = connection.host,
            port = connection.port,
            username = connection.username,
            auth = connection.toAuth(),
            expectedFingerprint = requireTrustedFingerprint(connection),
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
        )
    }

    private suspend fun requireConnection(id: Uuid): SshStoredConnection =
        store.read().connections.firstOrNull { it.id == id.toString() }
            ?: throw SshConnectionNotFoundException(id.toString())

    private fun requireTrustedFingerprint(connection: SshStoredConnection): String =
        connection.trustedFingerprint ?: throw SshFingerprintNotTrustedException(connection.id)

    private fun mergeDraft(existing: SshStoredConnection?, draft: SshConnectionDraft): SshStoredConnection {
        val old = existing
        val sameAuthType = old?.authType == draft.authType
        val password = when {
            draft.authType != SshAuthType.PASSWORD -> null
            sameAuthType -> draft.password ?: old.password
            else -> draft.password
        }
        val privateKey = when {
            draft.authType != SshAuthType.PRIVATE_KEY -> null
            sameAuthType -> draft.privateKey ?: old.privateKey
            else -> draft.privateKey
        }
        val passphrase = when {
            draft.authType != SshAuthType.PRIVATE_KEY -> null
            draft.clearPassphrase -> null
            sameAuthType -> draft.passphrase ?: old.passphrase
            else -> draft.passphrase
        }

        when (draft.authType) {
            SshAuthType.PASSWORD ->
                require(!password.isNullOrEmpty()) { "password is required" }
            SshAuthType.PRIVATE_KEY ->
                require(!privateKey.isNullOrBlank()) { "private key is required" }
        }

        val identityChanged = existing != null &&
            (existing.host != draft.host.trim() || existing.port != draft.port)
        val targetChanged = existing == null || identityChanged ||
            existing.username != draft.username.trim() ||
            existing.authType != draft.authType ||
            existing.password != password ||
            existing.privateKey != privateKey ||
            existing.passphrase != passphrase

        return SshStoredConnection(
            id = existing?.id ?: Uuid.random().toString(),
            name = draft.name.trim(),
            host = draft.host.trim(),
            port = draft.port,
            username = draft.username.trim(),
            authType = draft.authType,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            revision = if (targetChanged) newRevision() else existing?.revision ?: newRevision(),
            trustedFingerprint = if (identityChanged) null else existing?.trustedFingerprint,
            trustedKeyType = if (identityChanged) null else existing?.trustedKeyType,
            autoApproveCommands = draft.autoApproveCommands,
        )
    }

    private suspend fun refreshFlow() {
        flowMutex.withLock {
            _connections.value =
                store.read().connections.map { it.toInfo() }.sortedBy { it.name.lowercase() }
        }
    }

    private fun newRevision(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun normalizeFingerprint(fingerprint: String): String {
        val value = fingerprint.trim()
        require(value.startsWith("SHA256:")) { "expected a SHA256: fingerprint" }
        val body = value.removePrefix("SHA256:")
        val decoded = try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("malformed SHA256 fingerprint")
        }
        require(decoded.size == 32) { "SHA256 fingerprint must decode to 32 bytes" }
        return "SHA256:" + body.trimEnd('=')
    }

    private fun SshStoredConnection.toAuth(): SshAuth = when (authType) {
        SshAuthType.PASSWORD -> SshAuth.Password(
            password ?: throw SshStorageCorruptStateException("stored password missing"),
        )
        SshAuthType.PRIVATE_KEY -> SshAuth.PrivateKey(
            privateKey = privateKey ?: throw SshStorageCorruptStateException("stored private key missing"),
            passphrase = passphrase,
        )
    }

    private fun SshStoredConnection.toInfo() = SshConnectionInfo(
        id = Uuid.parse(id),
        name = name,
        host = host,
        port = port,
        username = username,
        authType = authType,
        revision = revision,
        trustedFingerprint = trustedFingerprint,
        trustedKeyType = trustedKeyType,
        hasPassphrase = !passphrase.isNullOrEmpty(),
        autoApproveCommands = autoApproveCommands,
    )

    private fun validateDraftBasics(draft: SshConnectionDraft) {
        val name = draft.name.trim()
        require(name.isNotEmpty() && name.length <= 128) { "name must be 1..128 chars" }
        require(name.none { it.isISOControl() }) { "name must not contain control characters" }

        val host = draft.host.trim()
        require(host.isNotEmpty() && host.length <= 255) { "host must be 1..255 chars" }
        require(host.none { it.isISOControl() || it.isWhitespace() }) {
            "host must not contain whitespace or control characters"
        }

        require(draft.port in 1..65535) { "port must be within 1..65535" }

        val username = draft.username.trim()
        require(username.isNotEmpty() && username.length <= 64) { "username must be 1..64 chars" }
        require(username.none { it.isISOControl() || it.isWhitespace() }) {
            "username must not contain whitespace or control characters"
        }

        draft.privateKey?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_PRIVATE_KEY_BYTES) { "private key too large" }
            require(!it.contains('\u0000')) { "private key must not contain NUL" }
        }
        draft.password?.let {
            require(!it.contains('\u0000')) { "password must not contain NUL" }
            require(it.length <= MAX_SECRET_CHARS) { "password too long" }
        }
        draft.passphrase?.let {
            require(!it.contains('\u0000')) { "passphrase must not contain NUL" }
            require(it.length <= MAX_SECRET_CHARS) { "passphrase too long" }
        }
    }

    private fun validateCommand(command: String, cwd: String?) {
        require(command.isNotBlank()) { "command must not be blank" }
        require(!command.contains('\u0000')) { "command must not contain NUL" }
        cwd?.let {
            require(it.isNotBlank()) { "cwd must not be blank" }
            require(!it.contains('\u0000')) { "cwd must not contain NUL" }
        }
    }

    companion object {
        private const val TAG = "SshConnectionRepository"
        private const val MAX_PRIVATE_KEY_BYTES = 256 * 1024
        private const val MAX_SECRET_CHARS = 4096
    }
}

/** Connection id does not exist. */
class SshConnectionNotFoundException(id: String) : Exception("SSH connection not found: $id")

/**
 * The connection changed between approval/probe and use; the caller must
 * re-read state and ask the user again instead of proceeding silently.
 */
class SshRevisionMismatchException : Exception("SSH connection changed; approval is stale")

/** No trusted host-key fingerprint is pinned for this connection. */
class SshFingerprintNotTrustedException(id: String) :
    Exception("SSH connection $id has no trusted host fingerprint")

/** Stored record is inconsistent (e.g. missing secret for its auth type). */
class SshStorageCorruptStateException(message: String) : Exception(message)
