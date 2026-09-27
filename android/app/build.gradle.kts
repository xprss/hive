plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val releaseStoreFile = providers.environmentVariable("HIVE_RELEASE_STORE_FILE").orNull
android {
    namespace = "io.xprss.hive"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.xprss.hive"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
    if (!releaseStoreFile.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.environmentVariable("HIVE_RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("HIVE_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("HIVE_RELEASE_KEY_PASSWORD").orNull
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
dependencies {
    val camera = "1.6.2"
    implementation("androidx.camera:camera-core:$camera")
    implementation("androidx.camera:camera-camera2:$camera")
    implementation("androidx.camera:camera-lifecycle:$camera")
    implementation("androidx.camera:camera-view:$camera")
    implementation("androidx.camera:camera-video:$camera")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment:1.8.9")
    testImplementation("junit:junit:4.13.2")
}
