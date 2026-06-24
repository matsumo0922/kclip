plugins {
    id("kclip.primitive.kmp.unix-native")
    id("kclip.primitive.detekt")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))
        }
    }
}
