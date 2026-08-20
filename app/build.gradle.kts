plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.kennys_dokidoki_wallpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.kennys_dokidoki_wallpaper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("shared") {
            val keystoreFile = rootProject.file("debug.keystore")
            if (!keystoreFile.isFile) {
                error("debug.keystore が見つからないぞ。リポジトリ直下に置け。")
            }
            storeFile = keystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.biometric)
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    implementation(libs.glide)
    //noinspection KaptUsageDefinition
    annotationProcessor(libs.glide.compiler)

    implementation(libs.image.cropper)
    implementation(libs.llamatik)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
