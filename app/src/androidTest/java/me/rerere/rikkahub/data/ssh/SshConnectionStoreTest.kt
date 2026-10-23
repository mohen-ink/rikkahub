package me.rerere.rikkahub.data.ssh

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ssh.SshAuth
import me.rerere.ssh.SshAuthType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SshConnectionStoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var storeFile: File
    private lateinit var keyAlias: String
    private lateinit var store: SshConnectionStore
    private val keyAliases = mutableListOf<String>()

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "ssh-store-test-").toFile()
        // Dedicated per-run aliases so the tests never touch the app's real key.
        keyAlias = newAlias("store")
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
        }
        storeFile = File(context.noBackupFilesDir, "ssh_connections.enc")
        store = newStore()
    }

    @After fun tearDown() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in keyAliases) {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
        directory.deleteRecursively()
    }

    private fun newAlias(tag: String): String =
        "ssh_store_test_${tag}_${UUID.randomUUID().toString().replace("-", "")}".also(keyAliases::add)

    private fun newStore() = SshConnectionStore(context, SshKeystoreCipher(keyAlias = keyAlias))

    private fun connection(
        id: String = "conn-1",
        name: String = NAME_SENTINEL,
        host: String = HOST_SENTINEL,
        port: Int = 2222,
        username: String = "deploy",
        authType: SshAuthType = SshAuthType.PASSWORD,
        password: String? = PASSWORD_SENTINEL,
        privateKey: String? = null,
        passphrase: String? = null,
        revision: String = "rev-1",
        trustedFingerprint: String? = null,
    ) = SshStoredConnection(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        authType = authType,
        password = password,
        privateKey = privateKey,
        passphrase = passphrase,
        revision = revision,
        trustedFingerprint = trustedFingerprint,
    )

    private fun save(connection: SshStoredConnection) = runBlocking {
        store.mutate { it.copy(connections = it.connections + connection) to Unit }
    }

    private fun blobAsString(): String = String(storeFile.readBytes(), Charsets.ISO_8859_1)

    @Test fun missingFileReadsAsEmptyFileModel() = runBlocking {
        val file = store.read()
        assertEquals(1, file.version)
        assertTrue(file.connections.isEmpty())
    }

    @Test fun secretsDoNotAppearInTheOnDiskBlob() = runBlocking {
        save(
            connection(
                privateKey = KEY_SENTINEL,
                passphrase = PASSPHRASE_SENTINEL,
                authType = SshAuthType.PRIVATE_KEY,
                password = null,
            )
        )
        save(connection(id = "conn-2", password = PASSWORD_SENTINEL, privateKey = null))
        assertTrue(storeFile.isFile && storeFile.length() > 0)
        val blob = blobAsString()
        for (sentinel in listOf(NAME_SENTINEL, HOST_SENTINEL, PASSWORD_SENTINEL, KEY_SENTINEL, PASSPHRASE_SENTINEL)) {
            assertFalse("plaintext sentinel leaked into encrypted blob", blob.contains(sentinel))
        }
    }

    @Test fun aRecreatedStoreReadsWhatTheFirstOneWrote() = runBlocking {
        val saved = connection(
            authType = SshAuthType.PRIVATE_KEY,
            password = null,
            privateKey = KEY_SENTINEL,
            passphrase = PASSPHRASE_SENTINEL,
            trustedFingerprint = "SHA256:abc",
        )
        save(saved)
        val recreated = newStore()
        assertEquals(listOf(saved), recreated.read().connections)
    }

    @Test fun mutateUpdatesAndDeletesPersistAcrossReads() = runBlocking {
        save(connection(id = "a"))
        save(connection(id = "b", name = "second"))

        val renamed = connection(id = "a", name = "renamed")
        store.mutate { file ->
            file.copy(connections = file.connections.map { if (it.id == "a") renamed else it }) to Unit
        }
        assertEquals(listOf(renamed, connection(id = "b", name = "second")), store.read().connections)

        store.mutate { file ->
            file.copy(connections = file.connections.filterNot { it.id == "a" }) to Unit
        }
        assertEquals(listOf(connection(id = "b", name = "second")), store.read().connections)
    }

    @Test fun mutateSkipsTheWriteWhenTheModelIsUnchanged() = runBlocking {
        val result = store.mutate { it to "seen" }
        assertEquals("seen", result)
        assertFalse(storeFile.exists())

        save(connection())
        val before = storeFile.readBytes()
        store.mutate { it to Unit }
        assertTrue(before contentEquals storeFile.readBytes())
    }

    @Test fun identicalWritesProduceDifferentCiphertext() = runBlocking {
        save(connection())
        val first = storeFile.readBytes()
        // copy() yields a new instance, so the store rewrites the same model.
        store.mutate { it.copy() to Unit }
        val second = storeFile.readBytes()
        assertFalse(first contentEquals second)
        assertEquals(listOf(connection()), store.read().connections)
    }

    @Test fun cipherEncryptTwiceYieldsDifferentBlobsThatBothDecrypt() {
        val cipher = SshKeystoreCipher(keyAlias = keyAlias)
        val plaintext = "ssh-store-plaintext".toByteArray()
        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)
        assertFalse(first contentEquals second)
        // Each blob must carry the nonce that was actually used; decrypting
        // with the prepended bytes fails if the provider ignored them.
        assertTrue(plaintext contentEquals cipher.decrypt(first))
        assertTrue(plaintext contentEquals cipher.decrypt(second))
    }

    @Test fun tamperedCiphertextFailsClosed() = runBlocking {
        save(connection())
        val blob = storeFile.readBytes()
        blob[blob.lastIndex] = (blob.last().toInt() xor 0x01).toByte()
        storeFile.writeBytes(blob)
        assertThrows(SshStorageCorruptException::class.java) { runBlocking { store.read() } }
        // A second read still fails; the store does not recover to blank state.
        assertThrows(SshStorageCorruptException::class.java) { runBlocking { store.read() } }
    }

    @Test fun truncatedBlobFailsClosed() = runBlocking {
        save(connection())
        storeFile.writeBytes(storeFile.readBytes().copyOf(8))
        assertThrows(SshStorageCorruptException::class.java) { runBlocking { store.read() } }
    }

    @Test fun blobWrittenUnderADifferentKeyFailsClosed() = runBlocking {
        val other = SshKeystoreCipher(keyAlias = newAlias("other"))
        storeFile.writeBytes(other.encrypt("{}".toByteArray()))
        assertThrows(SshStorageCorruptException::class.java) { runBlocking { store.read() } }
    }

    @Test fun garbageBlobFailsClosed() {
        storeFile.writeBytes(ByteArray(64) { (it * 31 + 7).toByte() })
        assertThrows(SshStorageCorruptException::class.java) { runBlocking { store.read() } }
    }

    @Test fun existingEmptyFileFailsClosed() {
        // Fail-closed contract: a present-but-unreadable blob must throw rather
        // than silently wipe state back to defaults.
        storeFile.writeBytes(ByteArray(0))
        assertThrows(SshStorageCorruptException::class.java) { runBlocking { store.read() } }
    }

    @Test fun draftToStringMasksSecrets() {
        val draft = SshConnectionDraft(
            name = "n",
            host = "h",
            port = 22,
            username = "u",
            authType = SshAuthType.PRIVATE_KEY,
            password = PASSWORD_SENTINEL,
            privateKey = KEY_SENTINEL,
            passphrase = PASSPHRASE_SENTINEL,
        ).toString()
        for (sentinel in listOf(PASSWORD_SENTINEL, KEY_SENTINEL, PASSPHRASE_SENTINEL)) {
            assertFalse(draft.contains(sentinel))
        }
        assertTrue(draft.contains("<set>"))
    }

    @Test fun storedConnectionToStringMasksSecrets() {
        val text = connection(
            privateKey = KEY_SENTINEL,
            passphrase = PASSPHRASE_SENTINEL,
            authType = SshAuthType.PRIVATE_KEY,
        ).toString()
        for (sentinel in listOf(PASSWORD_SENTINEL, KEY_SENTINEL, PASSPHRASE_SENTINEL)) {
            assertFalse(text.contains(sentinel))
        }
    }

    @Test fun authToStringMasksSecrets() {
        assertFalse(SshAuth.Password(PASSWORD_SENTINEL).toString().contains(PASSWORD_SENTINEL))
        val key = SshAuth.PrivateKey(privateKey = KEY_SENTINEL, passphrase = PASSPHRASE_SENTINEL).toString()
        assertFalse(key.contains(KEY_SENTINEL))
        assertFalse(key.contains(PASSPHRASE_SENTINEL))
    }

    private companion object {
        const val NAME_SENTINEL = "sentinel-config-name-9f27"
        const val HOST_SENTINEL = "sentinel.internal.example"
        const val PASSWORD_SENTINEL = "pw-sentinel-7f3a9c"
        const val KEY_SENTINEL = "PRIVATE-KEY-SENTINEL-5d21"
        const val PASSPHRASE_SENTINEL = "passphrase-sentinel-3b08"
    }
}
