plugins {
    id("kclip.primitive.kmp.executable")
    id("kclip.primitive.detekt")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:agent"))
            implementation(project(":core:application"))
            implementation(project(":core:diagnostics"))
            implementation(project(":core:domain"))
            implementation(project(":core:platform"))
            implementation(project(":core:protocol"))

            implementation(libs.clikt)
        }
    }
}
