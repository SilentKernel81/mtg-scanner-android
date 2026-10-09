import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release signing key is not in the repository: keystore.properties (ignored by git) names the
// keystore, the key and a file holding the password. Without it a release build is left unsigned.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.cardscanner"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.cardscanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Recorded scanner frames for OutlineParityTest: ./gradlew connectedDebugAndroidTest -PdebugFrames=<dir with frames/>
    providers.gradleProperty("debugFrames").orNull?.let { frames ->
        sourceSets.getByName("androidTest").assets.directories.add(frames)
    }

    signingConfigs {
        keystore?.let { k ->
            create("release") {
                storeFile = file(k.getProperty("storeFile"))
                keyAlias = k.getProperty("keyAlias")
                val password = file(k.getProperty("passwordFile")).readText().trim()
                storePassword = password
                keyPassword = password
            }
        }
    }

    // OpenCV ships native libraries for every ABI, ~50 MB each: debug builds take phones and the
    // x86_64 emulator, the release only phones (-PreleaseAbis=arm64-v8a,x86_64 for an emulator)
    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            ndk { abiFilters += (providers.gradleProperty("releaseAbis").orNull ?: "arm64-v8a").split(",") }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.opencv)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
