package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.kclip.core.agent.AttachmentAgent
import io.github.kclip.core.agent.AttachmentAgentConfigCodec
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.platform.PlatformServices

/**
 * background attachment agent として動作する内部 command。
 */
class AttachAgentCommand(
    private val platformServices: PlatformServices,
) : CliktCommand(
    name = "_attach-agent",
) {
    override fun run() {
        val bytes = when (val outcome = platformServices.agentConfigInput.readAll(MAX_CONFIG_BYTES)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val config = when (val outcome = AttachmentAgentConfigCodec.decode(bytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val agent = AttachmentAgent(
            config = config,
            ipcServer = platformServices.ipcServer,
            clock = platformServices.clock,
            clipboardBackendResolver = platformServices.clipboardBackendResolver,
        )
        val result = agent.runForever()
        if (result is Outcome.Err) {
            exitWith(result.error)
        }
    }

    private companion object {
        const val MAX_CONFIG_BYTES = 8 * 1024
    }
}
