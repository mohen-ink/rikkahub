package me.rerere.ssh

/**
 * Result of a single short-lived SSH exec. Part of the shared contract with UI and AI layers.
 *
 * [status] is one of:
 * - `completed` — the remote process exited, [exitCode] is authoritative.
 * - `timeout` — the command timeout elapsed and the channel was closed locally;
 *   the remote process may still be running.
 * - `interrupted` — the connection was lost or the coroutine was cancelled before
 *   the remote exit status arrived; remote termination is unknown.
 */
data class SshCommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val status: String = "completed",
)
