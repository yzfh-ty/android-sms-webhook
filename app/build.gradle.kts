import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val signingFields = listOf("ANDROID_KEYSTORE_PATH", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
val localSigning = Properties().apply {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file(".local/release-signing/signing.properties")).asText.orNull
    if (text != null) load(StringReader(text))
}
val signingEnvironment = signingFields.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val releaseSigning = if (signingEnvironment.values.any { it != null }) signingEnvironment
    else signingFields.associateWith { localSigning.getProperty(it)?.takeIf(String::isNotBlank) }
require(releaseSigning.values.none { it != null } || releaseSigning.values.all { it != null }) {
    "Release signing configuration is incomplete"
}

android {
    namespace = "com.tyverse.smswebhook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tyverse.smswebhook"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs.create("release") {
        storeFile = rootProject.file(releaseSigning["ANDROID_KEYSTORE_PATH"] ?: ".local/release-signing/release.p12")
        storePassword = releaseSigning["ANDROID_STORE_PASSWORD"]
        keyAlias = releaseSigning["ANDROID_KEY_ALIAS"] ?: "release"
        keyPassword = releaseSigning["ANDROID_KEY_PASSWORD"]
    }
    buildTypes {
        getByName("debug") {
            if (providers.gradleProperty("isolatedTestApp").orNull == "true") applicationIdSuffix = ".qa"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            if (providers.gradleProperty("isolatedTestApp").orNull == "true") applicationIdSuffix = ".qa"
            optimization {
                enable = true
            }
        }
    }
    // CI requests two ABI-specific Release APKs; local Debug/QA builds keep their existing defaults.
    splits {
        abi {
            isEnable = providers.gradleProperty("splitApks").orNull == "true"
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.viewmodel)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
