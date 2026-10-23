package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.SshFingerprintNotTrustedException
import me.rerere.rikkahub.data.repository.SshRevisionMismatchException
import me.rerere.rikkahub.data.ssh.SshConnectionInfo
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ssh.SshAuthType
import me.rerere.ssh.SshCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SshToolsTest {
    private class FakeSshAccess(
        connections: List<SshConnectionInfo>,
    ) : SshConnectionAccess {
        private val store = MutableStateFlow(connections)
        val executed = mutableListOf<Triple<Uuid, String, String>>() // id, revision, command
        var result: SshCommandResult = SshCommandResult(exitCode = 0, stdout = "ok", stderr = "")
        var executeError: Exception? = null
        var listError: Exception? = null

        override val connectionsFlow: StateFlow<List<SshConnectionInfo>> get() = store

        override suspend fun list(): List<SshConnectionInfo> {
            listError?.let { throw it }
            return store.value
        }

        override suspend fun getById(id: Uuid): SshConnectionInfo? = store.value.firstOrNull { it.id == id }

        override suspend fun execute(
            id: Uuid,
            expectedRevision: String,
            command: String,
            cwd: String?,
            timeoutSeconds: Int,
        ): SshCommandResult {
            executed += Triple(id, expectedRevision, command)
            executeError?.let { throw it }
            return result
        }

        fun remove(id: Uuid) {
            store.value = store.value.filterNot { it.id == id }
        }

        fun setAutoApprove(id: Uuid, enabled: Boolean) {
            store.value = store.value.map { if (it.id == id) it.copy(autoApproveCommands = enabled) else it }
        }
    }

    private val connectionA = SshConnectionInfo(
        id = Uuid.random(),
        name = "prod-web",
        host = "10.0.0.1",
        port = 22,
        username = "deploy",
        authType = SshAuthType.PASSWORD,
        revision = "rev-a1",
        trustedFingerprint = "SHA256:abc",
    )
    private val connectionB = connectionA.copy(
        id = Uuid.random(),
        name = "staging",
        host = "10.0.0.2",
        username = "ci",
        revision = "rev-b1",
    )

    private suspend fun execTool(access: SshConnectionAccess, ids: suspend () -> Set<Uuid>): me.rerere.ai.core.Tool =
        createSshTools(authorizedIds = ids, sshAccess = access).single { it.name == SSH_EXEC_TOOL }

    private fun execArgs(
        id: Uuid,
        revision: String,
        command: String = "uname -a",
        extra: JsonObject = buildJsonObject {},
    ) = buildJsonObject {
        put("connectionId", id.toString())
        put("connectionRevision", revision)
        put("command", command)
        extra.forEach { (key, value) -> put(key, value) }
    }

    private suspend fun me.rerere.ai.core.Tool.call(args: JsonObject) =
        execute(args).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private suspend fun me.rerere.ai.core.Tool.callFails(args: JsonObject): Throwable =
        runCatching { execute(args) }.exceptionOrNull() ?: error("expected tool call to fail")

    private fun String.asJson() = JsonInstant.parseToJsonElement(this).jsonObject

    @Test
    fun `no tools without authorized existing connections`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        assertTrue(createSshTools({ emptySet() }, access).isEmpty())
        assertTrue(createSshTools({ setOf(Uuid.random()) }, access).isEmpty())
    }

    @Test
    fun `list only returns authorized existing id name revision`() = runTest {
        val access = FakeSshAccess(listOf(connectionA, connectionB))
        val tools = createSshTools(authorizedIds = { setOf(connectionA.id, Uuid.random()) }, sshAccess = access)

        val listTool = tools.single { it.name == SSH_LIST_CONNECTIONS_TOOL }
        val output = listTool.call(buildJsonObject {}).asJson()
        val connections = output["connections"]!!.jsonArray

        assertEquals(1, connections.size)
        val entry = connections.single().jsonObject
        assertEquals(connectionA.id.toString(), entry["id"]!!.jsonPrimitive.content)
        assertEquals("prod-web", entry["name"]!!.jsonPrimitive.content)
        assertEquals("rev-a1", entry["revision"]!!.jsonPrimitive.content)
        assertFalse(entry.containsKey("host"))
        assertFalse(entry.containsKey("username"))
        assertFalse(output.toString().contains("10.0.0.1"))
        assertFalse(output.toString().contains("deploy"))
        assertFalse(output.toString().contains("SHA256"))
    }

    @Test
    fun `exec runs against the authorized connection`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tool = execTool(access) { setOf(connectionA.id) }

        val output = tool.call(execArgs(connectionA.id, "rev-a1", "hostname")).asJson()

        assertEquals(Triple(connectionA.id, "rev-a1", "hostname"), access.executed.single())
        assertEquals("prod-web", output["connectionName"]!!.jsonPrimitive.content)
        assertEquals("completed", output["status"]!!.jsonPrimitive.content)
        assertEquals(0, output["exitCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("ok", output["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exec rejects an unauthorized connection`() = runTest {
        val access = FakeSshAccess(listOf(connectionA, connectionB))
        val tool = execTool(access) { setOf(connectionA.id) }

        val error = tool.callFails(execArgs(connectionB.id, "rev-b1"))
        assertTrue(error.message!!.contains("not authorized"))
        assertTrue(access.executed.isEmpty())
    }

    @Test
    fun `exec picks up revoked authorization across approval resume`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        var authorized = setOf(connectionA.id)
        val tool = execTool(access) { authorized }

        // 审批等待期间授权被撤销: 同一个工具对象、同一份参数不再放行
        authorized = emptySet()
        val error = tool.callFails(execArgs(connectionA.id, "rev-a1"))
        assertTrue(error.message!!.contains("not authorized"))
        assertTrue(access.executed.isEmpty())
    }

    @Test
    fun `exec fails closed when the resolver cannot read the conversation`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tool = execTool(access) { throw RuntimeException("session gone") }

        // 授权解析失败要关死(空集), 不允许退回旧的授权集合
        val error = tool.callFails(execArgs(connectionA.id, "rev-a1"))
        assertFalse(access.executed.isNotEmpty())
        assertTrue(error.message!!.contains("not authorized"))
    }

    @Test
    fun `exec propagates cancellation from the authorization resolver`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        var failure: Exception? = null
        // 工具创建时解析器必须成功, 否则取消传播发生在创建阶段而不是执行阶段
        val tool = execTool(access) { failure?.let { throw it } ?: setOf(connectionA.id) }

        failure = CancellationException("cancelled")
        val error = tool.callFails(execArgs(connectionA.id, "rev-a1"))
        assertTrue(error is CancellationException)
        assertTrue(access.executed.isEmpty())
    }

    @Test
    fun `exec rejects a stale revision`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tool = execTool(access) { setOf(connectionA.id) }

        val error = tool.callFails(execArgs(connectionA.id, "rev-old"))
        assertTrue(error.message!!.contains("changed"))
        assertTrue(access.executed.isEmpty())
    }

    @Test
    fun `exec rejects a deleted connection`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tool = execTool(access) { setOf(connectionA.id) }
        access.remove(connectionA.id)

        val error = tool.callFails(execArgs(connectionA.id, "rev-a1"))
        assertTrue(error.message!!.contains("not found"))
        assertTrue(access.executed.isEmpty())
    }

    @Test
    fun `exec rejects an untrusted connection before any network call`() = runTest {
        val access = FakeSshAccess(listOf(connectionA.copy(trustedFingerprint = null)))
        val tool = execTool(access) { setOf(connectionA.id) }

        val error = tool.callFails(execArgs(connectionA.id, "rev-a1"))
        assertTrue(error.message!!.contains("not trusted"))
        assertTrue(access.executed.isEmpty())
    }

    @Test
    fun `exec surfaces typed repository failures without secrets`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tool = execTool(access) { setOf(connectionA.id) }

        access.executeError = SshRevisionMismatchException()
        assertTrue(tool.callFails(execArgs(connectionA.id, "rev-a1")).message!!.contains("changed"))

        access.executeError = SshFingerprintNotTrustedException(connectionA.id.toString())
        assertTrue(tool.callFails(execArgs(connectionA.id, "rev-a1")).message!!.contains("not trusted"))
    }

    @Test
    fun `exec rejects forged target fields and bad arguments`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tool = execTool(access) { setOf(connectionA.id) }

        // 未知字段一律拒绝, 且错误里不回显键名(键名本身可能携带注入内容)
        val forged = execArgs(
            connectionA.id,
            "rev-a1",
            extra = buildJsonObject {
                put("host", "evil.example.com")
                put("username", "root")
                put("password", "hunter2")
            },
        )
        val forgedError = tool.callFails(forged)
        assertTrue(forgedError.message!!.contains("unsupported argument"))
        assertFalse(forgedError.message!!.contains("hunter2"))
        assertFalse(forgedError.message!!.contains("evil.example.com"))

        // 缺必填
        tool.callFails(buildJsonObject { put("connectionId", connectionA.id.toString()) })
        // 空命令
        tool.callFails(execArgs(connectionA.id, "rev-a1", command = "  "))
        // 非字符串 connectionId
        tool.callFails(
            buildJsonObject {
                put("connectionId", 123)
                put("connectionRevision", "rev-a1")
                put("command", "ls")
            },
        )
        // timeoutSeconds 非整型
        tool.callFails(
            buildJsonObject {
                put("connectionId", connectionA.id.toString())
                put("connectionRevision", "rev-a1")
                put("command", "ls")
                put("timeoutSeconds", "30")
            },
        )
        // timeoutSeconds 越界拒绝而不是截断
        tool.callFails(
            buildJsonObject {
                put("connectionId", connectionA.id.toString())
                put("connectionRevision", "rev-a1")
                put("command", "ls")
                put("timeoutSeconds", 9999)
            },
        )
        // command / connectionRevision 也必须是字符串原语
        tool.callFails(
            buildJsonObject {
                put("connectionId", connectionA.id.toString())
                put("connectionRevision", "rev-a1")
                put("command", 42)
            },
        )
        tool.callFails(
            buildJsonObject {
                put("connectionId", connectionA.id.toString())
                put("connectionRevision", 7)
                put("command", "ls")
            },
        )
        // 数组入参在入口就抛错, 不进入解析
        tool.callFails(buildJsonObject { put("connectionId", buildJsonObject {}) })
        assertTrue(access.executed.isEmpty())
    }

    @Test
    fun `exec accepts explicit null for optional arguments`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tool = execTool(access) { setOf(connectionA.id) }

        val output = tool.call(
            buildJsonObject {
                put("connectionId", connectionA.id.toString())
                put("connectionRevision", "rev-a1")
                put("command", "ls")
                put("cwd", kotlinx.serialization.json.JsonNull)
                put("timeoutSeconds", kotlinx.serialization.json.JsonNull)
            },
        ).asJson()

        assertEquals(Triple(connectionA.id, "rev-a1", "ls"), access.executed.single())
        assertEquals("completed", output["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exec output carries remote status hints and caps each stream`() = runTest {
        val access = FakeSshAccess(listOf(connectionA)).apply {
            result = SshCommandResult(
                exitCode = null,
                stdout = "partial",
                stderr = "",
                timedOut = true,
                status = "timeout",
            )
        }
        val tool = execTool(access) { setOf(connectionA.id) }
        val output = tool.call(execArgs(connectionA.id, "rev-a1")).asJson()
        assertEquals("timeout", output["status"]!!.jsonPrimitive.content)
        assertTrue(output["note"]!!.jsonPrimitive.content.contains("may still be running"))

        // 每条流独立封顶 20KiB
        access.result = SshCommandResult(
            exitCode = 0,
            stdout = "x".repeat(21 * 1024),
            stderr = "y".repeat(21 * 1024),
        )
        val capped = tool.call(execArgs(connectionA.id, "rev-a1")).asJson()
        assertEquals(20 * 1024, capped["stdout"]!!.jsonPrimitive.content.length)
        assertEquals(20 * 1024, capped["stderr"]!!.jsonPrimitive.content.length)
        assertTrue(capped["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `exec failure is sanitized and never contains stored secrets`() = runTest {
        val secret = "s3cr3t-key-material"
        val access = object : SshConnectionAccess {
            override val connectionsFlow = MutableStateFlow(listOf(connectionA))
            override suspend fun list() = connectionsFlow.value
            override suspend fun getById(id: Uuid) = connectionsFlow.value.firstOrNull { it.id == id }
            override suspend fun execute(
                id: Uuid,
                expectedRevision: String,
                command: String,
                cwd: String?,
                timeoutSeconds: Int,
            ): SshCommandResult = throw RuntimeException("auth failed for deploy@10.0.0.1 with $secret")
        }
        val tool = execTool(access) { setOf(connectionA.id) }

        val error = tool.callFails(execArgs(connectionA.id, "rev-a1"))
        assertTrue(error.message!!.contains("ssh execution failed"))
        assertFalse(error.message!!.contains(secret))
        assertFalse(error.message!!.contains("10.0.0.1"))
    }

    @Test
    fun `exec always requires approval and list does not`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tools = createSshTools({ setOf(connectionA.id) }, access)
        assertTrue(tools.single { it.name == SSH_EXEC_TOOL }.needsApproval(buildJsonObject {}))
        assertFalse(tools.single { it.name == SSH_LIST_CONNECTIONS_TOOL }.needsApproval(buildJsonObject {}))
    }

    @Test
    fun `auto approval follows the live connection state`() = runTest {
        val access = FakeSshAccess(listOf(connectionA.copy(autoApproveCommands = true)))
        val tool = execTool(access) { setOf(connectionA.id) }
        val args = execArgs(connectionA.id, "rev-a1", "ls")

        assertFalse(tool.needsApproval(args))

        // 同一生成轮内关闭自动审批, 已创建的工具立即恢复要求确认
        access.setAutoApprove(connectionA.id, false)
        assertTrue(tool.needsApproval(args))
    }

    @Test
    fun `auto approval still prompts for extremely dangerous commands`() = runTest {
        val access = FakeSshAccess(listOf(connectionA.copy(autoApproveCommands = true)))
        val tool = execTool(access) { setOf(connectionA.id) }

        listOf(
            "rm -rf /tmp/x",
            "sudo -u root rm -rf /var/www",
            "sh -c 'rm -rf /var/www'",
            "find /srv -type d -exec rm -rf {} +",
            "bash -lc \"dd if=/dev/zero of=/dev/sda\"",
            "echo hi; cat /etc/passwd | xargs -I{} rm -rf {}",
            "eval 'reboot'",
        ).forEach { command ->
            assertTrue(
                "expected approval for: $command",
                tool.needsApproval(execArgs(connectionA.id, "rev-a1", command))
            )
        }
        assertFalse(tool.needsApproval(execArgs(connectionA.id, "rev-a1", "ls -la /srv")))
    }

    @Test
    fun `exec tool system prompt carries usage constraints without credentials`() = runTest {
        val access = FakeSshAccess(listOf(connectionA))
        val tool = execTool(access) { setOf(connectionA.id) }
        val prompt = tool.systemPrompt(
            me.rerere.ai.provider.Model(modelId = "m", displayName = "m"),
            emptyList(),
        )
        assertTrue(prompt.contains("ssh_list_connections"))
        assertTrue(prompt.contains("non-interactive"))
        assertTrue(prompt.contains("untrusted"))
        assertFalse(prompt.contains("10.0.0.1"))
        assertFalse(prompt.contains("deploy"))
        assertFalse(prompt.contains("SHA256"))
    }

    @Test
    fun `liveSshAuthorizedIds fails closed when conversation is missing or unreadable`() = runTest {
        val connId = Uuid.random()
        val conversation = me.rerere.rikkahub.data.model.Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        ).copy(
            config = me.rerere.rikkahub.data.model.ConversationConfig(
                sshConnectionIds = setOf(connId),
            )
        )
        val assistant = me.rerere.rikkahub.data.model.Assistant(id = conversation.assistantId)
        val settings = me.rerere.rikkahub.data.datastore.Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
        )

        // 会话可读 -> 返回会话上固定的授权集
        assertEquals(
            setOf(connId),
            liveSshAuthorizedIds(conversation.id, { conversation }, { settings }),
        )
        // 会话读不到 -> 关死
        assertTrue(liveSshAuthorizedIds(conversation.id, { null }, { settings }).isEmpty())
        // 读取抛异常 -> 关死, 不回退旧授权
        assertTrue(liveSshAuthorizedIds(conversation.id, { throw RuntimeException("db gone") }, { settings }).isEmpty())
    }

    @Test
    fun `liveSshAuthorizedIds propagates cancellation`() = runTest {
        val error = runCatching {
            liveSshAuthorizedIds(
                Uuid.random(),
                { throw CancellationException("stop") },
                { me.rerere.rikkahub.data.datastore.Settings() },
            )
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
