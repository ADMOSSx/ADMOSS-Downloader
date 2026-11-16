plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds must use the permanent signing key supplied by the build environment.
// Debug builds remain independent from release credentials.
val keystorePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val keystorePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val keyAliasValue = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val keyPasswordValue = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val updateRepo: String = System.getenv("GITHUB_REPOSITORY") ?: ""

val signingValues = listOf(keystorePath, keystorePassword, keyAliasValue, keyPasswordValue)
val releaseSigningConfigured = signingValues.all { it != null } &&
    keystorePath?.let { file(it).isFile } == true

if (signingValues.any { it != null } && !releaseSigningConfigured) {
    val missing = buildList {
        if (keystorePath == null || keystorePath.let { !file(it).isFile }) add("KEYSTORE_PATH (existing file)")
        if (keystorePassword == null) add("KEYSTORE_PASSWORD")
        if (keyAliasValue == null) add("KEY_ALIAS")
        if (keyPasswordValue == null) add("KEY_PASSWORD")
    }
    throw GradleException("Incomplete release signing configuration. Missing or invalid: ${missing.joinToString()}")
}

android {
    namespace = "com.admoss.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.admoss.downloader"
        minSdk = 29
        targetSdk = 34
        versionCode = 22
        versionName = "1.19.0"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        resourceConfigurations += listOf("en", "ar")
    }

    // One small APK per CPU type instead of one big universal APK.
    // Most phones need the arm64-v8a one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(keystorePath))
                storePassword = requireNotNull(keystorePassword)
                keyAlias = requireNotNull(keyAliasValue)
                keyPassword = requireNotNull(keyPasswordValue)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
    }

    // Needed so the bundled Python / ffmpeg binaries can run
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }
}

// Never allow a local or CI release packaging task to silently produce a debug-signed APK.
gradle.taskGraph.whenReady {
    val releasePackagingRequested = allTasks.any { task ->
        task.name.matches(Regex("(assemble|bundle|package|install).*Release.*"))
    }
    if (releasePackagingRequested && !releaseSigningConfigured) {
        throw GradleException(
            "Release signing is not configured. Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD. " +
                "Use assembleDebug for local testing."
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // Offline sharing: QR codes (ZXing) and the camera preview (CameraX)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // yt-dlp + ffmpeg for Android (YouTube, TikTok and many more sites)
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")

    testImplementation("junit:junit:4.13.2")
}
