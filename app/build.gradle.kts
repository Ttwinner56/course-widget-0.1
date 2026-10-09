plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------- 版本号
// 版本号的唯一来源是仓库根目录的 version.json,由 CI 或本机构建脚本读取后传入。
// 优先级:命令行 -PversionName/-PversionCode > version.json > 兜底值
val versionFile = rootProject.file("version.json")
val versionText: String = if (versionFile.exists()) versionFile.readText() else ""
val jsonVersionName: String? = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"")
    .find(versionText)?.groupValues?.get(1)
val jsonVersionCode: Int? = Regex("\"versionCode\"\\s*:\\s*(\\d+)")
    .find(versionText)?.groupValues?.get(1)?.toIntOrNull()

val appVersionName: String = (project.findProperty("versionName") as String?) ?: jsonVersionName ?: "0.0.0"
val appVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: jsonVersionCode ?: 1

// ---------------------------------------------------------------- 签名
// 固定密钥通过 Gradle 属性传入(CI 里来自 GitHub Secrets)。
// release 必须有密钥,否则产出的 APK 装不上 —— 这里直接报错,避免静默产出废包。
val storeFilePath: String? = project.findProperty("signing.storeFile") as String?
val storePassword: String? = project.findProperty("signing.storePassword") as String?
val keyAlias: String? = project.findProperty("signing.keyAlias") as String?
val keyPassword: String? = project.findProperty("signing.keyPassword") as String?
val hasSigning = storeFilePath != null && storePassword != null && keyAlias != null && keyPassword != null

val needsReleaseBuild = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}
if (needsReleaseBuild && !hasSigning) {
    throw GradleException(
        "release 构建缺少签名密钥。\n" +
            "CI 上请配置 GitHub Secrets: KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD;\n" +
            "本地请先运行: powershell -ExecutionPolicy Bypass -File gen-keystore.ps1 然后使用 build-local.ps1。"
    )
}

android {
    namespace = "com.veid.coursewidget"
    compileSdk = 35

    // 产物基础名:最终会得到 CourseWidget-v0.1.0-release.apk
    // 只用稳定的公开 API,不碰 AGP 内部类(内部 API 跨版本会崩)。
    @Suppress("DEPRECATION")
    archivesBaseName = "CourseWidget-v$appVersionName"

    defaultConfig {
        applicationId = "com.veid.coursewidget"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasSigning) {
            create("fixed") {
                storeFile = file(storeFilePath!!)
                storePassword = storePassword
                keyAlias = keyAlias
                keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        debug {
            // 本地调试也用同一套签名,这样 debug/release 包可以互相覆盖安装
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("fixed")
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
