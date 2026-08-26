plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.js8call.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26  // Android 8.0 (required for Oboe)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Native build configuration
        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++20",
                    "-fexceptions",
                    "-frtti",
                    "-Wall",
                    "-Wextra"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26"
                )
            }
        }

        // 64-bit plus 32-bit ARM: some Fire OS devices (e.g. Fire HD 10 2019)
        // run a 32-bit userland and only accept armeabi-v7a
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Enable native optimizations
            externalNativeBuild {
                cmake {
                    cppFlags += "-O3"
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
        debug {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-g", "-O0")
                    arguments += "-DCMAKE_BUILD_TYPE=Debug"
                }
            }
        }
    }

    // CMake configuration
    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Enable prefab for Oboe
    buildFeatures {
        prefab = true
        prefabPublishing = true
    }

    // Prefab configuration (exports our library)
    prefab {
        create("js8core") {
            headers = file("../../core/include").absolutePath
        }
        create("js8core-android") {
            headers = file("../../adapters/android/include").absolutePath
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Package native libraries
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // Source sets
    sourceSets {
        getByName("main") {
            // Kotlin sources from JNI directory
            java.srcDirs(
                "../../adapters/android/jni/kotlin",
                "../../adapters/android/jni/java"
            )
        }
        getByName("androidTest") {
            // The desktop project's reference recordings, packaged into the
            // test APK rather than copied into the tree.
            assets.srcDirs("../../media/tests")
        }
    }
}

dependencies {
    // Oboe for audio
    implementation("com.google.oboe:oboe:1.8.0")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")

    // USB serial bridge (Hamlib Android adapter)
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Task to copy FFTW3 libraries if they exist
tasks.register<Copy>("copyFftw3Libs") {
    from("libs/fftw3")
    into("src/main/jniLibs")
    include("**/*.so")
}

// Run copy task before build
tasks.named("preBuild") {
    dependsOn("copyFftw3Libs")
}
