plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.aegis.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aegis.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 136
        versionName = "0.9.16"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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

dependencies {
    implementation(project(":core-browser"))
    implementation(project(":feature-search"))
    implementation(project(":feature-privacy"))
    implementation(project(":feature-scanner"))
}
