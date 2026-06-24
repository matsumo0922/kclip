package io.github.kclip.core.platform

/**
 * Linux 用 platform service factory。
 */
fun createPlatformServices(): PlatformServices {
    return PlatformServices(
        environment = UnixEnvironment(),
        clock = UnixMonotonicClock(),
    )
}
