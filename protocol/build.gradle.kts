// docs/03 §9.2.4: :protocol = Kotlin/JVM library (AD-02), JVM 21, golden.write 伝播。

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
    if (project.hasProperty("golden.write")) {
        systemProperty("golden.write", project.property("golden.write").toString())
    }
}
