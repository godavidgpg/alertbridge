plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 순수 Kotlin JVM 모듈. Android·Pleos 의존성 금지 (EngineIsolationTest가 검사한다).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("alertbridge.root", rootDir.absolutePath)
}

// docs/policy-table.md를 policy_v1.json에서 생성한다.
tasks.register<JavaExec>("generatePolicyDoc") {
    group = "documentation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.alertbridge.engine.policy.PolicyDocGeneratorKt")
    args(rootDir.resolve("docs/policy-table.md").absolutePath)
}
