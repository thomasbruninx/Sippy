plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}
android {
    namespace = "org.sippy"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.sippy"
        minSdk = 28
        targetSdk = 36
        versionCode = 10
        versionName = "1.1.7"
        testInstrumentationRunner = "org.sippy.SettingsChecks"
        providers.gradleProperty("targetAbi").orNull?.let { abi ->
            require(abi in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) { "Unsupported targetAbi: $abi" }
            ndk { abiFilters += abi }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

dependencies {
    implementation("com.unboundid:unboundid-ldapsdk:7.0.6")
    implementation("androidx.activity:activity:1.13.0")
    val customSdk = providers.gradleProperty("sipSdkAar").orNull
    implementation(if (customSdk == null) "org.linphone:linphone-sdk-android:5.4.127" else files(customSdk))
}
