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
        versionCode = 4
        versionName = "1.3"
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
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
}

