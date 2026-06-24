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
        standardInput = UnixByteInput(),
        standardOutput = UnixByteOutput(),
        clipboardBackendResolver = MacOsClipboardBackendResolver(environment, commandRunner),
    )
}
