package me.rerere.rikkahub.data.ssh

import me.rerere.ssh.SshAuthType

/**
 * Write input from the edit UI. Secrets are nullable: a null field means
 * "keep the previously stored secret" when the auth type is unchanged, while
 * [clearPassphrase] explicitly clears a stored key passphrase.
 *
 * This type is never serialized into the encrypted model and must never be
 * exposed to the AI layer; the toString below intentionally omits secret
 * values so accidental logging can't leak them.
 */
class SshConnectionDraft(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshAuthType,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val clearPassphrase: Boolean = false,
    val autoApproveCommands: Boolean = false,
) {
    override fun toString(): String =
        "SshConnectionDraft(name=$name, host=$host, port=$port, username=$username, " +
            "authType=$authType, password=${if (password != null) "<set>" else "<unset>"}, " +
            "privateKey=${if (privateKey != null) "<set>" else "<unset>"}, " +
            "passphrase=${if (passphrase != null) "<set>" else "<unset>"}, " +
            "clearPassphrase=$clearPassphrase)"
}
