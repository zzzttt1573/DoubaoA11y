plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 模块版本号写在 ConfigSpec.kt 里（豆包进程里也要用），这里读出来，只改一处
val specText = file("src/main/java/io/github/doubaoa11y/ConfigSpec.kt").readText()
fun specConst(name: String): String =
    Regex("""const val $name = "?([^"
]+)"?""").find(specText)?.groupValues?.get(1)?.trim()
        ?: error("ConfigSpec.kt 里找不到 $name")

android {
    namespace = "io.github.doubaoa11y"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.doubaoa11y"
        minSdk = 26
        targetSdk = 35
        versionCode = specConst("MODULE_VERSION_CODE").toInt()
        versionName = specConst("MODULE_VERSION_NAME")
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
    // 使用帮助页显示整合包里的“使用说明.txt”
    sourceSets.getByName("main").assets.srcDir("../package")
    packaging {
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
