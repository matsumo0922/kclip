package primitive

import io.github.kclip.kotlin
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * kclip の Unix 系 Kotlin/Native target を設定する Gradle プラグイン。
 */
class KmpUnixNativePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("kclip.primitive.kmp.common")

            kotlin {
                macosArm64()
                linuxX64()
                linuxArm64()

                val commonMain = sourceSets.getByName("commonMain")
                val commonTest = sourceSets.getByName("commonTest")

                val unixMain = sourceSets.maybeCreate("unixMain").apply {
                    dependsOn(commonMain)
                }
                val unixTest = sourceSets.maybeCreate("unixTest").apply {
                    dependsOn(commonTest)
                }
                val macosMain = sourceSets.maybeCreate("macosMain").apply {
                    dependsOn(unixMain)
                }
                val macosTest = sourceSets.maybeCreate("macosTest").apply {
                    dependsOn(unixTest)
                }
                val linuxMain = sourceSets.maybeCreate("linuxMain").apply {
                    dependsOn(unixMain)
                }
                val linuxTest = sourceSets.maybeCreate("linuxTest").apply {
                    dependsOn(unixTest)
                }

                sourceSets.getByName("macosArm64Main").dependsOn(macosMain)
                sourceSets.getByName("macosArm64Test").dependsOn(macosTest)
                sourceSets.getByName("linuxX64Main").dependsOn(linuxMain)
                sourceSets.getByName("linuxX64Test").dependsOn(linuxTest)
                sourceSets.getByName("linuxArm64Main").dependsOn(linuxMain)
                sourceSets.getByName("linuxArm64Test").dependsOn(linuxTest)
            }
        }
    }
}
