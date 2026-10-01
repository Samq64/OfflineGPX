package dev.samuelq.gpx.ui.library

import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.settings.TrackSort
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackSortTest {

    private fun track(
        id: Long,
        name: String,
        started: Long? = null,
        opened: Long = 0,
        meters: Double = 0.0,
        trackName: String? = null,
    ) = TrackEntity(
        id = id,
        location = "tracks/$id.gpx",
        displayName = name,
        trackName = trackName,
        startedAtEpochMillis = started,
        lastOpenedAtEpochMillis = opened,
        pointCount = 2,
        distanceMeters = meters,
        totalSeconds = 0.0,
        movingSeconds = 0.0,
        averageSpeedMps = 0.0,
        ascentMeters = 0.0,
        descentMeters = 0.0,
        southLatitude = 0.0,
        westLongitude = 0.0,
        northLatitude = 0.0,
        eastLongitude = 0.0,
    )

    private fun List<TrackEntity>.ids(sort: TrackSort) =
        sortedFor(sort).map(TrackEntity::id)

    @Test
    fun `recent keeps the repository order`() {
        val tracks = listOf(track(2, "b"), track(1, "a"))
        assertEquals(listOf(2L, 1L), tracks.ids(TrackSort.RECENT))
    }

    @Test
    fun `date is newest first and falls back to when it was opened`() {
        val tracks = listOf(track(1, "a", started = 100), track(2, "b", opened = 300), track(3, "c", started = 200))
        assertEquals(listOf(2L, 3L, 1L), tracks.ids(TrackSort.DATE))
    }

    @Test
    fun `length is longest first and ties keep their order`() {
        val tracks = listOf(track(1, "a", meters = 5.0), track(2, "b", meters = 9.0), track(3, "c", meters = 5.0))
        assertEquals(listOf(2L, 1L, 3L), tracks.ids(TrackSort.LENGTH))
    }

    @Test
    fun `name sorts by title ignoring case`() {
        val tracks = listOf(track(1, "zeta.gpx"), track(2, "x.gpx", trackName = "alpha"), track(3, "Beta.gpx"))
        assertEquals(listOf(2L, 3L, 1L), tracks.ids(TrackSort.NAME))
    }
}
