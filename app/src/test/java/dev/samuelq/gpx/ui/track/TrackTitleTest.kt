package dev.samuelq.gpx.ui.track

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackTitleTest {

    private val saved = Locale.getDefault()

    @BeforeTest
    fun setUp() = Locale.setDefault(Locale.UK)

    @AfterTest
    fun tearDown() = Locale.setDefault(saved)

    @Test
    fun `a name wins over the file`() {
        assertEquals("Commute", trackTitle("Commute", "2024-05-04T180000.gpx"))
        assertEquals("Commute", editableTrackName("Commute", "ride.gpx"))
        assertEquals("Commute.gpx", exportFileName(" Commute", "ride.gpx").trim())
    }

    @Test
    fun `an unnamed recording is titled by its start, and exported under its stamp`() {
        assertEquals("4 May 2024, 18:00", trackTitle(null, "2024-05-04T180000.gpx"))
        assertEquals("4 May 2024, 18:00", trackTitle(" ", "2024-05-04T180000 (2).gpx"))
        assertEquals("4 May 2024, 18:00", editableTrackName(null, "2024-05-04T180000.gpx"))
        assertEquals("2024-05-04T180000.gpx", exportFileName(null, "2024-05-04T180000.gpx"))
    }

    @Test
    fun `an unnamed import keeps its filename`() {
        assertEquals("ride.gpx", trackTitle(null, "ride.gpx"))
        assertEquals("ride", editableTrackName(null, "ride.gpx"))
        assertEquals("ride.gpx", exportFileName(null, "ride.gpx"))
        // Not a real time.
        assertEquals("2024-13-04T180000.gpx", trackTitle(null, "2024-13-04T180000.gpx"))
    }
}
