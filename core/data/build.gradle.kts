plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.niceproxy.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.config)
    api(projects.core.database)
    api(projects.core.datastore)
    api(projects.core.network)
    implementation(projects.core.common)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // DAO 用手写 fake 实现来测，不引 Robolectric；但 fake 要实现 Room 注解过的
    // 接口，注解本身在 :core:database 里是 implementation 依赖，编译期看不到
    testImplementation(libs.androidx.room.runtime)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
