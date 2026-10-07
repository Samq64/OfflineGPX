package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.db.TrackSummary
import dev.samuelq.gpx.ui.track.trackTitle
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun row(location: String, trackName: String? = null, startedAt: Long? = started) = TrackEntity(
        location = location,
        displayName = location.substringAfterLast('/'),
        trackName = trackName,
        startedAtEpochMillis = startedAt,
        lastOpenedAtEpochMillis = 0,
        summary = TrackSummary(2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    )

    @Test
    fun `a name wins`() {
        assertEquals("Commute", row("recordings/2024-05-04T180000.gpx", "Commute").title)
        assertEquals("Commute", row("imports/ride.gpx", " Commute ").editableName.trim())
        assertEquals("Commute.gpx", exportFileName("Commute", "ride.gpx"))
    }

    @Test
    fun `an unnamed recording is titled by its start, whatever its file is called`() {
        for (file in listOf(
            "2024-05-04T180000.gpx",
            "2024-05-04T180000 (2).gpx",
            "2024-05-04T180000 (copy).gpx",
            "Renamed-1.gpx",
        )) {
            assertEquals("4 May 2024, 18:00", row("recordings/$file").title, file)
            assertEquals("4 May 2024, 18:00", row("recordings/$file", trackName = " ").titleStem, file)
            assertEquals("", row("recordings/$file", trackName = " ").editableName, file)
        }
        // Without a start, the filename's stamp stands in.
        assertEquals("4 May 2024, 18:00", row("recordings/2024-05-04T180000 (2).gpx", startedAt = null).title)
        // Exported under the stamp, which sorts.
        assertEquals("2024-05-04T180000.gpx", exportFileName(null, "2024-05-04T180000.gpx"))
    }

    @Test
    fun `an unnamed import keeps its filename`() {
        assertEquals("ride.gpx", row("imports/ride.gpx").title)
        assertEquals("ride", row("imports/ride.gpx").editableName)
        assertEquals("ride.gpx", exportFileName(null, "ride.gpx"))
    }

    @Test
    fun `before its row, a recording is titled off its stamp`() {
        assertEquals("4 May 2024, 18:00", trackTitle(null, "2024-05-04T180000.gpx"))
        assertEquals("2024-13-04T180000.gpx", trackTitle(null, "2024-13-04T180000.gpx"))
    }
}
