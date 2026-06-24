package primitive

import io.github.kclip.implementation
import io.github.kclip.kotlin
import io.github.kclip.library
import io.github.kclip.libs
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 共通 Kotlin Multiplatform 設定を適用する Gradle プラグイン。
 */
class KmpCommonPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")

            kotlin {
                sourceSets.named("commonMain") {
                    dependencies {
                        val kotlinBom = libs.library("kotlin-bom")
                        implementation(project.dependencies.platform(kotlinBom))
                    }
                }

                sourceSets.named("commonTest") {
                    dependencies {
                        implementation(kotlin("test"))
                    }
                }
            }
        }
    }
}
