plugins {
    alias(libs.plugins.android.application) apply false
    // Applying org.jetbrains.kotlin.android here would fail: AGP 9 registers the `kotlin`
    // extension itself. The Compose compiler plugin is a separate Kotlin compiler plugin
    // and is still applied normally; declaring it here also pins the Kotlin Gradle plugin
    // on the buildscript classpath to the catalog's Kotlin version.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
