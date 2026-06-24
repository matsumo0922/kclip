package io.github.kclip.core.platform

/**
 * Linux 用 platform service factory。
 */
fun createPlatformServices(): PlatformServices {
    val environment = UnixEnvironment()
    val commandRunner = UnixCommandRunner()
    val executableLookup = UnixExecutableLookup(environment)

    return PlatformServices(
        environment = environment,
        clock = UnixMonotonicClock(),
        sleeper = UnixSleeper(),
        standardInput = UnixByteInput(),
        standardOutput = UnixByteOutput(),
        agentConfigInput = UnixByteInput(AGENT_CONFIG_FD),
        clipboardBackendResolver = LinuxClipboardBackendResolver(
            environment = environment,
            executableLookup = executableLookup,
            commandRunner = commandRunner,
        ),
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
