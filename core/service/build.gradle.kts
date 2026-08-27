plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.niceproxy.core.service"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // PacServer 与 ConfigDigest 在异常路径上会调 android.util.Log，而 android.jar
        // 里的桩方法默认直接抛异常 —— 本该停在断言上的测试会变成一句看不懂的
        // "Method w in android.util.Log not mocked"。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.data)
    api(projects.core.network)
    implementation(projects.core.common)

    // AGP 不允许 library 模块把本地 .aar 作为 implementation 依赖
    //（会报 "Direct local .aar file dependencies are not supported"）。
    // 标准做法是这里 compileOnly、由 :app 模块 implementation 负责打包。
    compileOnly(files(rootProject.file("libs/libnice.aar")))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    api(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
