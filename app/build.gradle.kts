plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "th.ogay.luma"
    compileSdk = 36

    defaultConfig {
        applicationId = "th.ogay.luma"
        minSdk = 23
        targetSdk = 35
        versionCode = providers.environmentVariable("LUMA_BUILD_NUMBER").orNull?.toIntOrNull() ?: 100
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val keyFile = providers.environmentVariable("LUMA_KEYSTORE").orNull
    signingConfigs {
        if (keyFile != null) {
            create("distribution") {
                storeFile = file(keyFile)
                storePassword = System.getenv("LUMA_STORE_PASSWORD")
                keyAlias = System.getenv("LUMA_KEY_ALIAS")
                keyPassword = System.getenv("LUMA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            signingConfig = signingConfigs.findByName("distribution")
            matchingFallbacks += "release"
        }
        release { signingConfig = signingConfigs.findByName("distribution") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.camera:camera-video:1.6.2")
    implementation("androidx.camera:camera-extensions:1.6.2")
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.opencv:opencv:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
