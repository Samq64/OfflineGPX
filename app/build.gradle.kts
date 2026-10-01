import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Fails the build if the merged manifest asks for the network: the merger folds in every
 * dependency's permissions, so a library bump can add INTERNET silently.
 *
 * A transform of the merged manifest rather than an `assemble` hook, so it can't be skipped.
 */
abstract class CheckNoNetworkPermissions : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    /** Passed through unchanged; a transform must produce its artifact. */
    @get:OutputFile
    abstract val checkedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile.readText()
        val declared = BANNED.filter { manifest.contains("\"$it\"") }
        if (declared.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The merged manifest grants network access:")
                    declared.forEach { appendLine("    $it") }
                    appendLine()
                    appendLine("This app is offline by construction. A dependency most likely")
                    appendLine("declared one of these. Drop the dependency, or strip the")
                    appendLine("permission with tools:node=\"remove\" in AndroidManifest.xml.")
                }
            )
        }
        checkedManifest.get().asFile.writeText(manifest)
    }

    private companion object {
        val BANNED = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
        )
    }
}

/** The built commit, shown beside the version. Empty outside a git checkout. */
val gitHash: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("")

android {
    namespace = "dev.samuelq.gpx"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.samuelq.gpx"
        minSdk = libs.versions.minSdk.get().toInt()
        // AGP 9 defaults this to compileSdk; pinned so a compileSdk bump can't change behaviour.
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // For the version and commit shown in Settings.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9's built-in-Kotlin DSL; `kotlinOptions` is gone.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Skip the dependency-metadata blob Play would embed.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

}

androidComponents {
    onVariants { variant ->
        val check = tasks.register<CheckNoNetworkPermissions>(
            "check${variant.name.replaceFirstChar(Char::uppercase)}NoNetworkPermissions"
        )
        variant.artifacts
            .use(check)
            .wiredWithFiles(
                CheckNoNetworkPermissions::mergedManifest,
                CheckNoNetworkPermissions::checkedManifest,
            )
            .toTransform(SingleArtifact.MERGED_MANIFEST)
    }
}

// Checked-in schema for future migrations. Plugin-free spelling of `room { schemaDirectory }`.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// vtm-android's SVG symbol decoder. The generated render theme has no SVG symbols.
configurations.configureEach {
    exclude(group = "com.caverock", module = "androidsvg")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)

    // Basemap renderer. Plain jars, so no manifest or permissions to merge.
    implementation(libs.vtm)
    implementation(libs.vtm.android)
    implementation(libs.vtm.jts)
    // The tessellator, one jar per ABI; AGP packages the .so inside each.
    listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").forEach { abi ->
        runtimeOnly(variantOf(libs.vtm.android) { classifier("natives-$abi") })
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kxml2)
}
