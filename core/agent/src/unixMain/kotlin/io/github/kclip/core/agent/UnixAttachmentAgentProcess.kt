package io.github.kclip.core.agent

import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome

/**
 * Unix で attachment agent process を起動する placeholder。
 */
class UnixAttachmentAgentProcess : AttachmentAgentLauncher {
    override fun launch(): Outcome<AgentStatusSnapshot> {
        return Outcome.Err(
            KclipError.Internal(
                message = "UnixAttachmentAgentProcess is replaced by BackgroundProcessLauncher",
            ),
        )
    }
}
