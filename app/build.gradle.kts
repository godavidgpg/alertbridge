import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.alertbridge.app"
    compileSdk = 36
    // 차속(PERF_VEHICLE_SPEED). 차량용 Android에만 있어 매니페스트에서 required=false로 선언한다.
    useLibrary("android.car")

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

    // Claude API 키. local.properties(git 제외)의 anthropic.apiKey 또는 환경 변수 ANTHROPIC_API_KEY.
    // 키를 앱에 넣는 것은 시연용이다. 배포할 때는 서버 프록시를 거친다.
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val anthropicKey = localProps.getProperty("anthropic.apiKey") ?: System.getenv("ANTHROPIC_API_KEY") ?: ""
    defaultConfig { buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicKey\"") }
    // 공공데이터포털 자동차보험 통계 키. local.properties의 datagokr.serviceKey 또는 환경 변수 DATAGOKR_SERVICE_KEY.
    // 없으면 앱에 내장한 통계(2026년 1~8월 누계)를 쓴다.
    val dataGoKrKey = localProps.getProperty("datagokr.serviceKey") ?: System.getenv("DATAGOKR_SERVICE_KEY") ?: ""
    defaultConfig { buildConfigField("String", "DATAGOKR_SERVICE_KEY", "\"$dataGoKrKey\"") }

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
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":agent"))
    implementation("androidx.compose.material:material-icons-core")
    implementation("ai.pleos.playground:Vehicle:2.3.2")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3") // 인적사항 교환 QR

    testImplementation(kotlin("test-junit"))
}
