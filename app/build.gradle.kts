plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "th.ogay.luma"
    compileSdk = 35

    defaultConfig {
        applicationId = "th.ogay.luma"
        minSdk = 23
        targetSdk = 35
        versionCode = providers.environmentVariable("LUMA_BUILD_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.1.0"
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
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
