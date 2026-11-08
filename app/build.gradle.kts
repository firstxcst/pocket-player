plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.silas.pocketplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silas.pocketplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        // 无 native 库：播放完全走系统 MediaCodec，不打包任何 .so
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions { jvmTarget = "17" }

    // BuildConfig.VERSION_NAME 供应用内版本检测使用（AGP 8 默认不生成）
    buildFeatures { buildConfig = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // 转码为兼容 MP4 / 去音轨 / 片段导出。不需要转码就注释掉，再省约 1MB
    implementation(libs.media3.transformer)

    // 需要 AC3 / DTS / RMVB 等冷门编码的软解时再打开（会显著增大 APK）：
    // implementation(libs.media3.decoder.ffmpeg)
}
