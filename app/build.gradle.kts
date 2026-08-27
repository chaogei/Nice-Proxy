// 必须 import：Kotlin DSL 里 `java` 先解析成 Gradle 的 java 扩展，
// 写全限定的 java.util.Properties 会被当成读那个扩展的 util 属性
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * 版本号的单一来源是 `gradle.properties` 里的 `niceproxy.versionName`，
 * CI 在其后追加构建号。本地构建带 `-dev` 后缀，免得和 Release 里的产物混淆。
 *
 * 用环境变量而不是让 CI 改文件再提交：那种做法会在每次构建后往 main 推一个提交，
 * 既污染历史，又可能触发下一轮构建形成回环。
 */
val baseVersionName: String = providers.gradleProperty("niceproxy.versionName").get()

// 用 -PbuildNumber= 传而不是环境变量：实测在配置缓存开启时，
// providers.environmentVariable 的变化不会让缓存失效，改了变量却复用旧配置，
// 构建出来的包还带着上一次的版本号。命令行属性是构建调用的一部分，一定会失效。
val buildNumber: Int? = providers.gradleProperty("buildNumber").orNull?.toIntOrNull()

/**
 * 签名配置一律来自根目录的 `keystore.properties`（已在 .gitignore 中）。
 *
 * 本地和 CI 走同一条路径：CI 在构建前把密钥落成这个文件。这样「本地能签出来」
 * 就意味着 CI 也能，不会出现两套机制各自失效的情况。也刻意不用命令行属性传密码 ——
 * 那会把它暴露在进程列表里。
 *
 * 没有这个文件时 release 包保持未签名。未签名的 APK 根本装不上，
 * 所以下游必须自己校验，绝不能默默发出去。
 */
val keystoreProperties: Properties? =
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }

val keystoreFile: File? = keystoreProperties?.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.niceproxy"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.niceproxy"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode 必须单调递增，否则用户装不上新版。CI 用 workflow 的运行序号，
        // 它由 GitHub 维护且永不回退——比提交数可靠，后者会因 rebase 而减少。
        versionCode = buildNumber ?: 1
        versionName = buildNumber?.let { "$baseVersionName.$it" } ?: "$baseVersionName-dev"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystoreProperties?.getProperty("storePassword")
                keyAlias = keystoreProperties?.getProperty("keyAlias")
                keyPassword = keystoreProperties?.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // 没有密钥时保持未签名，让构建产物的文件名带上 -unsigned 自曝其短，
            // 而不是悄悄用 debug 密钥签出一个「能装但永远无法覆盖升级」的包
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // 单设备只需要一个 ABI 的原生库，拆分后下载体积从 31 MB 降到约 10 MB
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

composeCompiler {
    // 数据层模块不应用 Compose 插件，其类型默认被判为不稳定，
    // 持有它们的 composable 因此无法被跳过。这里显式声明为稳定。
    // 前提与风险见 compose-stability.conf。
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose-stability.conf"),
    )
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.config)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    implementation(projects.core.service)
    implementation(projects.core.designsystem)

    // core:service 只能 compileOnly 这个 AAR（AGP 不允许 library 模块
    // 依赖本地 .aar），实际打包由这里负责。见 core/service/build.gradle.kts。
    implementation(files(rootProject.file("libs/libnice.aar")))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
