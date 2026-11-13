plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wechatlocspoofer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wechatlocspoofer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://api.xposed.info/") }
}

dependencies {
    // 仅编译期依赖 Xposed API，运行时由 LSPosed 框架注入，绝不能用 implementation
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")
}
