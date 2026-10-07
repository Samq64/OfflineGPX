plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// A JVM module, so the compiler rather than convention keeps Android out of the analysis.
// Bytecode 17 to match :app, without requiring a JDK 17 toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // What :app may use is declared, not whatever happened to be left public.
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}
