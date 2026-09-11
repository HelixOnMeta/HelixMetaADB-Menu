plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "Helix.ADB"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "Helix.ADB"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}