plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.xmyun.starmate.feature.proactive"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// 单元测试跑在宿主 JVM 上：去掉只带 Android ABI 本地库的 sqlite-bundled-android，换成 JVM 变体（同 :core:data）。
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.sqlite.bundled)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    testImplementation(libs.work.testing)
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.7.1")
}
