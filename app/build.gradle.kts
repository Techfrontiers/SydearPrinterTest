plugins {
    id("com.android.application")
}

android {
    namespace = "com.sydear.printertest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sydear.printertest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
