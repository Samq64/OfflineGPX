package dev.samuelq.gpx.data.track

/**
 * The route palettes as ARGB, indexed by [RouteColor.ordinal].
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
    ).map(Long::toInt).also { check(it.size == RouteColor.entries.size) }

    val DARK: List<Int> = listOf(
        0xFFE66E6F,
        0xFFDFD308,
        0xFF2D9A0E,
        0xFF16E1D6,
        0xFF73A3FC,
        0xFFB853AC,
    ).map(Long::toInt).also { check(it.size == RouteColor.entries.size) }
}

/**
 * A track's colour, one per hue Garmin's `DisplayColor` names. Stored and exported by name, so
 * entries may be reordered or added but never renamed. In wheel order, the order they're assigned.
 */
enum class RouteColor {
    Red,
    Yellow,
    Green,
    Cyan,
    Blue,
    Magenta,
    ;

    companion object {
        /** Wraps, so any index picks one. */
        fun at(slot: Int): RouteColor = entries[slot.mod(entries.size)]

        /** Either shade of a hue's name; null for the greys, Transparent or anything unknown. */
        fun ofGarmin(name: String): RouteColor? {
            val hue = name.trim().removePrefix("Dark")
            return entries.firstOrNull { it.name == hue }
        }
    }
}
