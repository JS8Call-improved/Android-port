import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.js8call.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.js8call.example"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0-BETA4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            val envStoreFile = System.getenv("JS8_KEYSTORE_FILE")

            if (keystorePropsFile.exists()) {
                val props = Properties().apply {
                    FileInputStream(keystorePropsFile).use { load(it) }
                }
                props.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
                props.getProperty("storePassword")?.let { storePassword = it }
                props.getProperty("keyAlias")?.let { keyAlias = it }
                props.getProperty("keyPassword")?.let { keyPassword = it }
            } else if (!envStoreFile.isNullOrBlank()) {
                storeFile = rootProject.file(envStoreFile)
                System.getenv("JS8_KEYSTORE_PASSWORD")?.let { storePassword = it }
                System.getenv("JS8_KEY_ALIAS")?.let { keyAlias = it }
                System.getenv("JS8_KEY_PASSWORD")?.let { keyPassword = it }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null &&
                releaseSigning.storeFile != null &&
                !releaseSigning.storePassword.isNullOrBlank() &&
                !releaseSigning.keyAlias.isNullOrBlank() &&
                !releaseSigning.keyPassword.isNullOrBlank()
            ) {
                signingConfig = releaseSigning
            }
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
        buildConfig = true
    }
}

dependencies {
    // JS8Call core library
    implementation(project(":js8core-lib"))

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // LocalBroadcastManager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
