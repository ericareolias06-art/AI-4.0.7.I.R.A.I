plugins {
    id("com.android.application")
}

android {
    namespace = "com.iraitec"
    compileSdk = 36
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    defaultConfig {
        applicationId = "com.iraitec"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file(providers.gradleProperty("IRAI_UPLOAD_STORE_FILE").get())
            storePassword = providers.gradleProperty("IRAI_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("IRAI_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("IRAI_UPLOAD_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
}

