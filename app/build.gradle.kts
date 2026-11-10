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
        versionCode = 2
        versionName = "0.2-zkusebni"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("zkusebni.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
