import java.util.Calendar

plugins {
    id("com.android.application")
}

android {
    namespace = "com.close.hook.ads"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.close.hook.ads"
        minSdk = 26
        targetSdk = 33
        versionCode = calculateVersionCode()
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isDebuggable = false
        }
    }

    buildFeatures {
        viewBinding = true
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
    compileOnly("de.robv.android.xposed:api:82")

    implementation("com.google.android.material:material:1.8.0")

    implementation("io.reactivex.rxjava3:rxjava:3.1.5")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")

    implementation("androidx.lifecycle:lifecycle-reactivestreams:2.4.1")

    implementation("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.12.0")
}
