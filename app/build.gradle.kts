plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.doubaoa11y"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.doubaoa11y"
        minSdk = 26
        targetSdk = 35
        versionCode = 38
        versionName = "1.0.3"
    }

    buildTypes {
        release {
            // 不混淆：方便对照日志排查；用 debug 证书签名，直接可装
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
