package me.rerere.ssh

import java.security.MessageDigest
import java.util.Base64
import kotlin.math.min

// POSIX single-quote escaping ('\'' idiom); NUL can't be expressed so it's rejected.
internal fun quotePosixPath(path: String): String {
    require(!path.contains('\u0000')) { "cwd must not contain NUL" }
    if (path.isEmpty()) return "''"
    return "'" + path.replace("'", "'\\''") + "'"
}

internal fun hostKeyFingerprint(hostKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(hostKey)
    val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
    return "SHA256:$b64"
}

// SSH 公钥 blob 以 uint32 长度 + 算法名开头 (RFC 4253 §6.6)
internal fun hostKeyType(hostKey: ByteArray): String {
    if (hostKey.size < 4) return "unknown"
    val length = ((hostKey[0].toInt() and 0xFF) shl 24) or
        ((hostKey[1].toInt() and 0xFF) shl 16) or
        ((hostKey[2].toInt() and 0xFF) shl 8) or
        (hostKey[3].toInt() and 0xFF)
    if (length <= 0 || length > 64 || 4 + length > hostKey.size) return "unknown"
    return String(hostKey, 4, length, Charsets.US_ASCII)
}

internal fun normalizeSha256Fingerprint(fingerprint: String): String {
    val value = fingerprint.trim()
    require(value.startsWith("SHA256:")) { "expected a SHA256: fingerprint" }
    val b64 = value.removePrefix("SHA256:")
    val decoded = try {
        Base64.getDecoder().decode(b64)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("malformed SHA256 fingerprint")
    }
    require(decoded.size == SHA256_BYTES) { "SHA256 fingerprint must decode to $SHA256_BYTES bytes" }
    return "SHA256:" + b64.trimEnd('=')
}

private const val SHA256_BYTES = 32

// Keeps the tail of a stream under maxChars; drops overflow from the front.
internal class BoundedOutputBuffer(private val maxChars: Int) {
    private val buffer = StringBuilder()
    private var carriedBytes = ByteArray(0)
    var truncated: Boolean = false
        private set

    @Synchronized
    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val chunk = carriedBytes + bytes.copyOfRange(offset, offset + length)
        val consumed = utf8ConsumableLength(chunk)
        if (consumed < chunk.size) {
            carriedBytes = chunk.copyOfRange(consumed, chunk.size)
        } else {
            carriedBytes = EMPTY_BYTES
        }
        if (consumed == 0) return
        val text = String(chunk, 0, consumed, Charsets.UTF_8)
        if (buffer.length + text.length <= maxChars) {
            buffer.append(text)
        } else {
            val keep = maxChars - text.length
            if (keep > 0) {
                buffer.delete(0, buffer.length - keep)
            } else {
                buffer.setLength(0)
            }
            buffer.append(text.takeLast(min(text.length, maxChars)))
            truncated = true
        }
    }

    @Synchronized
    fun finish(): String {
        if (carriedBytes.isNotEmpty()) {
            val text = String(carriedBytes, Charsets.UTF_8)
            carriedBytes = EMPTY_BYTES
            val space = maxChars - buffer.length
            if (text.length <= space) {
                buffer.append(text)
            } else {
                buffer.setLength(0)
                buffer.append(text.takeLast(maxChars))
                truncated = true
            }
        }
        return buffer.toString()
    }

    private fun utf8ConsumableLength(bytes: ByteArray): Int {
        // 尾部最多只剩一个被切断的多字节字符, 回看 3 个字节就够;
        // 只有"首字节合法且后续续字节都匹配"才当作被切断而留到下次,
        // 非法字节立刻按替换字符解码, 否则 carriedBytes 会随连续非法输入无限增长
        for (length in 1..min(3, bytes.size)) {
            val start = bytes.size - length
            val lead = bytes[start].toInt() and 0xFF
            val needed = when {
                lead and 0x80 == 0 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> 0
            }
            if (needed == 0) continue
            if (needed > length && (1 until length).all { bytes[start + it].toInt() and 0xC0 == 0x80 }) {
                return start
            }
        }
        return bytes.size
    }

    private companion object {
        val EMPTY_BYTES = ByteArray(0)
    }
}
