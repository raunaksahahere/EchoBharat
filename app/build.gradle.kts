import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Release signing, read from an untracked keystore.properties (Rules §21) with keys
 * storeFile, storePassword, keyAlias, keyPassword. Without it, release builds come out
 * unsigned rather than failing.
 */
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

/** Directory holding indic-en/ and en-indic/ model exports, for the opt-in MT test. */
val mtModels: String? = providers.gradleProperty("mtModels").orNull

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.echobharat"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "com.echobharat"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 6
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk {
                // Real phones only. ONNX Runtime ships a ~20 MB .so per ABI, and the
                // x86 pair is emulator-only — an emulator cannot exercise the BLE mesh
                // that is the point of this app, so they are pure APK weight.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Release APKs are split per CPU architecture: ONNX Runtime is most of the APK and a
    // phone only runs its own copy, so each split is roughly half the universal size —
    // which matters when the app itself is passed phone to phone over Bluetooth. x86 is
    // left out as for debug: emulators cannot run the mesh. Debug stays one APK.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Emulator-only runtimes, out of every APK including the universal split.
        jniLibs {
            excludes += listOf("lib/x86/**", "lib/x86_64/**")
        }
    }

    testOptions {
        // android.util.Log and friends return defaults instead of throwing, so plain JVM
        // tests can exercise code that logs.
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Opt-in end-to-end translation test against the real models; see
            // IndicTrans2DesktopTest. Skipped unless -PmtModels=<dir> is given.
            mtModels?.let { test.systemProperty("echobharat.mtModels", it) }
            test.maxHeapSize = "2g"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

composeCompiler {
    includeComposeMappingFile.set(false)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Permissions
    implementation(libs.accompanist.permissions)

    // Cryptography
    implementation(libs.bundles.cryptography)

    // JSON
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Bluetooth BLE
    implementation(libs.nordic.ble)

    // Security preferences (EncryptedSharedPreferences)
    implementation(libs.androidx.security.crypto)

    // On-device inference for STT (IndicConformer + Silero VAD) and TTS (FastPitch + HiFi-GAN)
    implementation(libs.onnxruntime.android)

    // HTTP client — used ONLY by the Model Manager for one-time language-pack download
    implementation(libs.okhttp)

    // Testing
    testImplementation(libs.bundles.testing)
    if (mtModels != null) testImplementation(libs.onnxruntime.jvm)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
