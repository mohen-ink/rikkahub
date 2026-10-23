package me.rerere.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class InternalTest {

    @Test
    fun `quotePosixPath handles plain paths`() {
        assertEquals("'/srv/app'", quotePosixPath("/srv/app"))
        assertEquals("'relative dir'", quotePosixPath("relative dir"))
    }

    @Test
    fun `quotePosixPath escapes single quotes`() {
        assertEquals("'it'\\''s'", quotePosixPath("it's"))
        assertEquals("'a'\\''b'\\''c'", quotePosixPath("a'b'c"))
    }

    @Test
    fun `quotePosixPath rejects NUL`() {
        try {
            quotePosixPath("bad\u0000path")
            fail("expected NUL rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun validFingerprint(): String {
        val digest = ByteArray(32) { it.toByte() }
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    @Test
    fun `normalizeSha256Fingerprint accepts a real 32-byte fingerprint`() {
        assertEquals(validFingerprint(), normalizeSha256Fingerprint(validFingerprint()))
        val padded = validFingerprint() + "="
        assertEquals(validFingerprint(), normalizeSha256Fingerprint(padded))
    }

    @Test
    fun `normalizeSha256Fingerprint rejects junk`() {
        listOf("MD5:abc", "SHA256:", "SHA256:abc", "SHA256:===", "SHA256:not*base64!", "").forEach {
            try {
                normalizeSha256Fingerprint(it)
                fail("expected rejection of $it")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `BoundedOutputBuffer keeps tail when overflow`() {
        val buf = BoundedOutputBuffer(maxChars = 8)
        buf.append("0123456789".toByteArray(), 0, 10)
        val out = buf.finish()
        assertEquals(8, out.length)
        assertEquals("23456789", out)
        assertTrue(buf.truncated)
    }

    @Test
    fun `BoundedOutputBuffer not truncated when within cap`() {
        val buf = BoundedOutputBuffer(maxChars = 8)
        buf.append("abc".toByteArray(), 0, 3)
        assertEquals("abc", buf.finish())
        assertFalse(buf.truncated)
    }

    @Test
    fun `BoundedOutputBuffer splits multi-byte UTF-8 across reads`() {
        val euro = "€".toByteArray(Charsets.UTF_8) // 3 bytes
        val buf = BoundedOutputBuffer(maxChars = 16)
        buf.append(euro, 0, 1)
        buf.append(euro, 1, 2)
        assertEquals("€", buf.finish())
    }

    @Test
    fun `BoundedOutputBuffer handles split emoji correctly`() {
        val emoji = "🙂".toByteArray(Charsets.UTF_8) // 4 bytes
        val buf = BoundedOutputBuffer(maxChars = 16)
        for (b in emoji) buf.append(byteArrayOf(b), 0, 1)
        assertEquals("🙂", buf.finish())
        assertFalse(buf.truncated)
    }

    @Test
    fun `BoundedOutputBuffer does not corrupt when tail ends mid-emoji`() {
        val buf = BoundedOutputBuffer(maxChars = 4)
        buf.append("ab🙂cd".toByteArray(Charsets.UTF_8), 0, "ab🙂cd".toByteArray().size)
        val out = buf.finish()
        assertTrue(buf.truncated)
        assertEquals(4, out.length)
    }

    @Test
    fun `BoundedOutputBuffer keeps a bounded carry for invalid utf8`() {
        val buf = BoundedOutputBuffer(maxChars = 8)
        // 连续非法续字节不能一直留在 carry 里, 否则缓冲会随输出无限增长
        val junk = ByteArray(64 * 1024) { 0x80.toByte() }
        buf.append(junk, 0, junk.size)
        assertTrue(buf.finish().length <= 8)
        assertTrue(buf.truncated)
    }

    @Test
    fun `buildRemoteCommand groups command under cd`() {
        val client = JschSshClient()
        assertEquals("plain", client.buildRemoteCommand("plain", null))
        val built = client.buildRemoteCommand("echo hi ; ls", "/srv/it's")
        assertTrue(built.startsWith("cd '/srv/it'\\''s' && {\n"))
        assertTrue(built.endsWith("\n}"))
        assertTrue(built.contains("echo hi ; ls"))
    }
}
