import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从工程根目录的 keystore.properties 读取签名配置（密码不写进构建文件）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.wozai.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wozai.app"
        minSdk = 24          // 覆盖 Android 7.0 及以上，兼容大部分老人机/子女淘汰机
        targetSdk = 34
        versionCode = 6
        versionName = "0.6.6"
    }

    // AGP 8 默认不再生成 BuildConfig；AppLogger 需要 VERSION_NAME/DEBUG 写进日志
    buildFeatures {
        buildConfig = true
    }

    // release lint 检查不产出 APK 内容，却是 native 内存高峰来源；禁用以提升编译稳定性
    lint {
        checkReleaseBuilds = false
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // 正式版已内置日志导出，不再单独出 debug 测试版
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 纯系统原生定位（LocationManager），不引入 Google 服务，适配国产手机
    // 已移除 com.google.android.gms:play-services-location
}
