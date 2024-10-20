// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("dev.rikka.tools.materialthemebuilder") version "1.3.3"
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    id("dev.rikka.tools.autoresconfig") version "1.2.2" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}