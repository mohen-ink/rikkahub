package me.rerere.ssh

/** 探测到的服务器主机公钥: 算法名 (如 `ssh-ed25519`) 与 OpenSSH 风格的 `SHA256:` 指纹 */
data class SshHostKeyInfo(
    val type: String,
    val fingerprint: String,
)
