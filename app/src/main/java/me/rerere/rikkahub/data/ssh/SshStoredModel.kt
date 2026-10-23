package me.rerere.rikkahub.data.ssh

import kotlinx.serialization.Serializable
import me.rerere.ssh.SshAuthType
import kotlin.uuid.Uuid

/**
 * On-disk model for a stored SSH connection, serialized as JSON and then
 * encrypted as one blob. Internal to the SSH store — never returned by
 * repository public APIs and never shown to the AI layer.
 */
@Serializable
internal data class SshStoredConnection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshAuthType,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val revision: String,
    val trustedFingerprint: String? = null,
    val autoApproveCommands: Boolean = false,
) {
    override fun toString(): String =
        "SshStoredConnection(id=$id, name=$name, host=$host, port=$port, username=$username, " +
            "authType=$authType, password=${mask(password)}, privateKey=${mask(privateKey)}, " +
            "passphrase=${mask(passphrase)}, revision=$revision, trustedFingerprint=$trustedFingerprint)"

    private fun mask(value: String?) = if (value == null) "<unset>" else "<set>"
}

@Serializable
internal data class SshConnectionsFile(
    val version: Int = 1,
    val connections: List<SshStoredConnection> = emptyList(),
)

internal fun Uuid.toStoredString(): String = toString()

internal fun parseStoredUuid(raw: String): Uuid = Uuid.parse(raw)
