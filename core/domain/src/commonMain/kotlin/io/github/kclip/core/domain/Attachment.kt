package io.github.kclip.core.domain

/**
 * 利用者にも表示する attachment の識別子。
 */
data class AttachmentId(
    val value: String,
)

/**
 * remote TTY を path 再利用から守るための identity。
 */
data class TtyIdentity(
    val device: ULong,
    val inode: ULong,
    val displayPath: String,
)

/**
 * attachment client が agent へ接続する endpoint。
 */
sealed interface IpcEndpoint {
    /**
     * Unix domain socket endpoint。
     */
    data class UnixSocket(
        val path: String,
    ) : IpcEndpoint
}

/**
 * remote に保存する attachment の利用権。
 */
data class AttachmentLease(
    val formatVersion: UShort,
    val id: AttachmentId,
    val endpoint: IpcEndpoint,
    val nonce: Secret16,
    val capabilities: Set<ClipboardCapability>,
    val scope: TtyIdentity,
    val createdAt: EpochSeconds,
)
