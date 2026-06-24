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
        standardInput = UnixByteInput(),
        standardOutput = UnixByteOutput(),
        clipboardBackendResolver = LinuxClipboardBackendResolver(
            environment = environment,
            executableLookup = executableLookup,
            commandRunner = commandRunner,
        ),
    )
}
