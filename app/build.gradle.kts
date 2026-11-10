import java.security.MessageDigest

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
        // Native HDR EF2 links the OpenCV AAR's libopencv_java4.so, which is built against c++_shared.
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
    androidResources { noCompress += "tflite" }
    // Both the OpenCV AAR and this app's CMake build ship libc++_shared.so; keep one copy.
    packaging { jniLibs { pickFirsts += "**/libc++_shared.so" } }
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
    // Standalone CompiledModel: offline GPU FP16 with explicit CPU fallback.
    implementation("com.google.ai.edge.litert:litert:2.1.6")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.camera:camera-video:1.6.2")
    implementation("androidx.camera:camera-extensions:1.6.2")
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("org.opencv:opencv:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

val verifyDetailModel by tasks.registering {
    inputs.files("src/main/assets/luma-detail-sr2.tflite", "src/main/assets/luma-detail-sr2.json")
    doLast {
        val model = file("src/main/assets/luma-detail-sr2.tflite")
        val manifest = file("src/main/assets/luma-detail-sr2.json")
        check(model.isFile && manifest.isFile) { "Prepare the offline model first: python model/build_detail.py --verify (see docs/DETAIL_SR2.md)" }
        val digest = MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(manifest.readText().contains(digest)) { "Detail SR model does not match its verified manifest" }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyDetailModel) }

