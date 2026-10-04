// 显式 import：Kotlin DSL 里 `java` 这个名字会被遮蔽，
// 写成全限定名 java.util.Properties 会报 Unresolved reference: util
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/* ══════════════════════════════════════════════════════════════════════
   签名配置
   ══════════════════════════════════════════════════════════════════════
   从项目根目录的 keystore.properties 读取（这个文件**不进版本库**，见 .gitignore）。
   文件不存在时不配置 release 签名 —— 这样 assembleRelease 会产出
   app-release-unsigned.apk，可以用来验证 R8 混淆是否正常，
   但**不可能**误装成一个「正式版」。

   格式（keystore.properties）：
       storeFile=../summer-journal.jks
       storePassword=你的密码
       keyAlias=summerjournal
       keyPassword=你的密码
   ══════════════════════════════════════════════════════════════════════ */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile") != null

/*
 * 版本号来源：项目根目录的 version.properties
 * 好处是 bump-version 脚本只需要改那两行 ASCII 文本，
 * 不用去动这个带中文注释的 Kotlin 文件 —— 用批处理重写 .kts 很容易写坏。
 */
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}
val appVersionCode = versionProps.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 1
val appVersionName = versionProps.getProperty("versionName")?.trim() ?: "1.0.0"

android {
    namespace = "com.summer.journal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.summer.journal"
        minSdk = 26                         // Android 8.0，覆盖绝大多数在用机型
        targetSdk = 35

        /* ── 版本号 ──
           真正生效的是项目根目录的 version.properties，这个文件由
           bump-version.bat 1.1.0（或 .sh）自动改，也可以手动改那两行。
           规则见《版本更新指南.md》第三节。
        */
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            /*
             * 只保留真机在用的两种 ABI。
             *
             * ML Kit 的 OCR 原生库 `libmlkit_google_ocr_pipeline.so` 单个就有 10MB+，
             * 四套 ABI 加起来约 39MB，而 x86/x86_64 只在模拟器上用得上。
             * 过滤掉能把 APK 减掉约 22MB（解压后）。
             *
             * 需要在模拟器上调试时，把下面这行注释掉即可。
             */
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 天气用 Open-Meteo，不需要任何 API Key / Secret
        // 教务系统也不走第三方凭证 —— 所以 BuildConfig 里没有任何敏感字段
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 开启 v2 + v3 签名：v1 在新系统上已不必要，v3 支持密钥轮换
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没有 keystore 就保持未签名，产 app-release-unsigned.apk
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // java.time 在 API 26 以下也安全
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true   // Roborazzi 截图测试需要
    }
}

ksp {
    // Room 的 schema 导出目录，配合 Migration 测试
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.splashscreen)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    // Worker 里也要能 @Inject，必须带 hilt-work + androidx 的 hilt-compiler
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler.androidx)

    // 本地存储
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    // 网络
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // 教务系统课表解析
    implementation(libs.jsoup)

    // OCR：图片文字识别（手札图片转正文 / 课表截图识别）
    // bundled 版模型随 APK 分发，完全离线，不依赖 Google Play 服务
    implementation(libs.mlkit.text.recognition.chinese)

    // 媒体 / 图片
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.coil.compose)

    // 图片选择（PickVisualMedia 在 activity-ktx 里）
    implementation(libs.photopicker)

    // 后台
    implementation(libs.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
}
