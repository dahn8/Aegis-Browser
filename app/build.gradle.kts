import java.util.Properties

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
        versionCode = 138
        versionName = "0.9.18"
    }

    signingConfigs {
        create("release") {
            val envStore = System.getenv("AEGIS_KEYSTORE_PATH")
            if (!envStore.isNullOrBlank()) {
                storeFile = file(envStore)
                storePassword = System.getenv("AEGIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AEGIS_KEY_ALIAS")
                keyPassword = System.getenv("AEGIS_KEY_PASSWORD")
                storeType = "PKCS12"
            } else {
                val propsFile = rootProject.file("keystore.properties")
                if (propsFile.exists()) {
                    val props = Properties()
                    propsFile.inputStream().use { props.load(it) }
                    storeFile = rootProject.file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                    storeType = props.getProperty("storeType", "PKCS12")
                }
            }
        }
    }

    val releaseSigningConfig = signingConfigs.getByName("release")

    buildTypes {
        release {
            if (releaseSigningConfig.storeFile != null) {
                signingConfig = releaseSigningConfig
            }
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
