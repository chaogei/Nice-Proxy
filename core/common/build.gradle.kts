plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.javaTarget.get().toInt())
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
}
