package dev.samuelq.gpx.ui.library

import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.db.TrackSummary
import dev.samuelq.gpx.data.settings.TrackOrder
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
        summary = TrackSummary(2, meters, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    )

    private fun List<TrackEntity>.ids(sort: TrackSort) = sortedFor(TrackOrder(sort)).map(TrackEntity::id)

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

    @Test
    fun `turned round, a sort runs the other way, ties included`() {
        val tracks = listOf(track(1, "a", meters = 5.0), track(2, "b", meters = 9.0), track(3, "c", meters = 5.0))
        assertEquals(listOf(2L, 1L, 3L), tracks.sortedFor(TrackOrder(TrackSort.LENGTH)).map(TrackEntity::id))
        assertEquals(
            listOf(3L, 1L, 2L),
            tracks.sortedFor(TrackOrder(TrackSort.LENGTH, descending = false)).map(TrackEntity::id),
        )
    }
}
