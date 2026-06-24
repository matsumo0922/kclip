package io.github.kclip.core.platform

import io.github.kclip.core.domain.ClipboardBackendResolver
import io.github.kclip.core.domain.Deadline
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.ProtocolByteChannel
import io.github.kclip.core.domain.TtyIdentity

/**
 * platform 固有 service を束ねる composition root 用の値。
 */
data class PlatformServices(
    val environment: Environment,
    val clock: MonotonicClock,
    val sleeper: Sleeper,
    val standardInput: ByteInput,
    val standardOutput: ByteOutput,
    val agentConfigInput: ByteInput,
    val clipboardBackendResolver: ClipboardBackendResolver,
    val commandRunner: CommandRunner,
    val ipcConnector: IpcConnector,
    val ipcServer: IpcServer,
    val backgroundProcessLauncher: BackgroundProcessLauncher,
    val runtimePaths: RuntimePaths,
    val ttyIdentityResolver: TtyIdentityResolver,
    val processIdentityResolver: ProcessIdentityResolver,
    val secureRandom: SecureRandom,
    val fileStore: FileStore,
)

/**
 * process environment を読むための interface。
 */
interface Environment {
    fun get(name: String): String?

    fun snapshot(): Map<String, String>
}

/**
 * monotonic time を扱う clock。
 */
interface MonotonicClock {
    fun nowMillis(): Long

    fun deadlineAfterMillis(durationMillis: Long): Deadline {
        return Deadline(nowMillis() + durationMillis)
    }
}

/**
 * 短い待機を行う adapter。
 */
interface Sleeper {
    fun sleepMillis(durationMillis: Long): Outcome<Unit>
}

/**
 * byte stream の入力。
 */
interface ByteInput {
    fun readAll(maxBytes: Int): Outcome<ByteArray>
}

/**
 * byte stream の出力。
 */
interface ByteOutput {
    fun writeAll(bytes: ByteArray): Outcome<Unit>
}

/**
 * current process の remote metadata。
 */
data class CurrentProcessIdentity(
    val uid: ULong,
    val username: String,
    val hostname: String,
)

/**
 * current process の user/host metadata を解決する adapter。
 */
interface ProcessIdentityResolver {
    fun current(): Outcome<CurrentProcessIdentity>
}

/**
 * secret や ID の生成に使う secure random source。
 */
interface SecureRandom {
    fun readBytes(size: Int): Outcome<ByteArray>
}

/**
 * kclip runtime state 用の private file store。
 */
interface FileStore {
    fun readBytes(path: String, maxBytes: Int): Outcome<ByteArray>

    fun writePrivateBytes(path: String, bytes: ByteArray): Outcome<Unit>

    fun delete(path: String): Outcome<Unit>
}

/**
 * Unix socket などの IPC endpoint へ接続する adapter。
 */
interface IpcConnector {
    fun connect(endpoint: IpcEndpoint, deadline: Deadline): Outcome<ProtocolByteChannel>
}

/**
 * IPC listener を作成する adapter。
 */
interface IpcServer {
    fun listen(endpoint: IpcEndpoint): Outcome<IpcListener>
}

/**
 * IPC listener。
 */
interface IpcListener {
    fun accept(deadline: Deadline): Outcome<ProtocolByteChannel>

    fun close(): Outcome<Unit>
}

/**
 * 背景 process の起動指定。
 */
data class BackgroundProcessSpec(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val stdoutPath: String,
    val stderrPath: String,
)

/**
 * 起動済み background process。
 */
data class BackgroundProcess(
    val processId: Int,
)

/**
 * secret config fd を渡して background process を起動する adapter。
 */
interface BackgroundProcessLauncher {
    fun launch(spec: BackgroundProcessSpec, configBytes: ByteArray): Outcome<BackgroundProcess>
}

/**
 * kclip runtime path を組み立てる adapter。
 */
interface RuntimePaths {
    fun runtimeRoot(): Outcome<String>

    fun attachmentsDirectory(): Outcome<String>

    fun bindingsDirectory(): Outcome<String>

    fun agentsDirectory(): Outcome<String>

    fun sshDirectory(): Outcome<String>
}

/**
 * current TTY identity を解決する adapter。
 */
interface TtyIdentityResolver {
    fun current(): Outcome<TtyIdentity>
}
