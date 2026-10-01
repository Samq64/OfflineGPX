package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.R
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultNameTest {

    // 2026-10-03 is a Saturday.
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 30, 0, 0, ZoneOffset.UTC)

    @Test
    fun `a ride is named for its weekday and part of day`() {
        assertEquals(R.string.track_default_morning to "Saturday", defaultNameParts(at(3, 9), Locale.UK))
        assertEquals(R.string.track_default_afternoon to "Saturday", defaultNameParts(at(3, 13), Locale.UK))
        assertEquals(R.string.track_default_evening to "Saturday", defaultNameParts(at(3, 18), Locale.UK))
        assertEquals(R.string.track_default_night to "Saturday", defaultNameParts(at(3, 22), Locale.UK))
    }

    @Test
    fun `the small hours belong to the night before`() {
        assertEquals(R.string.track_default_night to "Saturday", defaultNameParts(at(4, 1), Locale.UK))
        assertEquals(R.string.track_default_morning to "Sunday", defaultNameParts(at(4, 5), Locale.UK))
    }

    @Test
    fun `the weekday follows the locale`() {
        assertEquals("samedi", defaultNameParts(at(3, 9), Locale.FRANCE).second)
    }
}
