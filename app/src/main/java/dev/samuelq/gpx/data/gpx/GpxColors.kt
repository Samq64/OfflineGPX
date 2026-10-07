package dev.samuelq.gpx.data.gpx

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The track colour extensions read and written: Topografix's gpx_style holds any RGB, Garmin's
 * one of 16 names, and OsmAnd's is read only. Colours are 0xRRGGBB.
 */
internal object GpxColors {
    const val STYLE_NAMESPACE = "http://www.topografix.com/GPX/gpx_style/0/2"
    const val GARMIN_NAMESPACE = "http://www.garmin.com/xmlschemas/GpxExtensions/v3"

    const val STYLE_LINE = "line"
    const val STYLE_COLOR = "color"
    const val GARMIN_TRACK = "TrackExtension"
    const val GARMIN_COLOR = "DisplayColor"
    const val OSMAND_COLOR = "color"

    /** Any gpx_style version: `color` is the same in each. */
    fun isStyle(namespace: String?): Boolean = namespace?.startsWith(STYLE_BASE) == true

    /** OsmAnd has used both schemes. */
    fun isOsmAnd(namespace: String?): Boolean = namespace?.removeSuffix("/")?.endsWith("://osmand.net") == true

    /** A `<trk><extensions>` child that sets the line colour, which a new colour replaces whole. */
    fun isColorElement(namespace: String?, name: String): Boolean = (isStyle(namespace) && name == STYLE_LINE) ||
        (namespace == GARMIN_NAMESPACE && name == GARMIN_TRACK) ||
        (isOsmAnd(namespace) && name == OSMAND_COLOR)

    /** `RRGGBB` or OsmAnd's `AARRGGBB`, `#` optional; alpha is dropped. */
    fun parseHex(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        return hex.toLongOrNull(16)?.toInt()?.and(0xFFFFFF)
    }

    fun hex(rgb: Int): String = "%06X".format(rgb and 0xFFFFFF)

    /** Null for Transparent or an unknown name. */
    fun garminRgb(name: String): Int? = GARMIN[name.trim()]

    /**
     * The nearest of Garmin's coloured names by hue, then lightness: by distance alone a blue
     * would go dark cyan. Its greys would read as no colour.
     */
    fun garminName(rgb: Int): String = GARMIN.filterKeys { it !in GARMIN_GREYS }.entries.minWith(
        // Rounded so each dark and light pair, one hue, ties.
        compareBy<Map.Entry<String, Int>>({ Oklab.hueDistance(it.value, rgb).roundToInt() })
            .thenBy { abs(Oklab.of(it.value)[0] - Oklab.of(rgb)[0]) },
    ).key

    private const val STYLE_BASE = "http://www.topografix.com/GPX/gpx_style/"

    /** The schema names no values; readers take them as the VGA palette. */
    private val GARMIN = mapOf(
        "Black" to 0x000000,
        "DarkRed" to 0x800000,
        "DarkGreen" to 0x008000,
        "DarkYellow" to 0x808000,
        "DarkBlue" to 0x000080,
        "DarkMagenta" to 0x800080,
        "DarkCyan" to 0x008080,
        "LightGray" to 0xC0C0C0,
        "DarkGray" to 0x808080,
        "Red" to 0xFF0000,
        "Green" to 0x00FF00,
        "Yellow" to 0xFFFF00,
        "Blue" to 0x0000FF,
        "Magenta" to 0xFF00FF,
        "Cyan" to 0x00FFFF,
        "White" to 0xFFFFFF,
    )

    private val GARMIN_GREYS = setOf("Black", "LightGray", "DarkGray", "White")
}

/** Perceptual colour space, for picking a nearest colour. */
internal object Oklab {
    fun of(rgb: Int): DoubleArray {
        val r = linear(rgb shr 16 and 0xFF)
        val g = linear(rgb shr 8 and 0xFF)
        val b = linear(rgb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    fun chroma(rgb: Int): Double = of(rgb).let { sqrt(it[1] * it[1] + it[2] * it[2]) }

    /** In degrees, round the shorter way. */
    fun hueDistance(a: Int, b: Int): Double {
        val d = abs(hue(a) - hue(b)) % 360
        return min(d, 360 - d)
    }

    private fun hue(rgb: Int): Double = of(rgb).let { Math.toDegrees(atan2(it[2], it[1])) }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
