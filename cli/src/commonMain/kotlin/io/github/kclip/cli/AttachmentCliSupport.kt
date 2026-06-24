package io.github.kclip.cli

import io.github.kclip.core.agent.AttachmentClipboardBackend
import io.github.kclip.core.agent.FileAttachmentStateRepository
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardBackend
import io.github.kclip.core.domain.ClipboardOperation
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairingMaterial
import io.github.kclip.core.platform.Environment
import io.github.kclip.core.platform.PlatformServices

/**
 * pair code から導出される remote Unix socket path。
 */
fun remoteSocketPath(material: PairingMaterial): String {
    return "/tmp/kclip-${material.socketId.value.lowercase()}.sock"
}

/**
 * attachment state repository を作る helper。
 */
fun attachmentStateRepository(platformServices: PlatformServices): FileAttachmentStateRepository {
    return FileAttachmentStateRepository(
        fileStore = platformServices.fileStore,
        runtimePaths = platformServices.runtimePaths,
        ttyIdentityResolver = platformServices.ttyIdentityResolver,
    )
}

/**
 * copy/paste 用 backend を attachment 優先で解決する helper。
 */
fun resolveClipboardBackend(
    operation: ClipboardOperation,
    preference: BackendPreference,
    attachmentId: AttachmentId?,
    platformServices: PlatformServices,
): Outcome<ClipboardBackend> {
    val shouldTryAttachment = preference == BackendPreference.AUTO || preference == BackendPreference.ATTACHMENT
    if (shouldTryAttachment) {
        val attachmentBackend = resolveAttachmentBackend(attachmentId, platformServices)
        if (attachmentBackend is Outcome.Ok) {
            return attachmentBackend
        }
        val error = (attachmentBackend as Outcome.Err).error
        if (preference == BackendPreference.ATTACHMENT || !isMissingCurrentBinding(error)) {
            return attachmentBackend
        }
        if (requiresAttachmentForMissingBinding(operation, preference, platformServices.environment)) {
            return Outcome.Err(
                KclipError.AttachmentUnavailable(
                    attachmentId = null,
                    message = "no local clipboard attachment is bound to this SSH terminal",
                    detail = "run `kclip pair --paste` remotely, then `kclip attach --paste=allow <ssh-destination>` locally",
                ),
            )
        }
    }

    return platformServices.clipboardBackendResolver.resolve(preference)
}

private fun resolveAttachmentBackend(
    attachmentId: AttachmentId?,
    platformServices: PlatformServices,
): Outcome<ClipboardBackend> {
    val repository = attachmentStateRepository(platformServices)
    val lease = if (attachmentId == null) {
        repository.readCurrentLease()
    } else {
        repository.readLease(attachmentId)
    }
    if (lease is Outcome.Err) {
        return lease
    }

    return Outcome.Ok(
        AttachmentClipboardBackend(
            lease = (lease as Outcome.Ok).value,
            connector = platformServices.ipcConnector,
            clock = platformServices.clock,
        ),
    )
}

private fun isMissingCurrentBinding(error: KclipError): Boolean {
    return error is KclipError.AttachmentUnavailable && error.attachmentId == null
}

internal fun requiresAttachmentForMissingBinding(
    operation: ClipboardOperation,
    preference: BackendPreference,
    environment: Environment,
): Boolean {
    val hasSshConnection = environment.get("SSH_CONNECTION")?.isNotBlank() == true
    val hasSshClient = environment.get("SSH_CLIENT")?.isNotBlank() == true
    val isAutoPaste = operation == ClipboardOperation.PASTE && preference == BackendPreference.AUTO

    return isAutoPaste && (hasSshConnection || hasSshClient)
}

/**
 * pair code の endpoint を作る helper。
 */
fun remotePairEndpoint(material: PairingMaterial): IpcEndpoint.UnixSocket {
    return IpcEndpoint.UnixSocket(remoteSocketPath(material))
}
