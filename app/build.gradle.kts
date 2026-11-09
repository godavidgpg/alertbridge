plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.alertbridge.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alertbridge.app"
        minSdk = 28 // 공모전 요건: Android API 28 이상
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        // 테스트 중 실수로 112·119에 걸리지 않도록 디버그 빌드는 전화 앱을 열지 않는다.
        debug { buildConfigField("boolean", "OPEN_REAL_DIALER", "false") }
        release { buildConfigField("boolean", "OPEN_REAL_DIALER", "true") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    // 시나리오·프로필 JSON의 원본은 엔진 테스트 리소스 하나뿐이다. 앱은 같은 파일을 재생한다.
    sourceSets["main"].assets.srcDir("../engine/src/test/resources")

    testOptions { unitTests.isReturnDefaultValues = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(kotlin("test-junit"))
}
