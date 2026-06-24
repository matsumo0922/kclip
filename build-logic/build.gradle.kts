plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("KmpCommonPlugin") {
            id = "kclip.primitive.kmp.common"
            implementationClass = "primitive.KmpCommonPlugin"
        }
        register("KmpUnixNativePlugin") {
            id = "kclip.primitive.kmp.unix-native"
            implementationClass = "primitive.KmpUnixNativePlugin"
        }
        register("KmpExecutablePlugin") {
            id = "kclip.primitive.kmp.executable"
            implementationClass = "primitive.KmpExecutablePlugin"
        }
        register("DetektPlugin") {
            id = "kclip.primitive.detekt"
            implementationClass = "primitive.DetektPlugin"
        }
    }
}
