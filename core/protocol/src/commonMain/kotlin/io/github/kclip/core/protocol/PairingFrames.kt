package io.github.kclip.core.protocol

import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.TtyIdentity

/**
 * PAIR request body を表す metadata。
 */
data class PairFrame(
    val requestedCapabilities: Set<ClipboardCapability>,
    val remoteUid: ULong,
    val username: String,
    val hostname: String,
    val ttyIdentity: TtyIdentity,
)

/**
 * PAIR success response body を表す metadata。
 */
data class PairAcceptedFrame(
    val grantedCapabilities: Set<ClipboardCapability>,
    val maxCopyBytes: UInt,
    val maxPasteBytes: UInt,
)
