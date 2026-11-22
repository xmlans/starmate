plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.xmyun.starmate.core.ui"
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

dependencies {
    api(project(":core:model"))

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.material3)
    api(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    api(libs.coil.compose)

    // 毛玻璃。只在 core:ui 内部使用，功能模块通过 glass 包里的封装调用。
    implementation(libs.haze)
    implementation(libs.haze.blur)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
}
