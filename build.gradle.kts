plugins {
    alias(libs.plugins.android.application) apply false
    // No org.jetbrains.kotlin.android: AGP 9 registers `kotlin` itself. Declaring Compose
    // here also pins the Kotlin Gradle plugin to the catalog's version.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
