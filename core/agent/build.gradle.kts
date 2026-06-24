plugins {
    id("kclip.primitive.kmp.unix-native")
    id("kclip.primitive.detekt")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:diagnostics"))
            implementation(project(":core:domain"))
            implementation(project(":core:platform"))
            implementation(project(":core:protocol"))
        }
    }
}
