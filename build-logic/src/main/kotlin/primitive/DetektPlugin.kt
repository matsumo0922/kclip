package primitive

import io.github.kclip.configureDetekt
import io.github.kclip.library
import io.github.kclip.libs
import io.github.kclip.plugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Detekt の共通静的解析設定を適用する Gradle プラグイン。
 */
class DetektPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.plugin("detekt").pluginId)

            configureDetekt()

            dependencies {
                "detektPlugins"(libs.library("detekt-formatting"))
            }
        }
    }
}
