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
        // versionCode/Name 按 flavor 分别定义, 见 productFlavors
    }

    // TV(机顶盒, 遥控器交互) 与 phone(手机, 触屏交互) 两个版本
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            applicationId = "com.tv.mailvod"
            versionCode = 40
            versionName = "0.7.6"
        }
        create("phone") {
            dimension = "device"
            applicationId = "com.mailvod.phone"
            versionCode = 5
            versionName = "0.1.4"
        }
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
                "META-INF/NOTICE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/DEPENDENCIES",
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
    add("tvImplementation", "androidx.leanback:leanback:1.0.0") // 仅 TV 包携带(主题 Theme.Leanback)
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

    // Lifecycle（与 core-ktx 1.9.0 配套）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
}
