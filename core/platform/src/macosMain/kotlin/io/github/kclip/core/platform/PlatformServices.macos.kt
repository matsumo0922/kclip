package io.github.kclip.core.platform

/**
 * macOS 用 platform service factory。
 */
fun createPlatformServices(): PlatformServices {
    val environment = UnixEnvironment()
    val commandRunner = UnixCommandRunner()

    return PlatformServices(
        environment = environment,
        clock = UnixMonotonicClock(),
        sleeper = UnixSleeper(),
        standardInput = UnixByteInput(),
        standardOutput = UnixByteOutput(),
        agentConfigInput = UnixByteInput(AGENT_CONFIG_FD),
        clipboardBackendResolver = MacOsClipboardBackendResolver(environment, commandRunner),
        commandRunner = commandRunner,
        ipcConnector = UnixIpcConnector(),
        ipcServer = UnixIpcServer(),
        backgroundProcessLauncher = UnixBackgroundProcessLauncher(),
        runtimePaths = UnixRuntimePaths(environment),
        ttyIdentityResolver = UnixTtyIdentityResolver(),
        processIdentityResolver = UnixProcessIdentityResolver(environment),
        secureRandom = UnixSecureRandom(),
        fileStore = UnixFileStore(),
    )
}

private const val AGENT_CONFIG_FD = 3
