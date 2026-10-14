import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "pt.filipe.enquadra.nativa"
    compileSdk = 36
    defaultConfig {
        // a different id from the web-view app: both can be installed side by side
        applicationId = "pt.filipe.enquadra.nativa"
        minSdk = 29
        targetSdk = 34
        versionCode = 14
        versionName = "N0.14"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("enquadra.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
dependencies {
    val cx = "1.4.1"
    implementation("androidx.camera:camera-core:$cx")
    implementation("androidx.camera:camera-camera2:$cx")
    implementation("androidx.camera:camera-lifecycle:$cx")
    implementation("androidx.camera:camera-view:$cx")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
