plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.javaTarget.get().toInt())
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
