package dev.samuelq.gpx.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.samuelq.gpx.MainActivity
import dev.samuelq.gpx.R
import dev.samuelq.gpx.container
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.track.TrackFiles
import dev.samuelq.gpx.sampleGpx
import dev.samuelq.gpx.targetContext
import dev.samuelq.gpx.waitFor
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

/** Whole-app flows, found by text and semantics. */
@RunWith(AndroidJUnit4::class)
class AppFlowsTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun string(id: Int) = targetContext.getString(id)

    private fun launch(intent: Intent? = null) {
        scenario = if (intent == null) {
            ActivityScenario.launch(MainActivity::class.java)
        } else {
            ActivityScenario.launch(intent)
        }
    }

    private fun deleteAllTracks() {
        val repository = container.trackRepository
        val ids = kotlinx.coroutines.runBlocking { repository.tracks.first() }.map { it.id }
        repository.deleteLater(ids)
        repository.commitDelete(ids)
        waitFor(message = "library emptied") { repository.tracks.first().isEmpty() }
    }

    @Test
    fun emptyLibrary() {
        deleteAllTracks()
        launch()
        compose.onNodeWithContentDescription(string(R.string.library_title)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(string(R.string.library_empty_title))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(string(R.string.library_import)).assertExists()
    }

    @Test
    fun viewIntentImportsTheTrack() {
        val name = "Shared ${System.nanoTime()}"
        // Under a FileProvider root, as another app's content URI would arrive.
        val file = File(TrackFiles.importsDir(targetContext), "incoming-${System.nanoTime()}.gpx")
        file.writeText(sampleGpx(name = name))
        val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)

        launch(
            Intent(Intent.ACTION_VIEW, uri, targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )

        // The rule's own wait: effects run on its dispatcher, which a blocking poll would stall.
        compose.waitUntil(10_000) {
            kotlinx.coroutines.runBlocking { container.trackRepository.tracks.first() }.any { it.trackName == name }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(name), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        file.delete()

        // In the library too.
        compose.onNodeWithContentDescription(string(R.string.library_title)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(name), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun undoSurvivesRecreation() {
        val name = "Undone ${System.nanoTime()}"
        val file = File(targetContext.cacheDir, "undo-${System.nanoTime()}.gpx").apply { writeText(sampleGpx(name = name)) }
        val repository = container.trackRepository
        val id = kotlinx.coroutines.runBlocking { repository.import(Uri.fromFile(file)).getOrThrow() }
        file.delete()
        launch()

        compose.onNodeWithContentDescription(string(R.string.library_title)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(targetContext.getString(R.string.track_manage_named, name)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(targetContext.getString(R.string.track_manage_named, name)).performClick()
        compose.onNodeWithText(string(R.string.library_delete)).performClick()
        // As a locale change does; the undo mustn't commit.
        scenario!!.recreate()
        compose.onNodeWithText(string(R.string.action_undo)).performClick()

        compose.waitUntil(5_000) { kotlinx.coroutines.runBlocking { repository.tracks.first() }.any { it.id == id } }
        repository.deleteLater(listOf(id))
        repository.commitDelete(listOf(id))
    }

    @Test
    fun unitsChoice() {
        val settings = container.settingsRepository
        settings.setUnits(UnitSystem.METRIC)
        launch()

        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        compose.onNodeWithText(string(R.string.settings_units_imperial)).performClick()
        assertEquals(UnitSystem.IMPERIAL, settings.settings.value.units)
        compose.onNodeWithText(string(R.string.settings_units_metric)).performClick()
        assertEquals(UnitSystem.METRIC, settings.settings.value.units)

        // Back to the map, the root.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(string(R.string.settings_title)).assertExists()
    }
}
