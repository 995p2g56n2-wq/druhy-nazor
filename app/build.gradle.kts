plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.druhynazor"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.druhynazor.test"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-zkusebni"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
