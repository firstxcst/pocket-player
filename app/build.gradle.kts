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
        versionCode = 8
        versionName = "1.5.0"
        // lite 无 native 库；full 只打 arm64-v8a，控制 FFmpeg so 带来的体积增量
        ndk { abiFilters.add("arm64-v8a") }
    }

    flavorDimensions += "codecs"
    productFlavors {
        // 轻量版：纯系统 MediaCodec，约 2.7 MB
        create("lite") { dimension = "codecs" }
        // 全格式版：NextLib FFmpeg 软解，支持 HEVC Main10 / AC3 / DTS / AV1 等
        create("full") { dimension = "codecs" }
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

    // 仅 full flavor 打包 NextLib 的 FFmpeg 软解扩展（lite 完全不引入 native 库）
    add("fullImplementation", libs.nextlib.media3ext)
}
