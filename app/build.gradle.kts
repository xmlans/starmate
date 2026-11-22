import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 发布签名从仓库根目录的 keystore.properties 读取（不进版本库，见 README）。
// 没有这个文件时发布版不签名，assembleRelease 产出 app-release-unsigned.apk。
val releaseSigning: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "org.xmyun.starmate"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.xmyun.starmate"
        minSdk = 28
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"

        // 各版本的能力开关，经 AppEdition 交给功能模块（见 di/AppModule.kt）
        buildConfigField("String", "CONTACT_EMAIL", "\"support@xmyun.org\"")
        buildConfigField("boolean", "KEEP_ALIVE", "true")
        buildConfigField("boolean", "BATTERY_EXEMPTION_REQUEST", "true")
        buildConfigField("boolean", "CONTENT_REPORTS", "false")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 1.0 不开代码压缩：真机上测过的一直是未压缩的版本，压缩会改变运行时行为
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // 三个版本：
    // - stable：GitHub 发布的正式版，不含手机操作（无障碍服务）。
    // - beta：完整功能。包名加后缀，可以和正式版同时安装，数据互不影响。
    // - play：Google Play 特供版。不含手机操作；去掉 Play 不允许的保活服务和电池优化豁免请求
    //   （清单见 src/play/AndroidManifest.xml）；加上举报入口；目标版本升到 Play 要求的 Android 16。
    //   包名和正式版相同，它就是商店里的“星伴”。
    flavorDimensions += "edition"
    productFlavors {
        create("stable") {
            dimension = "edition"
            buildConfigField("String", "EDITION", "\"stable\"")
        }
        create("beta") {
            dimension = "edition"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            buildConfigField("String", "EDITION", "\"beta\"")
        }
        create("play") {
            dimension = "edition"
            targetSdk = 36
            buildConfigField("String", "EDITION", "\"play\"")
            buildConfigField("boolean", "KEEP_ALIVE", "false")
            buildConfigField("boolean", "BATTERY_EXEMPTION_REQUEST", "false")
            buildConfigField("boolean", "CONTENT_REPORTS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Screenshot tests render pages to PNG under build/outputs/roborazzi for visual review.
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:llm"))
    implementation(project(":core:engine"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:diary"))
    implementation(project(":feature:proactive"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:tools"))
    implementation(project(":feature:mcp"))
    // 手机操作只进 beta 版。功能模块靠依赖注入自注册，不打包进来就整个不存在：没有工具、没有设置入口、清单里也没有无障碍服务。
    "betaImplementation"(project(":feature:mobileuse"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.work.runtime)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
}
