// :protocol — Phone / Glass で共有する wire protocol 定義 (Phase 3 §2 / AD-02)。
// Android 依存なしの Kotlin/JVM library。

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // golden 再生成モードを Gradle property → JVM system property に伝播。
    // 通常は verify-only、`-Pgolden.write=true` でファイル書き込みモードに切替。
    if (project.hasProperty("golden.write")) {
        systemProperty("golden.write", project.property("golden.write").toString())
    }
}
