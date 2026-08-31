plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.tv.mailvod"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tv.mailvod"
        minSdk = 21
        targetSdk = 34
        versionCode = 19
        versionName = "0.4.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE",
                "META-INF/DEPENDENCIES",
                "META-INF/mailcap",
                "META-INF/*.kotlin_module"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX core + TV（低版本稳定组合，参考 jyt2018/Downm3u8-android）
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines（低版本，兼容 minSdk 21）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")

    // ExoPlayer 2.x（旧包名 com.google.android.exoplayer，适配低版本设备）
    implementation("com.google.android.exoplayer:exoplayer:2.18.5")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.18.5")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.18.5")

    // OkHttp（低版本稳定）
    implementation("com.squareup.okhttp3:okhttp:4.9.3")

    // JavaMail for Android (IMAP)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Lifecycle（与 core-ktx 1.9.0 配套）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
}
