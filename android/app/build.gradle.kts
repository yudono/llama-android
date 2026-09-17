plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.llamacpp.local"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.llamacpp.local"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // Flag CMake utk backend GPU Vulkan (glslc + SPIRV-Headers di mesin
        // build, bukan di HP). Bisa dioverride via env VULKAN_GLSLC.
        externalNativeBuild {
            cmake {
                val home = System.getProperty("user.home")
                val glslc = System.getenv("VULKAN_GLSLC") ?: "/opt/homebrew/bin/glslc"
                arguments(
                    "-DVulkan_GLSLC_EXECUTABLE=$glslc",
                    "-DSPIRV-Headers_DIR=$home/.vulkan-deps/install/share/cmake/SPIRV-Headers",
                    // NDK tak menyertakan vulkan.hpp (C++ binding); pakai Khronos.
                    "-DVulkan_INCLUDE_DIR=$home/.vulkan-deps/Vulkan-Headers/include",
                    // Stub libvulkan API-24 tak punya simbol Vulkan 1.1+
                    // (mis. vkGetPhysicalDeviceFeatures2). Link lawan stub
                    // API-34; aman: libggml-vulkan.so hanya di-dlopen bila
                    // backend Vulkan dipakai, dan ggml mengecek dukungan
                    // device sblm menggunakannya. minSdk Java tetap 24.
                    "-DANDROID_PLATFORM=android-34"
                )
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            // NOTE: armeabi-v7a disengaja TIDAK diikutkan — llama.cpp b10893
            // gagal kompilasi di ARM 32-bit (vld1q_f16 tak tersedia di armv7).
            // HP 32-bit juga tak realistis untuk model GGUF ratusan MB.
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Pakai debug key agar APK/AAB rilis GitHub bisa langsung diinstal
            // (sideload). Untuk Play Store, ganti dengan upload key sendiri.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
