import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    jacoco
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
                    appendLine("declared one of these. Drop the dependency or, only if it works")
                    appendLine("offline, strip the permission with tools:node=\"remove\" in")
                    appendLine("AndroidManifest.xml and a comment saying why.")
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
        // The hash alone, with -dirty when tracked files have uncommitted changes.
        commandLine("git", "describe", "--always", "--dirty", "--exclude=*")
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
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

    // android.jar's stubs throw; defaults let code that logs on failure run in JVM tests.
    testOptions.unitTests.isReturnDefaultValues = true

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    // Room's exported schemas, for MigrationTestHelper.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

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
    implementation(libs.androidx.core.location.altitude)
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

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kxml2)

    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Hosts createComposeRule's activity; debug only, so release never merges it.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// JVM test coverage over :app and :core. Generated code is left out.
val coverageExcludes = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    "**/*_Impl*", "**/ComposableSingletons*", "**/*\$serializer*",
)
val coreBuild = project(":core").layout.buildDirectory
val coverageClasses = files(
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
    coreBuild.dir("classes/kotlin/main"),
).asFileTree.matching { exclude(coverageExcludes) }
val coverageData = files(
    layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
    coreBuild.file("jacoco/test.exec"),
)

val coverage = tasks.register<JacocoReport>("coverage") {
    group = "verification"
    description = "JVM test coverage of :app and :core, as HTML and XML."
    dependsOn("testDebugUnitTest", ":core:test")
    executionData.from(coverageData)
    classDirectories.from(coverageClasses)
    sourceDirectories.from("src/main/java", project(":core").layout.projectDirectory.dir("src/main/kotlin"))
    reports {
        html.required = true
        xml.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/coverage/html")
        xml.outputLocation = layout.buildDirectory.file("reports/coverage/coverage.xml")
    }
}

/** Per-file line and branch minimums, for code where a bug could lose a track. UI glue isn't held. */
val coverageMinimums = mapOf(
    "dev/samuelq/gpx/core/model/Track.kt" to (1.00 to 0.94),
    "dev/samuelq/gpx/core/model/GeoBounds.kt" to (1.00 to 0.92),
    "dev/samuelq/gpx/core/analysis/TrackAnalyzer.kt" to (1.00 to 0.98),
    "dev/samuelq/gpx/core/analysis/TrackProfile.kt" to (1.00 to 1.00),
    "dev/samuelq/gpx/core/analysis/FixFilter.kt" to (1.00 to 0.95),
    "dev/samuelq/gpx/core/analysis/SpeedWindow.kt" to (1.00 to 1.00),
    "dev/samuelq/gpx/data/gpx/GpxParser.kt" to (0.99 to 0.96),
    "dev/samuelq/gpx/data/gpx/GpxWriter.kt" to (0.98 to 1.00),
    "dev/samuelq/gpx/data/gpx/GpxTrimmer.kt" to (0.99 to 0.89),
    "dev/samuelq/gpx/data/record/RecordingWal.kt" to (1.00 to 0.97),
    "dev/samuelq/gpx/data/record/RecordingSession.kt" to (1.00 to 0.95),
    "dev/samuelq/gpx/data/track/TrackCache.kt" to (1.00 to 0.96),
    "dev/samuelq/gpx/data/map/MapTileIndex.kt" to (1.00 to 1.00),
    "dev/samuelq/gpx/data/map/MapOverlap.kt" to (1.00 to 1.00),
    "dev/samuelq/gpx/ui/chart/ChartMath.kt" to (1.00 to 0.89),
    "dev/samuelq/gpx/ui/format/Formatters.kt" to (1.00 to 0.97),
)

val coverageVerification = tasks.register<JacocoCoverageVerification>("coverageVerification") {
    group = "verification"
    description = "Fails if the data-safety logic drops below its coverage minimums."
    dependsOn(coverage)
    executionData.from(coverageData)
    classDirectories.from(coverageClasses)
    sourceDirectories.from("src/main/java", project(":core").layout.projectDirectory.dir("src/main/kotlin"))
    violationRules {
        coverageMinimums.forEach { (file, minimums) ->
            rule {
                element = "SOURCEFILE"
                includes = listOf(file)
                limit { counter = "LINE"; minimum = minimums.first.toBigDecimal() }
                limit { counter = "BRANCH"; minimum = minimums.second.toBigDecimal() }
            }
        }
    }
}
tasks.named("check") { dependsOn(coverageVerification) }
