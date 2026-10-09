package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.model.GeoBounds
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.db.TrackSummary
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackTitleTest {

    private val locale = Locale.getDefault()
    private val zone = TimeZone.getDefault()

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.UK)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    private val started = LocalDateTime.of(
        2024,
        5,
        4,
        18,
        0,
    ).atZone(ZoneId.of("America/Toronto")).toInstant().toEpochMilli()

    private fun row(trackName: String? = null) = TrackEntity(
        trackName = trackName,
        lastOpenedAtEpochMillis = 0,
        summary = TrackSummary(started, 0.0, 0.0, GeoBounds(0.0, 0.0, 0.0, 0.0)),
    )

    @Test
    fun `a name wins`() {
        assertEquals("Commute", row("Commute").title)
        assertEquals("Commute", row(" Commute ").editableName.trim())
        assertEquals("Commute.gpx", row("Commute").exportFileName)
        assertEquals("ride.gpx", row("ride.gpx").exportFileName)
        assertEquals("Mon_Tue.gpx", row("Mon/Tue").exportFileName)
        assertFalse(row("Commute").isTitledByStart)
    }

    @Test
    fun `an unnamed track is titled by its start and exported under its stamp`() {
        for (unnamed in listOf(null, " ")) {
            assertEquals("4 May 2024, 18:00", row(unnamed).title)
            assertEquals("", row(unnamed).editableName)
            assertTrue(row(unnamed).isTitledByStart)
            // The stamp sorts, where the localised date wouldn't.
            assertEquals("2024-05-04T180000.gpx", row(unnamed).exportFileName)
        }
    }

    @Test
    fun `an import is named after its file without the extension`() {
        assertEquals("ride", "ride.gpx".withoutGpxSuffix())
        assertEquals("ride", "ride.GPX".withoutGpxSuffix())
        assertEquals("ride.xml", "ride.xml".withoutGpxSuffix())
    }
}
