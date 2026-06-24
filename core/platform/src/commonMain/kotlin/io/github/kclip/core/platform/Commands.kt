package io.github.kclip.core.platform

import io.github.kclip.core.domain.Outcome

/**
 * shell を介さずに実行する child process の指定。
 */
data class CommandSpec(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 5_000,
    val maxStdoutBytes: Int = 1 * 1024 * 1024,
    val maxStderrBytes: Int = 16 * 1024,
)

/**
 * child process の終了結果。
 */
data class CommandOutput(
    val exitStatus: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandOutput) return false

        val hasSameExitStatus = exitStatus == other.exitStatus
        val hasSameStdout = stdout.contentEquals(other.stdout)
        val hasSameStderr = stderr.contentEquals(other.stderr)

        return hasSameExitStatus && hasSameStdout && hasSameStderr
    }

    override fun hashCode(): Int {
        var result = exitStatus
        result = 31 * result + stdout.contentHashCode()
        result = 31 * result + stderr.contentHashCode()

        return result
    }
}

/**
 * external command を実行する adapter。
 */
interface CommandRunner {
    fun run(spec: CommandSpec, stdin: ByteArray?): Outcome<CommandOutput>
}

/**
 * PATH などから実行ファイルを探す adapter。
 */
interface ExecutableLookup {
    fun findExecutable(name: String): String?
}
