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
        versionCode = 29
        versionName = "0.29-zkusebni"
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
    testOptions {
        unitTests.all { it.testLogging { showStandardStreams = true; events("passed", "failed") } }
    }
}

dependencies {
    // Modul Dopisy: fotoaparát, který sám najde okraje papíru (stahuje se přes služby Google)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Čtení textu z fotky přímo v telefonu, bez internetu
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
