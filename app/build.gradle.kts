import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------- 版本号
// 版本号的唯一来源是仓库根目录的 version.json。
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
// 密钥来源优先级:
//   1. -Psigning.* / gradle.properties 里的 signing.*  (CI 用这个)
//   2. keystore/keystore.properties                    (本地开发,不进仓库)
//   3. 本工程固定口令                                   (兜底,密钥文件本身仍受保护)
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

fun signingValue(name: String): String? = project.findProperty("signing.$name") as String?
    ?: keystoreProps.getProperty(name)

val FIXED_STORE_PASSWORD = "CourseWidget2026!"
val FIXED_KEY_ALIAS = "coursewidget"
val FIXED_KEY_PASSWORD = "CourseWidget2026!"

// 变量名刻意用 ksp/keystore 前缀,避免与外层变量同名遮蔽 ——
// 之前写成 storePassword = storePassword 时,右侧被解析成本地变量,
// 导致 SigningConfig 里的口令始终是 null(报 "missing required property")。
val keystorePath: String? = signingValue("storeFile")
    ?: listOf("keystore/coursewidget.p12", "coursewidget.p12")
        .map { rootProject.file(it) }
        .firstOrNull { it.exists() }
        ?.absolutePath
val keystoreStorePassword: String? = signingValue("storePassword") ?: FIXED_STORE_PASSWORD
val keystoreKeyAlias: String? = signingValue("keyAlias") ?: FIXED_KEY_ALIAS
val keystoreKeyPassword: String? = signingValue("keyPassword") ?: FIXED_KEY_PASSWORD
val hasSigning = keystorePath != null &&
    keystoreStorePassword != null &&
    keystoreKeyAlias != null &&
    keystoreKeyPassword != null

// 绝对路径直接采用,相对路径按仓库根解析
val keystoreFile: File? = keystorePath?.let { path ->
    val f = File(path)
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
    logger.lifecycle("签名密钥: " + keystoreFile.name + " (alias=" + keystoreKeyAlias + ")")
}

android {
    namespace = "com.veid.coursewidget"
    compileSdk = 35

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
                storeFile = keystoreFile
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
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
            // 本地调试同样用固定签名,避免 debug/release 互相覆盖安装时冲突
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

    // 产物文件名带上版本号:CourseWidget-v0.1.0-release.apk
    // 只能用 AGP 的这个 API,AGP 8 的 Kotlin DSL 没有 archivesBaseName。
    applicationVariants.all {
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "CourseWidget-v$appVersionName-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
