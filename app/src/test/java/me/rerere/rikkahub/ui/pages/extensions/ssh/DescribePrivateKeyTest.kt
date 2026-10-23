package me.rerere.rikkahub.ui.pages.extensions.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DescribePrivateKeyTest {
    @Test
    fun `recognizes common private key headers`() {
        assertEquals("OpenSSH 私钥", describePrivateKey("\n-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n"))
        assertEquals("RSA 私钥", describePrivateKey("-----BEGIN RSA PRIVATE KEY-----\nabc"))
        assertEquals("私钥", describePrivateKey("-----BEGIN PRIVATE KEY-----\nabc"))
    }

    @Test
    fun `rejects public keys and random text`() {
        assertNull(describePrivateKey("ssh-ed25519 AAAAC3Nza user@host"))
        assertNull(describePrivateKey("-----BEGIN PUBLIC KEY-----\nabc"))
        assertNull(describePrivateKey(""))
    }
}
