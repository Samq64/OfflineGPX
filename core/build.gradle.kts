plugins {
    alias(libs.plugins.kotlin.jvm)
}

// A JVM module, so the compiler rather than convention keeps Android out of the analysis.
// Bytecode 17 to match :app, without requiring a JDK 17 toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}
