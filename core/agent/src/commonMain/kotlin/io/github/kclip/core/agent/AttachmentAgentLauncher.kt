package io.github.kclip.core.agent

import io.github.kclip.core.domain.Outcome

/**
 * `_attach-agent` process を起動するための interface。
 */
interface AttachmentAgentLauncher {
    fun launch(): Outcome<AgentStatusSnapshot>
}
