package me.rerere.rikkahub.data.ssh

import me.rerere.ssh.SshAuthType
import kotlin.uuid.Uuid

/**
 * Public summary of a stored SSH connection. Safe to show to UI and — minus the
 * fingerprint — safe to describe to the AI. Never carries password, private key
 * or passphrase material.
 *
 * [revision] is an opaque random token that changes on *every* mutation
 * (credential, connection detail, or fingerprint trust). Callers must present
 * the revision they saw when initiating an approval; the repository re-checks
 * it right before credentials/network are used, so an edit or trust change
 * invalidates pending approvals instead of being silently redirected.
 */
data class SshConnectionInfo(
    val id: Uuid,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshAuthType,
    val revision: String,
    val trustedFingerprint: String?,
    val hasPassphrase: Boolean = false,
    val autoApproveCommands: Boolean = false,
)
