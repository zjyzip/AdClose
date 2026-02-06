// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.materialThemeBuilder) apply false 
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.autoresconfig) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
