package me.rerere.ssh

/**
 * Base type for expected SSH failures. Messages deliberately carry only
 * connection-level detail (host/port/username/class of failure) — never
 * credential material.
 */
open class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** TCP connect/handshake failed or timed out before authentication. */
class SshConnectException(message: String, cause: Throwable? = null) : SshException(message, cause)

/** Authentication failed or the configured auth material is unusable. */
class SshAuthException(message: String, cause: Throwable? = null) : SshException(message, cause)

/** The server host key did not match the fingerprint pinned by the user. */
class SshHostKeyMismatchException(message: String) : SshException(message)

/** The requested host-key probing handshake could not complete. */
class SshHostKeyProbeException(message: String, cause: Throwable? = null) : SshException(message, cause)
