plugins {
    id("com.android.application")
}

android {
    namespace = "com.novarion.velaristv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.novarion.velaristv"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {\n        viewBinding = false\n    }\n\n    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
