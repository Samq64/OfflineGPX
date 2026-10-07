package dev.samuelq.gpx.ui.library

import dev.samuelq.gpx.core.model.GeoBounds
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.db.TrackSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryListingTest {

    private fun track(id: Long, category: String?) = TrackEntity(
        id = id,
        location = "tracks/$id.gpx",
        displayName = "$id.gpx",
        trackName = null,
        startedAtEpochMillis = null,
        lastOpenedAtEpochMillis = 0,
        summary = TrackSummary(2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        category = category,
    )

    private val view = GeoBounds(southLatitude = 47.0, westLongitude = 8.0, northLatitude = 48.0, eastLongitude = 9.0)

    @Test
    fun `a track overlaps the area if any of its box is inside`() {
        assertTrue(GeoBounds(47.2, 8.2, 47.4, 8.4).overlaps(view))
        assertTrue(GeoBounds(46.0, 7.0, 47.5, 8.5).overlaps(view), "across the corner")
        assertTrue(GeoBounds(40.0, 0.0, 50.0, 20.0).overlaps(view), "around it")
        assertFalse(GeoBounds(49.0, 8.2, 49.5, 8.4).overlaps(view), "north")
        assertFalse(GeoBounds(47.2, 9.5, 47.4, 9.8).overlaps(view), "east")
    }

    @Test
    fun `an area across the antimeridian finds tracks on either side`() {
        val fiji = GeoBounds(southLatitude = -20.0, westLongitude = 170.0, northLatitude = -10.0, eastLongitude = 190.0)
        assertTrue(GeoBounds(-18.0, 178.0, -17.0, 179.0).overlaps(fiji))
        assertTrue(GeoBounds(-18.0, -179.5, -17.0, -179.0).overlaps(fiji))
        assertFalse(GeoBounds(-18.0, -160.0, -17.0, -150.0).overlaps(fiji))
        // Zoomed out past a world's width, every longitude is in view.
        assertTrue(GeoBounds(-18.0, -160.0, -17.0, -150.0).overlaps(GeoBounds(-80.0, -300.0, 80.0, 300.0)))
    }

    @Test
    fun `categories group alphabetically, uncategorised last, keeping the sort within`() {
        val sorted =
            listOf(
                track(1, null),
                track(2, "Rides"),
                track(3, "Hikes"),
                track(4, null),
                track(5, "Rides"),
                track(6, "Hikes"),
            )
        val grouped = sorted.groupedByCategory()
        assertEquals(listOf(3L, 6L, 2L, 5L, 1L, 4L), grouped.map(TrackEntity::id))

        val sections = grouped.sections()
        assertEquals(listOf("Hikes", "Rides", null), sections.map(Section::category))
        assertEquals(
            listOf(listOf(3L, 6L), listOf(2L, 5L), listOf(1L, 4L)),
            sections.map {
                it.tracks.map(TrackEntity::id)
            },
        )
        assertTrue(emptyList<TrackEntity>().sections().isEmpty())
    }

    @Test
    fun `categories differing only in case are one section`() {
        val grouped = listOf(track(1, "hiking"), track(2, "Rides"), track(3, "Hiking")).groupedByCategory()
        assertEquals(listOf(1L, 3L, 2L), grouped.map(TrackEntity::id))
        assertEquals(listOf("hiking", "Rides"), grouped.sections().map(Section::category))
    }
}
