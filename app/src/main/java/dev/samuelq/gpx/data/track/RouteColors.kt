package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.data.db.TrackEntity

/**
 * The route palettes as ARGB, one slot per hue Garmin's `DisplayColor` names, so a colour
 * leaves and returns as that name. In wheel order, which is also the order slots are assigned.
 *
 * From `tools/route_palette.py`: each within 8° of its pure sRGB hue, at least 2.5:1 against
 * map land (1.75:1 against water and vegetation), and apart from each other by 25 CIEDE2000
 * (12 under simulated CVD) in dark mode, 18 (7) in light. Dark mode's red was then taken as
 * deep as those allow.
 */
object RouteColors {
    val LIGHT: List<Int> = listOf(
        0xFFC43E46,
        0xFFA0951F,
        0xFF055F04,
        0xFF07A4A4,
        0xFF2773EE,
        0xFF79028D,
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
