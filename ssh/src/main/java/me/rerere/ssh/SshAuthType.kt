package me.rerere.ssh

/**
 * SSH authentication method. Part of the shared contract with UI and AI layers.
 */
enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY,
}
