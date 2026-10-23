package me.rerere.rikkahub.data.ai.tools

private val BLOCK_DEVICE_REDIRECT = Regex(""">\s*/dev/(?:sd|hd|vd|xvd|nvme|mmcblk|disk)""", RegexOption.IGNORE_CASE)
private val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""")
private val COMMAND_SUBSTITUTION = Regex("""\$\(([^()]*)\)|`([^`]*)`""")
private val SHELLS = setOf("sh", "bash", "zsh", "dash", "ksh", "ash", "mksh")
private val POWER_COMMANDS = setOf("shutdown", "reboot", "poweroff", "halt")
private val WIPE_COMMANDS = setOf("shred", "wipefs")
private val BLOCK_DEVICE_PREFIXES = listOf(
    "/dev/sd",
    "/dev/hd",
    "/dev/vd",
    "/dev/xvd",
    "/dev/nvme",
    "/dev/mmcblk",
    "/dev/disk",
)

private const val MAX_SCAN_DEPTH = 4

/**
 * 判断命令是否属于必须显式确认的极度危险操作, 开启自动审批时也不会放行。
 *
 * 匹配基于每一段命令里的任意词, 而不只是首个词, 所以 `sudo -u root rm -rf /`、
 * `env X=1 rm -rf /`、`find . -exec rm -rf {} +` 这类包装写法同样会被识别;
 * `sh -c '…'`、`eval …` 与 `$(…)`/反引号内的脚本会递归检查。
 * 误判只会多一次确认, 漏判则会绕过确认, 因此这里偏向保守。
 */
fun isExtremelyDangerousSshCommand(command: String): Boolean = scanScript(command, depth = 0)

private fun scanScript(script: String, depth: Int): Boolean {
    if (script.isBlank()) return false
    // 嵌套过深时按危险处理而不是放行, 避免用多层 `sh -c` 套壳绕过检测
    if (depth > MAX_SCAN_DEPTH) return true
    if (BLOCK_DEVICE_REDIRECT.containsMatchIn(script)) return true
    if (FORK_BOMB.containsMatchIn(script)) return true
    val substitution = COMMAND_SUBSTITUTION.findAll(script).any { match ->
        val inner = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@any false
        scanScript(inner, depth + 1)
    }
    if (substitution) return true
    return shellSegments(script).any { scanSegment(it, depth) }
}

private fun scanSegment(tokens: List<String>, depth: Int): Boolean {
    tokens.forEachIndexed { index, raw ->
        val name = raw.substringAfterLast('/')
        when {
            name in SHELLS -> {
                val payload = inlinePayload(tokens, index)
                if (payload != null && scanScript(payload, depth + 1)) return true
            }

            name == "eval" -> {
                val payload = tokens.drop(index + 1).joinToString(" ")
                if (scanScript(payload, depth + 1)) return true
            }

            name == "rm" -> if (isRecursiveForceRm(tokens.drop(index + 1))) return true
            name == "dd" -> if (ddWritesBlockDevice(tokens.drop(index + 1))) return true
            name in WIPE_COMMANDS || name in POWER_COMMANDS -> return true
            name.startsWith("mkfs") -> return true
        }
    }
    return false
}

/** `sh -c payload` 的内联脚本; 其余位置参数是脚本文件, 不展开 */
private fun inlinePayload(tokens: List<String>, shellIndex: Int): String? {
    var index = shellIndex + 1
    while (index < tokens.size) {
        val token = tokens[index]
        if (token == "--") {
            index++
            continue
        }
        if (token.startsWith("--")) {
            index++
            continue
        }
        if (token.startsWith("-") && token.length > 1) {
            if (token.drop(1).contains('c')) return tokens.getOrNull(index + 1)
            index++
            continue
        }
        return null
    }
    return null
}

/** 按引号感知的方式切成命令段; 引号内的分隔符不切分, 引号本身从词里去掉 */
private fun shellSegments(script: String): List<List<String>> {
    val segments = mutableListOf<List<String>>()
    var tokens = mutableListOf<String>()
    val current = StringBuilder()
    var tokenStarted = false
    var quote: Char? = null

    fun finishToken() {
        if (!tokenStarted) return
        tokens += current.toString()
        current.setLength(0)
        tokenStarted = false
    }

    fun finishSegment() {
        finishToken()
        if (tokens.isNotEmpty()) segments += tokens
        tokens = mutableListOf()
    }

    var index = 0
    while (index < script.length) {
        val char = script[index]
        when {
            quote == '\'' -> {
                if (char == '\'') quote = null else current.append(char)
                index++
            }

            quote == '"' -> when {
                char == '"' -> {
                    quote = null
                    index++
                }

                char == '\\' && index + 1 < script.length -> {
                    current.append(script[index + 1])
                    index += 2
                }

                else -> {
                    current.append(char)
                    index++
                }
            }

            char == '\\' && index + 1 < script.length -> {
                current.append(script[index + 1])
                tokenStarted = true
                index += 2
            }

            char == '\'' || char == '"' -> {
                quote = char
                tokenStarted = true
                index++
            }

            char.isWhitespace() -> {
                finishToken()
                if (char == '\n' || char == '\r') finishSegment()
                index++
            }

            char == ';' -> {
                finishSegment()
                index++
            }

            char == '&' || char == '|' -> {
                finishSegment()
                index++
                while (index < script.length && (script[index] == '&' || script[index] == '|')) index++
            }

            else -> {
                current.append(char)
                tokenStarted = true
                index++
            }
        }
    }
    finishSegment()
    return segments
}

private fun isRecursiveForceRm(args: List<String>): Boolean {
    var recursive = false
    var force = false
    args.forEach { token ->
        if (token == "--recursive") recursive = true
        if (token == "--force") force = true
        if (token.startsWith("-") && !token.startsWith("--")) {
            val flags = token.drop(1)
            if (flags.contains('r') || flags.contains('R')) recursive = true
            if (flags.contains('f')) force = true
        }
    }
    return recursive && force
}

private fun ddWritesBlockDevice(args: List<String>): Boolean =
    args.any { token ->
        val normalized = token.replace(" ", "")
        normalized.startsWith("of=/dev/") && BLOCK_DEVICE_PREFIXES.any { normalized.startsWith("of=$it") }
    }
