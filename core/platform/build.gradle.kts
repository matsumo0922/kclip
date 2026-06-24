plugins {
    id("kclip.primitive.kmp.unix-native")
    id("kclip.primitive.detekt")
}

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.getByName("main") {
            cinterops {
                create("kclipSpawn") {
                    defFile(project.file("src/nativeInterop/cinterop/kclip_spawn.def"))
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:diagnostics"))
            implementation(project(":core:domain"))
        }
    }
}
