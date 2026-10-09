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
// 固定密钥。口令来源(优先级):
//   1. gradle.properties / -P 传入的 signing.* (CI 用这个)
//   2. keystore/keystore.properties (本地开发用,不进仓库)
//   3. 兜底为本工程的固定口令
// release 缺密钥会直接报错,避免静默产出装不上的包。
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = java.util.Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(name: String, fallback: String? = null): String? =
    (project.findProperty("signing.$name") as String?)
        ?: keystoreProps.getProperty(name)
        ?: fallback

// 本工程的固定签名口令(证书指纹见 RELEASE.md)
val FIXED_STORE_PASSWORD = "CourseWidget2026!"
val FIXED_KEY_ALIAS = "coursewidget"
val FIXED_KEY_PASSWORD = "CourseWidget2026!"

// storeFile 必须显式给出(CI 指向还原出来的密钥库,本地默认用仓库里的 keystore/)
val storeFilePath: String? = signingValue("storeFile")
    ?: listOf("keystore/coursewidget.p12", "coursewidget.p12")
        .map { rootProject.file(it) }
        .firstOrNull { it.exists() }
        ?.absolutePath
val storePassword: String? = signingValue("storePassword", FIXED_STORE_PASSWORD)
val keyAlias: String? = signingValue("keyAlias", FIXED_KEY_ALIAS)
val keyPassword: String? = signingValue("keyPassword", FIXED_KEY_PASSWORD)
val hasSigning = storeFilePath != null && storePassword != null && keyAlias != null && keyPassword != null

// 签名时用到的密钥库文件(绝对路径直接采用,相对路径按仓库根解析)
val keystoreFile: java.io.File? = storeFilePath?.let { path ->
    val f = java.io.File(path)
    if (f.isAbsolute) f else rootProject.file(path)
}

val needsReleaseBuild = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}
if (needsReleaseBuild && !hasSigning) {
    throw GradleException(
        "release 构建缺少签名密钥。CI 请检查 Secret KEYSTORE_BASE64;" +
            "本地请先运行 gen-keystore.ps1"
    )
}
if (keystoreFile != null) {
    logger.lifecycle("签名密钥: " + keystoreFile.name + " (alias=" + keyAlias + ")")
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
                storeFile = keystoreFile!!
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
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
