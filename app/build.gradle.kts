plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ilukezippo.lapfold"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ilukezippo.lapfold"
        minSdk = 33
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            storeFile = file("../lapfold.keystore")
            storePassword = "lapfold123"
            keyAlias = "lapfold"
            keyPassword = "lapfold123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}
android { testOptions { unitTests.isReturnDefaultValues = true } }
