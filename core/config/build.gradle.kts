plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.javaTarget.get().toInt())
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)

    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Gradle 的 -D 只作用于守护进程，需要显式转发给测试 JVM，
    // 否则 -Dgolden.update=true 不会生效。
    systemProperty(
        "golden.update",
        providers.systemProperty("golden.update").getOrElse("false"),
    )
}
