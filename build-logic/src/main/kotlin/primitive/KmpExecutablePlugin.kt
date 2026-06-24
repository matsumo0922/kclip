package primitive

import io.github.kclip.kotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * kclip CLI executable binary を設定する Gradle プラグイン。
 */
class KmpExecutablePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("kclip.primitive.kmp.unix-native")

            kotlin {
                targets.withType(KotlinNativeTarget::class.java).configureEach {
                    binaries.executable {
                        baseName = "kclip"
                        entryPoint = "io.github.kclip.main"
                    }
                }
            }
        }
    }
}
