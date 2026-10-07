plugins {
    alias(libs.plugins.android.application) apply false
    // No org.jetbrains.kotlin.android: AGP 9 registers `kotlin` itself. Declaring Compose
    // here also pins the Kotlin Gradle plugin to the catalog's version.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
}

// Style per .editorconfig; `check` runs it, `ktlintFormat` fixes what it can.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.8.0")
    }
}
