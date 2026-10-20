plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hasaan.busymode"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hasaan.busymode"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // libnode.so needs the shared C++ runtime, so package libc++_shared.so with the app.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    signingConfigs {
        create("stable") {
            val ks = System.getenv("BUSYMODE_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("BUSYMODE_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("BUSYMODE_KEY_ALIAS") ?: "busymode"
                keyPassword = System.getenv("BUSYMODE_KEYSTORE_PASSWORD") ?: "android"
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the build machine's standard Android test key so it installs directly.
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        // Compress the Node.js runtime inside the APK (much smaller download).
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libnode.so"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources {
        // Keep the engine bundle exactly as built.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Offline wake-word detection ("Assistant")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
