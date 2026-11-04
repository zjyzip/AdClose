import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Calendar

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.materialthemebuilder")
    id("com.google.devtools.ksp")
    id("dev.rikka.tools.autoresconfig")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

autoResConfig {
    generateClass.set(true)
    generateRes.set(false)
    generatedClassFullName.set("com.close.hook.ads.util.LangList")
    generatedArrayFirstItem.set("SYSTEM")
}

materialThemeBuilder {
    themes {
        for ((name, color) in listOf(
            "Default" to "6750A4",
            "Red" to "F44336",
            "Pink" to "E91E63",
            "Purple" to "9C27B0",
            "DeepPurple" to "673AB7",
            "Indigo" to "3F51B5",
            "Blue" to "2196F3",
            "LightBlue" to "03A9F4",
            "Cyan" to "00BCD4",
            "Teal" to "009688",
            "Green" to "4FAF50",
            "LightGreen" to "8BC3A4",
            "Lime" to "CDDC39",
            "Yellow" to "FFEB3B",
            "Amber" to "FFC107",
            "Orange" to "FF9800",
            "DeepOrange" to "FF5722",
            "Brown" to "795548",
            "BlueGrey" to "607D8F",
            "Sakura" to "FF9CA8"
        )) {
            create("Material$name") {
                lightThemeFormat = "ThemeOverlay.Light.%s"
                darkThemeFormat = "ThemeOverlay.Dark.%s"
                primaryColor = "#$color"
            }
        }
    }
    generatePalette = true
}

android {
    namespace = "com.close.hook.ads"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    signingConfigs {
        create("keyStore") {
            storeFile = file("AdClose.jks")
            keyAlias = "AdClose"
            keyPassword = "rikkati"
            storePassword = "rikkati"
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.close.hook.ads"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = calculateVersionCode()
        versionName = "4.3.7"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("keyStore")
            
            externalNativeBuild {
                cmake {
                    cppFlags.add("-DDEBUG=0")
                }
            }
        }
        getByName("debug") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("keyStore")
            
            externalNativeBuild {
                cmake {
                    cppFlags.add("-DDEBUG=1")
                }
            }
        }
    }
    buildFeatures {
        aidl = true
        viewBinding = true
        buildConfig = true
        prefab = false
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/cpp/libs")
        }
    }
}

fun calculateVersionCode(): Int {
    val calendar = Calendar.getInstance()
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    return year * 10000 + month * 100 + day
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    compileOnly(libs.xposedApi)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.libxposed.`interface`)
    implementation(libs.dexkit)

    implementation("com.bytedance.android:shadowhook:2.0.0")

    implementation(libs.appcompat)
    implementation(libs.preferenceKtx)
    implementation(libs.constraintLayout)
    implementation(libs.recyclerviewSelection)
    implementation(libs.swipeRefreshLayout)
    implementation(libs.roomRuntime)
    ksp(libs.roomCompiler)
    implementation(libs.roomKtx)
    runtimeOnly(libs.lifecycleLiveDataKtx)
    implementation(libs.fragmentKtx)

    implementation(libs.material)
    implementation(libs.fastscroll)

    implementation(libs.kotlinxSerializationJson)

    implementation(libs.brotli.dec)
    implementation(libs.gson)
    implementation(libs.guava)
    implementation(libs.appcenterAnalytics)
    implementation(libs.appcenterCrashes)
    implementation(libs.mpandroidchart)
}

// Opt-in benchmark: ./gradlew :app:testDebugUnitTest --tests '*KeywordBenchmark*' -PkeywordBenchmark
val compileKeywordMemoryAgent by tasks.registering(JavaCompile::class) {
    source("src/benchmarkAgent/java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("keywordBenchmark/agentClasses"))
    options.release.set(17)
}
val keywordMemoryAgent by tasks.registering(Jar::class) {
    from(compileKeywordMemoryAgent.flatMap { it.destinationDirectory })
    archiveFileName.set("keyword-memory-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("keywordBenchmark"))
    manifest.attributes["Premain-Class"] = "com.close.hook.ads.benchmark.MemoryAgent"
}
val benchmarkEnabled = providers.gradleProperty("keywordBenchmark").isPresent
if (benchmarkEnabled) {
    tasks.withType<Test>().configureEach {
        dependsOn(keywordMemoryAgent)
        jvmArgs(
            "-javaagent:${keywordMemoryAgent.get().archiveFile.get().asFile.absolutePath}",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED"
        )
        minHeapSize = "512m"
        maxHeapSize = "2g"
        maxParallelForks = 1
        systemProperty("adclose.keywordBenchmark", "true")
        systemProperty("adclose.benchmark.output", layout.buildDirectory.dir("reports/keywordBenchmark").get().asFile.absolutePath)
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false }
    }
}
