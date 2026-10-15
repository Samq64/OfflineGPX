package dev.samuelq.gpx.ui

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.samuelq.gpx.MainActivity
import dev.samuelq.gpx.PAUSE_SECONDS
import dev.samuelq.gpx.R
import dev.samuelq.gpx.container
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.track.TrackFiles
import dev.samuelq.gpx.sampleGpx
import dev.samuelq.gpx.targetContext
import dev.samuelq.gpx.ui.format.spokenDuration
import dev.samuelq.gpx.waitFor
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    /** The library is newest first, so a test track can sit below the fold of a full device. */
    private fun scrollLibraryTo(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) {
            runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher) }.isSuccess
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

    /** Into Downloads through MediaStore, so it arrives as another app's content would. */
    private fun download(name: String, bytes: ByteArray): Uri {
        val resolver = targetContext.contentResolver
        val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name) }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        downloads += uri
        return uri
    }

    private val downloads = mutableListOf<Uri>()

    @After
    fun deleteDownloads() {
        downloads.forEach { targetContext.contentResolver.delete(it, null, null) }
    }

    /** The test map, padded back to the size its header declares, as in OfflineMapTest. */
    private fun mapBytes(): ByteArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("andorra-fragment.map").use { it.readBytes() }
        return bytes.copyOf(ByteBuffer.wrap(bytes, 28, 8).long.toInt())
    }

    private fun tracks() = runBlocking { container.trackRepository.tracks.first() }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun viewIntentImportsTheTrack() {
        val name = "Shared ${System.nanoTime()}"
        val uri = download("incoming-${System.nanoTime()}.gpx", sampleGpx(name = name).toByteArray())

        launch(
            Intent(Intent.ACTION_VIEW, uri, targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )

        // The rule's own wait: effects run on its dispatcher, which a blocking poll would stall.
        compose.waitUntil(10_000) { tracks().any { it.trackName == name } }
        waitForText(name)
        waitForText(targetContext.resources.getQuantityString(R.plurals.library_imported_all, 1, 1))

        // In the library too.
        compose.onNodeWithContentDescription(string(R.string.library_title)).performClick()
        scrollLibraryTo(hasText(name))
    }

    /** Drawn on a canvas, so it's checked through what a screen reader hears. */
    @Test
    fun aPauseIsSpokenAsAGapInBothCharts() {
        val name = "Paused ${System.nanoTime()}"
        val uri = download("paused-${System.nanoTime()}.gpx", sampleGpx(name = name, pauseAt = 10).toByteArray())
        launch(
            Intent(Intent.ACTION_VIEW, uri, targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        compose.waitUntil(10_000) { tracks().any { it.trackName == name } }
        waitForText(name)

        val resources = targetContext.resources
        val gap = resources.getQuantityString(
            R.plurals.chart_gaps,
            1,
            1,
            resources.spokenDuration(PAUSE_SECONDS.toDouble()),
        )
        val spoken = hasContentDescription(gap, substring = true)
        // On a distance axis a pause has no width.
        assertTrue(compose.onAllNodes(spoken).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(string(R.string.axis_time)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(spoken).fetchSemanticsNodes().size == 2 }
    }

    /** As CoMaps shares even one file; a map among them is told apart by its first bytes. */
    @Test
    fun sendMultipleAddsTracksAndMaps() {
        val name = "Multi ${System.nanoTime()}"
        val uris = arrayListOf(
            download("multi-${System.nanoTime()}.gpx", sampleGpx(name = name).toByteArray()),
            download("andorra-${System.nanoTime()}.map", mapBytes()),
        )
        val mapsBefore = container.mapStore.maps.value.map { it.file }.toSet()

        launch(
            Intent(Intent.ACTION_SEND_MULTIPLE, null, targetContext, MainActivity::class.java)
                .setType("application/octet-stream")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )

        val imported = targetContext.resources.getQuantityString(R.plurals.incoming_imported_all, 2, 2)
        waitForText(imported)
        assertTrue(tracks().any { it.trackName == name })
        val added = container.mapStore.maps.value.filter { it.file !in mapsBefore }
        assertEquals(1, added.size)

        // Undo takes both back out.
        compose.onNodeWithText(string(R.string.action_undo)).performClick()
        compose.waitUntil(10_000) {
            tracks().none { it.trackName == name } &&
                container.mapStore.maps.value.none { it.file in added.map(OfflineMap::file) }
        }
    }

    @Test
    fun aFileThatIsNeitherGpxNorAMapSaysSo() {
        val before = tracks().size
        val uri = download("notes-${System.nanoTime()}.gpx", "not xml".toByteArray())
        launch(
            Intent(Intent.ACTION_SEND, null, targetContext, MainActivity::class.java)
                .setType("application/gpx+xml")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        waitForText(string(R.string.incoming_unsupported))
        assertEquals(before, tracks().size)
    }

    /** An explicit intent skips the filters; our own files mustn't be read on another app's say. */
    @Test
    fun ownFilesAreIgnored() {
        val file = File(TrackFiles.dir(targetContext), "own-${System.nanoTime()}.gpx")
        file.writeText(sampleGpx(name = "Own ${System.nanoTime()}"))
        val before = tracks().size
        try {
            listOf(
                FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file),
                Uri.fromFile(file),
            ).forEach { uri ->
                launch(Intent(Intent.ACTION_VIEW, uri, targetContext, MainActivity::class.java))
                compose.waitForIdle()
                Thread.sleep(1_000)
                assertEquals(before, tracks().size)
                scenario?.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun undoSurvivesRecreation() {
        val name = "Undone ${System.nanoTime()}"
        val file = File(targetContext.cacheDir, "undo-${System.nanoTime()}.gpx").apply {
            writeText(sampleGpx(name = name))
        }
        val repository = container.trackRepository
        val id = kotlinx.coroutines.runBlocking { repository.import(Uri.fromFile(file)).getOrThrow() }
        file.delete()
        launch()

        compose.onNodeWithContentDescription(string(R.string.library_title)).performClick()
        val manage = targetContext.getString(R.string.action_manage_named, name)
        scrollLibraryTo(hasContentDescription(manage))
        compose.onNodeWithContentDescription(manage).performClick()
        compose.onNodeWithText(string(R.string.action_delete)).performClick()
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
