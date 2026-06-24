package io.github.kclip.core.platform

/**
 * macOS 用 platform service factory。
 */
fun createPlatformServices(): PlatformServices {
    return PlatformServices(
        environment = UnixEnvironment(),
        clock = UnixMonotonicClock(),
    )
}
