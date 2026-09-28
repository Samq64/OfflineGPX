import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Fails the build if the merged manifest asks for the network.
 *
 * The app's one structural promise is that it cannot phone home, and that promise is kept
 * by an absence - which is exactly the kind of thing that goes missing quietly. The
 * manifest merger folds in every dependency's permissions, so a library bump can add
 * INTERNET without a line changing in this repo. No current dependency declares one -
 * VTM ships as jars with no manifest at all - so this now guards against the next
 * dependency rather than the present ones, which is the point: it fires before a review does.
 *
 * Wired as a *transform* of the merged manifest rather than hung off `assemble`: a
 * transform is the only way to be unskippable. Every build that produces an APK produces
 * this artifact, and producing it now means passing through here.
 */
abstract class CheckNoNetworkPermissions : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    /** The same manifest, unchanged. A transform has to hand its artifact onwards. */
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

android {
    namespace = "dev.samuelq.gpx"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.samuelq.gpx"
        minSdk = libs.versions.minSdk.get().toInt()
        // AGP 9 defaults targetSdk to compileSdk; set explicitly so a compileSdk bump
        // can never silently change runtime behaviour.
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9's built-in-Kotlin DSL. The old `android.kotlinOptions` spelling was removed.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // The app has no network access; don't ship the dependency-metadata blob Play would
    // otherwise embed in the artifact.
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

// Exports the schema as JSON so a future migration can be diffed against it and tested.
// Checked in; Room warns on every build without it. This is the plugin-free spelling -
// the `room { schemaDirectory(...) }` block needs the separate androidx.room plugin.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// vtm-android's SVG symbol decoder. The generated render theme has no SVG symbols.
configurations.configureEach {
    exclude(group = "com.caverock", module = "androidsvg")
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)

    // Renders the basemap from a .map file the user supplied. Ships as plain jars, so it
    // declares no permissions of its own and has no manifest to merge.
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
