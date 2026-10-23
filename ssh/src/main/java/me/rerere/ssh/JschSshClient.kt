package me.rerere.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

sealed interface SshAuth {
    data class Password(val password: String) : SshAuth {
        override fun toString(): String = "Password(password=<set>)"
    }

    data class PrivateKey(val privateKey: String, val passphrase: String?) : SshAuth {
        override fun toString(): String =
            "PrivateKey(privateKey=<set>, passphrase=${if (passphrase != null) "<set>" else "<unset>"})"
    }
}

/**
 * Short-lived SSH client on the maintained mwiede/jsch fork. The host key is
 * pinned to a SHA-256 fingerprint before authentication; probing captures the
 * key without sending credentials. Only the configured non-interactive method
 * is offered, with one auth attempt; agent/X forwarding stays disabled.
 * `disconnect()` closes the local channel only — remote termination is never
 * claimed and surfaces as `status = "interrupted"` / `timedOut`.
 */
class JschSshClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val maxConcurrent: Int = 4,
) {
    private val slots = Semaphore(maxConcurrent)

    suspend fun probeHostKey(host: String, port: Int): SshHostKeyInfo =
        slots.withPermit {
            runInterruptible(dispatcher) {
                val probe = CapturingHostKeyRepository()
                val (_, session) = newSession(host = host, port = port, username = PROBE_USERNAME)
                session.setHostKeyRepository(probe)
                try {
                    // check() returns NOT_INCLUDED, so connect() throws after kex
                    // and before any authentication request is sent.
                    session.connect(connectTimeoutMillis)
                } catch (e: JSchException) {
                    // expected; the captured key is what matters
                } finally {
                    runCatching { session.disconnect() }
                }
                val key = probe.capturedKey
                    ?: throw SshHostKeyProbeException("could not obtain host key from $host:$port")
                SshHostKeyInfo(type = hostKeyType(key), fingerprint = hostKeyFingerprint(key))
            }
        }

    suspend fun testAuthentication(
        host: String,
        port: Int,
        username: String,
        auth: SshAuth,
        expectedFingerprint: String,
    ): Unit = slots.withPermit {
        runInterruptible(dispatcher) {
            val session = authenticatedSession(host, port, username, auth, expectedFingerprint)
            runCatching { session.disconnect() }
        }
    }

    suspend fun execute(
        host: String,
        port: Int,
        username: String,
        auth: SshAuth,
        expectedFingerprint: String,
        command: String,
        cwd: String? = null,
        timeoutSeconds: Int = DEFAULT_COMMAND_TIMEOUT_SECONDS,
    ): SshCommandResult {
        require(timeoutSeconds in 1..MAX_COMMAND_TIMEOUT_SECONDS) {
            "timeoutSeconds must be within 1..$MAX_COMMAND_TIMEOUT_SECONDS"
        }
        require(command.isNotBlank() && !command.contains('\u0000')) { "invalid command" }
        if (cwd != null) {
            require(cwd.isNotBlank() && !cwd.contains('\u0000')) { "invalid cwd" }
        }
        return slots.withPermit {
            val session = runInterruptible(dispatcher) {
                authenticatedSession(host, port, username, auth, expectedFingerprint)
            }
            try {
                runExec(session, command, cwd, timeoutSeconds)
            } finally {
                runCatching { session.disconnect() }
            }
        }
    }

    private suspend fun runExec(
        session: Session,
        command: String,
        cwd: String?,
        timeoutSeconds: Int,
    ): SshCommandResult = coroutineScope {
        val stdoutBuf = BoundedOutputBuffer(MAX_STREAM_CHARS)
        val stderrBuf = BoundedOutputBuffer(MAX_STREAM_CHARS)
        val channel = runInterruptible(dispatcher) { session.openChannel("exec") as ChannelExec }
        try {
            channel.setCommand(buildRemoteCommand(command, cwd))
            channel.setPty(false)
            channel.setAgentForwarding(false)
            channel.setXForwarding(false)
            channel.setInputStream(null)

            val stdoutReader = async(dispatcher) { drainStream(channel.inputStream, stdoutBuf) }
            val stderrReader = async(dispatcher) { drainStream(channel.errStream, stderrBuf) }

            runInterruptible(dispatcher) {
                try {
                    channel.connect(connectTimeoutMillis)
                } catch (e: InterruptedException) {
                    runCatching { channel.disconnect() }
                    throw e
                }
            }

            val exitCode = awaitExit(channel, timeoutSeconds, session)
            stdoutReader.await()
            stderrReader.await()

            when {
                exitCode == null -> SshCommandResult(
                    exitCode = null,
                    stdout = stdoutBuf.finish(),
                    stderr = stderrBuf.finish(),
                    timedOut = true,
                    truncated = stdoutBuf.truncated || stderrBuf.truncated,
                    status = "timeout",
                )
                exitCode < 0 -> SshCommandResult(
                    exitCode = null,
                    stdout = stdoutBuf.finish(),
                    stderr = stderrBuf.finish(),
                    timedOut = false,
                    truncated = stdoutBuf.truncated || stderrBuf.truncated,
                    status = "interrupted",
                )
                else -> SshCommandResult(
                    exitCode = exitCode,
                    stdout = stdoutBuf.finish(),
                    stderr = stderrBuf.finish(),
                    timedOut = false,
                    truncated = stdoutBuf.truncated || stderrBuf.truncated,
                    status = "completed",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!currentCoroutineContext().isActive) throw e
            SshCommandResult(
                exitCode = null,
                stdout = stdoutBuf.finish(),
                stderr = stderrBuf.finish(),
                timedOut = false,
                truncated = stdoutBuf.truncated || stderrBuf.truncated,
                status = "interrupted",
            )
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    /**
     * Group the command so it only runs if `cd` succeeds; braces keep the
     * remote shell from treating `cd x && a ; b` as `cd x && a` then `b`.
     */
    internal fun buildRemoteCommand(command: String, cwd: String?): String =
        if (cwd.isNullOrEmpty()) {
            command
        } else {
            "cd ${quotePosixPath(cwd)} && {\n$command\n}"
        }

    private fun authenticatedSession(
        host: String,
        port: Int,
        username: String,
        auth: SshAuth,
        expectedFingerprint: String,
    ): Session {
        val (jsch, session) = newSession(host = host, port = port, username = username)
        session.setHostKeyRepository(PinningHostKeyRepository(expectedFingerprint))
        applyAuth(jsch, session, auth)
        try {
            session.connect(connectTimeoutMillis)
        } catch (e: JSchException) {
            runCatching { session.disconnect() }
            throw mapConnectException(e, host, port)
        } catch (e: InterruptedException) {
            runCatching { session.disconnect() }
            throw e
        }
        return session
    }

    private suspend fun awaitExit(
        channel: ChannelExec,
        timeoutSeconds: Int,
        session: Session,
    ): Int? = withTimeoutOrNull(timeoutSeconds.toLong() * 1000) {
        runInterruptible(dispatcher) {
            while (!channel.isClosed) {
                if (!session.isConnected) throw IOException("SSH session disconnected")
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    channel.disconnect()
                    throw e
                }
            }
            channel.exitStatus
        }
    }

    private fun newSession(host: String, port: Int, username: String): Pair<JSch, Session> {
        val jsch = JSch()
        jsch.setInstanceLogger(NoopLogger)
        val session = jsch.getSession(username, host, port)
        session.setLogger(NoopLogger)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("MaxAuthTries", "1")
        return jsch to session
    }

    private fun applyAuth(jsch: JSch, session: Session, auth: SshAuth) {
        when (auth) {
            is SshAuth.Password -> {
                session.setConfig("PreferredAuthentications", "password")
                session.setPassword(auth.password.toByteArray(Charsets.UTF_8))
                session.setUserInfo(object : UserInfo {
                    override fun getPassphrase(): String? = null
                    override fun getPassword(): String = auth.password
                    override fun promptPassword(message: String?): Boolean = true
                    override fun promptPassphrase(message: String?): Boolean = false
                    override fun promptYesNo(message: String?): Boolean = false
                    override fun showMessage(message: String?) {}
                })
            }
            is SshAuth.PrivateKey -> {
                session.setConfig("PreferredAuthentications", "publickey")
                try {
                    jsch.addIdentity(
                        "ssh-connection-key",
                        auth.privateKey.toByteArray(Charsets.UTF_8),
                        null,
                        auth.passphrase?.toByteArray(Charsets.UTF_8),
                    )
                } catch (e: JSchException) {
                    throw SshAuthException("private key could not be loaded")
                }
            }
        }
    }

    private fun mapConnectException(e: JSchException, host: String, port: Int): SshException {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Auth fail", ignoreCase = true) ||
                msg.contains("Auth cancel", ignoreCase = true) ->
                SshAuthException("authentication failed for $host:$port")
            msg.contains("HostKey has been changed", ignoreCase = true) ||
                msg.contains("hostkey", ignoreCase = true) ||
                msg.contains("reject HostKey", ignoreCase = true) ->
                SshHostKeyMismatchException("host key mismatch for $host:$port")
            else -> SshConnectException("cannot reach $host:$port")
        }
    }

    private class CapturingHostKeyRepository : HostKeyRepository {
        @Volatile
        var capturedKey: ByteArray? = null
            private set

        override fun check(host: String?, key: ByteArray?): Int {
            if (capturedKey == null && key != null) capturedKey = key.copyOf()
            return HostKeyRepository.NOT_INCLUDED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "ssh-probe"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private class PinningHostKeyRepository(expectedFingerprint: String) : HostKeyRepository {
        private val expected = normalizeSha256Fingerprint(expectedFingerprint)

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            return if (hostKeyFingerprint(key) == expected) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "ssh-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private suspend fun drainStream(stream: java.io.InputStream, buffer: BoundedOutputBuffer) {
        runInterruptible(dispatcher) {
            try {
                val chunk = ByteArray(READ_CHUNK)
                while (true) {
                    val n = try {
                        stream.read(chunk)
                    } catch (e: IOException) {
                        break
                    }
                    if (n < 0) break
                    buffer.append(chunk, 0, n)
                }
            } catch (e: InterruptedException) {
                // channel closed or coroutine cancelled
            } finally {
                runCatching { stream.close() }
            }
        }
    }

    private object NoopLogger : Logger {
        override fun isEnabled(level: Int): Boolean = false
        override fun log(level: Int, message: String?) {}
        override fun log(level: Int, message: String?, e: Throwable?) {}
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_COMMAND_TIMEOUT_SECONDS = 30
        const val MAX_COMMAND_TIMEOUT_SECONDS = 600
        const val MAX_STREAM_CHARS = 20 * 1024
        private const val READ_CHUNK = 8 * 1024
        private const val POLL_MS = 50L
        private const val PROBE_USERNAME = "ssh-probe"
    }
}
