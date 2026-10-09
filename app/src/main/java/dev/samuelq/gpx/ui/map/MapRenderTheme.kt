package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

/**
 * Basemap render theme, built at runtime so it can take the user's theme colours. Written in
 * the mapsforge dialect against raw OSM tags; with no expression language, zoom-varying widths
 * are nested per-band rules. Zooms are on 256 px tiles. The background is transparent so the
 * land overlay underneath shows only where the file has data.
 */
object MapRenderTheme {

    /**
     * @param textScale the user's font scale, which VTM's fixed label sizes don't follow.
     * @param contourLabels whether to print heights, which files store in metres.
     */
    fun xml(
        land: Color,
        label: Color,
        background: Color,
        textScale: Float = 1f,
        contourLabels: Boolean = true,
    ): String {
        // Derived colours move away from the background, not toward black, or roads vanish in dark mode.
        val dark = background.luminance() < DARK_THRESHOLD

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<rendertheme xmlns="http://mapsforge.org/renderTheme" version="6" """)
            // Transparent: the land underneath shows through only where the file has data. Outside is
            // what VTM clears the screen to.
            append("""map-background="#00000000" map-background-outside="${background.css()}">""")

            // Rules paint in document order, so this is the stacking order.
            sea(land, dark)
            vegetation(dark)
            contours(dark)
            water(dark)
            buildings(land, dark)
            roads(land, dark)
            labels(label, background, textScale, contourLabels, dark)

            append("</rendertheme>")
        }
    }

    // --- Ground ----------------------------------------------------------------------

    /**
     * The sea isn't a feature: coastal tiles get `sea`/`nosea` polygons and all-water tiles
     * `issea`. Undrawn, the ocean reads as land.
     */
    private fun StringBuilder.sea(land: Color, dark: Boolean) {
        area("natural", "issea|sea", waterBlue(dark))
        area("natural", "nosea", land.css())
    }

    /** One green for every vegetated or protected kind; the app has no reason to tell them apart. */
    private fun StringBuilder.vegetation(dark: Boolean) {
        val green = greenVegetation(dark)
        area("natural", "wood|scrub|heath|fell|grassland|scree", green)
        area(
            "landuse",
            "forest|farmland|farmyard|meadow|grass|orchard|recreation_ground|cemetery|greenfield",
            green,
        )
        area("leisure", "park|garden|golf_course|nature_reserve|pitch|playground|dog_park", green)
        area("boundary", "protected_area|national_park", green)
        area("tourism", "camp_site", green)
    }

    /**
     * pyhgtmap's tags, which OpenAndroMaps files carry; mapsforge.org files have no contours.
     * Faint and under the water, so they read as ground rather than as ways.
     */
    private fun StringBuilder.contours(dark: Boolean) {
        val stroke = contourBrown(dark, if (dark) 0x99 else 0x66)
        zoomedLine(
            selector = """<rule e="way" k="contour_ext" v="elevation_major">""",
            stroke = stroke,
            stops = listOf(12 to 0.4f, 15 to 0.9f, 18 to 1.4f),
        )
        zoomedLine(
            selector = """<rule e="way" k="contour_ext" v="elevation_medium|elevation_minor">""",
            stroke = stroke,
            stops = listOf(14 to 0.3f, 15 to 0.5f, 18 to 0.8f),
        )
    }

    /** Areas and lines separately: a river given to an area renderer fills its course into a blob. */
    private fun StringBuilder.water(dark: Boolean) {
        val blue = waterBlue(dark)
        append("""<rule e="way" k="natural" v="water" closed="yes"><area fill="$blue"/></rule>""")
        append("""<rule e="way" k="landuse" v="reservoir|basin" closed="yes"><area fill="$blue"/></rule>""")
        zoomedLine(
            selector = """<rule e="way" k="waterway" v="river|canal" closed="no">""",
            stroke = blue,
            stops = listOf(11 to 0.6f, 14 to 1.6f, 17 to 5f),
        )
        zoomedLine(
            selector = """<rule e="way" k="waterway" v="stream|drain" closed="no">""",
            stroke = blue,
            stops = listOf(14 to 0.5f, 17 to 2f),
        )
    }

    /** Kept visible as landmarks on country roads. From z15, where the files start carrying them. */
    private fun StringBuilder.buildings(land: Color, dark: Boolean) {
        append("""<rule e="way" k="building" v="*" closed="yes" zoom-min="15">""")
        append("""<area fill="${land.shifted(0.16f, dark).css()}"/></rule>""")
    }

    /** Quietest first, so a motorway is never buried under a service road. No aeroway or ferry. */
    private fun StringBuilder.roads(land: Color, dark: Boolean) {
        zoomedLine(
            selector = """<rule e="way" k="highway" v="residential|unclassified|living_street|service|pedestrian">""",
            stroke = land.shifted(0.30f, dark).css(),
            stops = MINOR_ROAD_STOPS,
        )
        zoomedLine(
            selector = """<rule e="way" k="highway" v="$MAJOR_ROADS">""",
            stroke = land.shifted(0.45f, dark).css(),
            stops = listOf(9 to 0.5f, 13 to 2f, 18 to 10f),
        )

        // Tighter dash than a path's so the two don't read alike. No subway: it's underground.
        zoomedLine(
            selector = """<rule e="way" k="railway" v="rail|light_rail|narrow_gauge">""",
            stroke = land.shifted(0.55f, dark).css(),
            stops = listOf(12 to 0.5f, 15 to 1f, 18 to 3f),
            dashes = "4,4",
        )

        // Dashed and narrower than any road so a footpath never outranks its street. Also quiet
        // because mapsforge drops `footway=sidewalk`, so town sidewalks draw here too.
        zoomedLine(
            selector = """<rule e="way" k="highway" v="path|footway|cycleway|bridleway|track|steps">""",
            stroke = pathBrown(dark),
            stops = PATH_STOPS,
            dashes = "8,5",
        )
    }

    internal val MINOR_ROAD_STOPS = listOf(13 to 0.5f, 15 to 1.5f, 18 to 6f)

    /** Half a minor road's width close in, where a town's sidewalks otherwise crowd it out. */
    internal val PATH_STOPS = listOf(13 to 0.4f, 15 to 1f, 18 to 3f)

    // --- Names -----------------------------------------------------------------------

    /** Collisions are settled by `priority`, higher winning, not by document order. */
    private fun StringBuilder.labels(
        label: Color,
        background: Color,
        scale: Float,
        contourLabels: Boolean,
        dark: Boolean,
    ) {
        // Lowest priority: a height is the first thing to give way.
        if (contourLabels) {
            append("""<rule e="way" k="contour_ext" v="elevation_major" zoom-min="14">""")
            append(
                """<pathText k="ele" font-size="${fontSize(10, scale)}" priority="0" """ +
                    """fill="${contourBrown(dark, 0xFF)}" stroke="${background.css()}" stroke-width="2.0"/></rule>""",
            )
        }

        caption("natural", "water", label, background, minZoom = 10, size = fontSize(12, scale), priority = 10)
        caption(
            "place",
            "city|town|village|hamlet|locality",
            label,
            background,
            minZoom = 6,
            size = fontSize(14, scale),
            priority = 20,
        )

        // Trail names win every collision: "which trail is this" is the app's core question.
        append("""<rule e="way" k="highway" v="*" zoom-min="14">""")
        append(
            """<pathText k="name" font-size="${fontSize(11, scale)}" priority="30" fill="${label.css()}" """ +
                """stroke="${background.css()}" stroke-width="2.0"/></rule>""",
        )
    }

    private fun StringBuilder.caption(
        key: String,
        values: String,
        label: Color,
        halo: Color,
        minZoom: Int,
        size: String,
        priority: Int,
    ) {
        append("""<rule e="any" k="$key" v="$values" zoom-min="$minZoom">""")
        append(
            """<caption k="name" font-size="$size" priority="$priority" fill="${label.css()}" """ +
                """stroke="${halo.css()}" stroke-width="2.0" display="ifspace"/></rule>""",
        )
    }

    private fun fontSize(base: Int, scale: Float) = String.format(Locale.ROOT, "%.1f", base * scale)

    // --- Rule construction -------------------------------------------------------------

    private fun StringBuilder.area(key: String, values: String, fill: String) {
        append("""<rule e="way" k="$key" v="$values" closed="yes"><area fill="$fill"/></rule>""")
    }

    /**
     * A line whose width follows [stops], filled in to one nested rule per zoom level: stepping
     * only at the stops made roads hold a width then jump. `e="any" k="*" v="*"` is the
     * pass-through child selector.
     */
    private fun StringBuilder.zoomedLine(
        selector: String,
        stroke: String,
        stops: List<Pair<Int, Float>>,
        dashes: String? = null,
    ) {
        val widths = perZoom(stops)
        append(selector)
        widths.forEachIndexed { index, (zoom, width) ->
            val next = widths.getOrNull(index + 1)?.first
            val range = if (next == null) """zoom-min="$zoom"""" else """zoom-min="$zoom" zoom-max="${next - 1}""""
            append("""<rule e="any" k="*" v="*" $range>""")
            append("""<line stroke="$stroke" stroke-width="${unscaled(zoom, width)}" """)
            append("""stroke-linecap="round" stroke-linejoin="round"""")
            if (dashes != null) append(""" stroke-dasharray="$dashes"""")
            append("/></rule>")
        }
        append("</rule>")
    }

    /**
     * [width] with VTM's own growth divided out: its tile loader widens lines 1.4x per zoom
     * above 12, which on per-zoom widths drew z18 streets six times too wide.
     */
    internal fun unscaled(zoom: Int, width: Float): Float = Math.round(
        width / Math.pow(VTM_STROKE_INCREASE, (zoom - VTM_STROKE_MIN_ZOOM).coerceAtLeast(0).toDouble()).toFloat() *
            1000,
    ) /
        1000f

    internal fun perZoom(stops: List<Pair<Int, Float>>): List<Pair<Int, Float>> =
        stops.zipWithNext().flatMap { (from, to) ->
            (from.first until to.first).map { zoom ->
                val t = (zoom - from.first).toFloat() / (to.first - from.first)
                zoom to Math.round((from.second + (to.second - from.second) * t) * 100) / 100f
            }
        } + stops.last()

    // --- Fixed colours ------------------------------------------------------------------
    // Water and vegetation ignore Material You: atlas convention outranks colour harmony.

    private fun waterBlue(dark: Boolean) = if (dark) "#16323f" else "#a6cbe3"

    private fun greenVegetation(dark: Boolean) = if (dark) "#1e3220" else "#d5e9cf"

    /**
     * Trail brown. The dark variant is much darker: roads step toward white in dark mode, and
     * a mid-tone brown outshone them all.
     */
    private fun pathBrown(dark: Boolean) = if (dark) "#6b4f3e" else "#a8785f"

    /** Atlas brown, `#aarrggbb`. Lighter in dark mode, where roads step toward white. */
    private fun contourBrown(dark: Boolean, alpha: Int) =
        String.format(Locale.ROOT, "#%02x%s", alpha, if (dark) "b08a68" else "8b5a2b")

    /** Below this background luminance, derived colours move toward white. */
    private const val DARK_THRESHOLD = 0.35f

    /** VectorTileLoader's STROKE_INCREASE and STROKE_MIN_ZOOM; see [unscaled]. */
    private const val VTM_STROKE_INCREASE = 1.4
    private const val VTM_STROKE_MIN_ZOOM = 12
}

/** `#rrggbb`, with `Locale.ROOT` since it's a document token. */
private fun Color.css(): String = String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and toArgb())

/** A step away from the background: lighter on a dark theme, darker on a light one. */
private fun Color.shifted(amount: Float, dark: Boolean): Color = if (dark) lighten(amount) else darken(amount)

private fun Color.darken(amount: Float): Color = Color(
    red = (red * (1f - amount)).coerceIn(0f, 1f),
    green = (green * (1f - amount)).coerceIn(0f, 1f),
    blue = (blue * (1f - amount)).coerceIn(0f, 1f),
    alpha = alpha,
)

private fun Color.lighten(amount: Float): Color = Color(
    red = (red + (1f - red) * amount).coerceIn(0f, 1f),
    green = (green + (1f - green) * amount).coerceIn(0f, 1f),
    blue = (blue + (1f - blue) * amount).coerceIn(0f, 1f),
    alpha = alpha,
)

private const val MAJOR_ROADS =
    "motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"
