package io.github.kclip

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

fun DependencyHandlerScope.implementation(artifact: Dependency) {
    add("implementation", artifact)
}

fun DependencyHandlerScope.implementation(artifact: MinimalExternalModuleDependency) {
    add("implementation", artifact)
}

fun DependencyHandlerScope.api(artifact: Dependency) {
    add("api", artifact)
}

fun DependencyHandlerScope.api(artifact: MinimalExternalModuleDependency) {
    add("api", artifact)
}

fun DependencyHandlerScope.testImplementation(artifact: MinimalExternalModuleDependency) {
    add("testImplementation", artifact)
}

fun Project.kotlin(action: KotlinMultiplatformExtension.() -> Unit) {
    extensions.configure(action)
}
