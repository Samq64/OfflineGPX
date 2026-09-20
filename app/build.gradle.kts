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
 * INTERNET without a line changing in this repo. MapLibre declares three network
 * permissions of its own and the app manifest strips all three; this is what notices when
 * a fourth appears, or when one of the removals is deleted by accident.
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
                    appendLine("declared one of these and app/src/main/AndroidManifest.xml has no")
                    appendLine("matching tools:node=\"remove\" for it.")
                    appendLine("Either add the removal, or drop the dependency.")
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

    testOptions {
        unitTests {
            // android.jar is stubbed for unit tests, so any framework call throws
            // "not mocked" by default. The tests here are pure Kotlin plus kxml2, but
            // this keeps an incidental android.util.Log call from failing a green test.
            isReturnDefaultValues = true
        }
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // Renders the basemap from a .pmtiles archive the user supplied. It can only ever read
    // local files here: every network permission it declares is stripped from the merged
    // manifest, and the build fails if one survives.
    implementation(libs.maplibre.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kxml2)
}
