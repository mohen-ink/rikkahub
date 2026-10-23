package me.rerere.rikkahub.data.ssh

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/**
 * Encrypted-at-rest connection store under `context.noBackupFilesDir` (excluded
 * from backups/migration). The whole model is one AES-GCM blob written
 * atomically via [AtomicFile]. Reads and mutations are serialized by [mutex].
 * Fail-closed: an existing but unreadable/tampered/oversized blob throws
 * [SshStorageCorruptException] and is never silently reset.
 */
internal class SshConnectionStore(
    context: Context,
    private val cipher: SshKeystoreCipher = SshKeystoreCipher(),
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun read(): SshConnectionsFile = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    suspend fun <R> mutate(transform: (SshConnectionsFile) -> Pair<SshConnectionsFile, R>): R =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readLocked()
                val (next, result) = transform(current)
                if (next !== current) writeLocked(next)
                result
            }
        }

    private fun readLocked(): SshConnectionsFile {
        val blob = try {
            file.openRead().use { it.readBounded() }
        } catch (e: FileNotFoundException) {
            // AtomicFile restores .bak on openRead; reaching here means neither
            // the base file nor a recoverable backup exists.
            if (file.baseFile.exists()) throw SshStorageCorruptException("cannot read SSH store")
            return SshConnectionsFile()
        } catch (e: SshStorageCorruptException) {
            throw e
        } catch (e: Exception) {
            throw SshStorageCorruptException("cannot read SSH store")
        }
        if (blob.isEmpty()) {
            throw SshStorageCorruptException("SSH store blob has invalid size")
        }
        val plaintext = try {
            cipher.decrypt(blob)
        } finally {
            blob.fill(0)
        }
        val text = try {
            String(plaintext, Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
        val model = try {
            json.decodeFromString(SshConnectionsFile.serializer(), text)
        } catch (e: Exception) {
            throw SshStorageCorruptException("cannot decode SSH store")
        }
        if (model.version != SCHEMA_VERSION) {
            throw SshStorageCorruptException("unsupported SSH store version ${model.version}")
        }
        return model
    }

    private fun writeLocked(fileModel: SshConnectionsFile) {
        val plaintext = json.encodeToString(SshConnectionsFile.serializer(), fileModel)
            .toByteArray(Charsets.UTF_8)
        val blob = try {
            cipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        if (blob.size > MAX_BLOB_BYTES) {
            throw SshStorageCorruptException("SSH store exceeds maximum size")
        }
        try {
            val out = file.startWrite()
            try {
                out.write(blob)
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        } catch (e: Exception) {
            throw SshStorageCorruptException("cannot write SSH store")
        } finally {
            blob.fill(0)
        }
    }

    private companion object {
        const val FILE_NAME = "ssh_connections.enc"
        const val SCHEMA_VERSION = 1
        const val MAX_BLOB_BYTES = 16 * 1024 * 1024
        private const val READ_CHUNK = 8 * 1024

        // InputStream.readNBytes needs API 33; bound the read by hand so an
        // oversized blob throws instead of allocating unbounded memory.
        private fun java.io.InputStream.readBounded(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(READ_CHUNK)
            var total = 0
            while (true) {
                val n = read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BLOB_BYTES) {
                    throw SshStorageCorruptException("SSH store blob has invalid size")
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
