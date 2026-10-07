package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.gpx.Oklab

/**
 * The route palettes as ARGB, here so a file's colour can be matched to a slot.
 *
 * Picked by search for the hue each is named: at least 3:1 against map land (2:1 against water
 * and vegetation), and apart from each other and the recording red by 25 CIEDE2000 (12 under
 * simulated CVD) in dark mode. Light mode gets 18 (7): a yellow dark enough for white land
 * leaves orange little room beside the red. Slots keep the hue of the colour stored there
 * before: blue became cyan, plum purple, violet blue, grey orange. Lower slots are assigned first.
 */
object RouteColors {
    val LIGHT: List<Int> = listOf(
        0xFF2A95B9,
        0xFF9765E9,
        0xFF098745,
        0xFFAA861B,
        0xFFE45191,
        0xFF3851A3,
        0xFFB15D08,
    ).map(Long::toInt).also { check(it.size == TrackEntity.PALETTE_SIZE) }

    val DARK: List<Int> = listOf(
        0xFF20EDFF,
        0xFF9321D4,
        0xFF28C67A,
        0xFFFCED54,
        0xFFFE499B,
        0xFF627FFE,
        0xFFEC9424,
    ).map(Long::toInt).also { check(it.size == TrackEntity.PALETTE_SIZE) }

    /** What a file records for [slot]: the light value, as other apps mostly draw on light maps. */
    fun rgb(slot: Int): Int = LIGHT[slot.mod(LIGHT.size)] and 0xFFFFFF

    /**
     * The slot nearest [rgb]'s hue in either theme, since slots are told apart by hue; by
     * distance a dark cyan would go green. Null for a grey, which has none.
     */
    fun slotOf(rgb: Int): Int? {
        if (Oklab.chroma(rgb) < MIN_CHROMA) return null
        return LIGHT.indices.minBy { minOf(Oklab.hueDistance(LIGHT[it], rgb), Oklab.hueDistance(DARK[it], rgb)) }
    }

    /** Below this a colour has too little hue to pick a slot by. */
    private const val MIN_CHROMA = 0.04
}
