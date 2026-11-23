plugins {
    id("com.android.application")
}

val uploadStoreFile = providers.gradleProperty("IRAI_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.gradleProperty("IRAI_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.gradleProperty("IRAI_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.gradleProperty("IRAI_UPLOAD_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    uploadStoreFile,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.iraitec"
    compileSdk = 36
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    defaultConfig {
        applicationId = "com.Iraitec"
        minSdk = 24
        targetSdk = 36
        versionCode = 16 // Next Google Play upload; do not reuse version codes
        versionName = "1.6.8"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword!!
                keyAlias = uploadKeyAlias!!
                keyPassword = uploadKeyPassword!!
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
dependencies {
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    // LiteRT-LM 0.16.1 invokes SendChannel.close$default; coroutines 1.9.0\n    // is binary-incompatible with that callback and crashes the :irai_ai process.\n    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")\n    constraints {\n        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2") {\n            because("LiteRT-LM requires a compatible SendChannel API at runtime")\n        }\n    }
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
}

