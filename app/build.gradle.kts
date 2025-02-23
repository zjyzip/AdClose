import java.util.Calendar

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.materialthemebuilder")
    id("com.google.devtools.ksp")
    id("dev.rikka.tools.autoresconfig")
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
    // Add Material Design 3 color tokens (such as palettePrimary100) in generated theme
    // rikka.material >= 2.0.0 provides such attributes
    generatePalette = true
}

android {
    namespace = "com.close.hook.ads"
    compileSdk = 34

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
        targetSdk = 34
        versionCode = calculateVersionCode()
        versionName = "2.2.8"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("keyStore")
        }
        getByName("debug") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("keyStore")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

fun calculateVersionCode(): Int {
    val calendar = Calendar.getInstance()
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    return year * 10000 + month * 100 + day
}

configurations.configureEach {
    exclude("androidx.appcompat", "appcompat")
}

dependencies {
    compileOnly(libs.xposedApi)
    implementation(libs.dexkit)

    implementation(libs.appcompat)
    implementation(libs.preferenceKtx)
    implementation(libs.constraintLayout)
    implementation(libs.recyclerviewSelection)
    implementation(libs.roomRuntime)
    ksp(libs.roomCompiler)
    implementation(libs.roomKtx)
    runtimeOnly(libs.lifecycleLiveDataKtx)
    implementation(libs.fragmentKtx)

    implementation(libs.material)
    implementation(libs.about)
    implementation(libs.fastscroll)
    implementation(libs.rikkaMaterial)
    implementation(libs.rikkaMaterialPreference)

    implementation(libs.gson)
    implementation(libs.guava)
    implementation(libs.glide)
    annotationProcessor(libs.glideCompiler)
    implementation(libs.appcenterAnalytics)
    implementation(libs.appcenterCrashes)
}
