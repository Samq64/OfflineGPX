package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.data.db.TrackEntity

/**
 * The route palettes as ARGB, one slot per hue Garmin's `DisplayColor` names, so a colour
 * leaves and returns as that name. In wheel order, which is also the order slots are assigned.
 *
 * From `tools/route_palette.py`: each within 8° of its primary's hue, at least 3:1 against map
 * land (2:1 against water and vegetation), and apart from each other by 25 CIEDE2000 (12 under
 * simulated CVD) in dark mode, 18 (7) in light. Dark red was then taken as deep as those allow.
 */
object RouteColors {
    val LIGHT: List<Int> = listOf(
        0xFFBA343E,
        0xFF978C0D,
        0xFF045503,
        0xFF07908D,
        0xFF201A97,
        0xFFC65CD5,
    ).map(Long::toInt).also { check(it.size == TrackEntity.PALETTE_SIZE) }

    val DARK: List<Int> = listOf(
        0xFFE66E6F,
        0xFFDFD308,
        0xFF2D9A0E,
        0xFF16E1D6,
        0xFF73A3FC,
        0xFFB853AC,
    ).map(Long::toInt).also { check(it.size == TrackEntity.PALETTE_SIZE) }

    /** What a file records for each slot. */
    private val GARMIN_NAMES = listOf("Red", "Yellow", "Green", "Cyan", "Blue", "Magenta")
        .also { check(it.size == TrackEntity.PALETTE_SIZE) }

    fun garminName(slot: Int): String = GARMIN_NAMES[slot.mod(GARMIN_NAMES.size)]

    /** Either shade of a hue's name; null for the greys, Transparent or anything unknown. */
    fun slotOf(garminName: String): Int? =
        GARMIN_NAMES.indexOf(garminName.trim().removePrefix("Dark")).takeIf { it >= 0 }
}
