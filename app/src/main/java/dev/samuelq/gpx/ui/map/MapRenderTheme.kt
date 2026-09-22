package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * The render theme the basemap is drawn with, built at runtime rather than shipped as an
 * asset so it can take its colours from the theme the user is actually in.
 *
 * Mapsforge reads raw OSM tags, not a normalised schema, so every rule here filters on
 * `highway`, `natural`, `landuse` and friends directly. There is no expression language:
 * a width that varies with zoom is written out as nested rules, one per band, rather than
 * as an interpolation.
 *
 * The background is transparent and the land is drawn by the map screen as an overlay
 * under this, so that ground the imported file does not cover reads as empty rather than
 * as land. See [OfflineMapCanvas].
 */
object MapRenderTheme {

    fun xml(land: Color, label: Color, background: Color): String {
        // Every derived colour moves away from the background, not toward black -
        // darkening unconditionally made roads vanish against near-black dark-mode land.
        val dark = background.luminance() < DARK_THRESHOLD

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<rendertheme xmlns="http://mapsforge.org/renderTheme" version="6" """)
            // Transparent, not the land colour: the tile layer paints only features, and
            // whatever is underneath shows through where the file has no data.
            append("""map-background="#00000000" map-background-outside="#00000000">""")

            // Ground, quietest first. Nothing here is ordered by an explicit z-index -
            // mapsforge paints in document order, so this list is the stacking order.
            vegetation(dark)
            water(dark)
            buildings(land, dark)
            roads(land, dark)
            labels(label, background)

            append("</rendertheme>")
        }
    }

    // --- Ground ----------------------------------------------------------------------

    /**
     * One green for every vegetated or protected kind. The distinction between a park, a
     * forest and farmland is not one this app has a reason to draw, and collapsing them
     * means a single rule instead of a dozen near-identical ones.
     */
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
     * Polygons and lines separately: `natural=water` is lakes, `waterway` is rivers, and a
     * river handed to an area renderer closes and fills its own course into a blob. Water
     * keeps a fixed blue in both themes - see [waterBlue].
     */
    private fun StringBuilder.water(dark: Boolean) {
        val blue = waterBlue(dark)
        append("""<rule e="way" k="natural" v="water" closed="yes"><area fill="$blue"/></rule>""")
        append("""<rule e="way" k="landuse" v="reservoir|basin" closed="yes"><area fill="$blue"/></rule>""")
        zoomedLine(
            selector = """<rule e="way" k="waterway" v="river|canal" closed="no">""",
            stroke = blue,
            widths = listOf(10 to 0.6f, 13 to 1.6f, 16 to 5f),
        )
        zoomedLine(
            selector = """<rule e="way" k="waterway" v="stream|drain" closed="no">""",
            stroke = blue,
            widths = listOf(13 to 0.5f, 16 to 2f),
        )
    }

    /**
     * A lone building is a landmark on a country road - "the farmhouse" or "the barn" is
     * how a route gets described - so this wants to read as a shape rather than vanish as
     * texture the way dense urban infill would if it were this dark.
     */
    private fun StringBuilder.buildings(land: Color, dark: Boolean) {
        append("""<rule e="way" k="building" v="*" closed="yes" zoom-min="15">""")
        append("""<area fill="${land.shifted(0.16f, dark).css()}"/></rule>""")
    }

    /**
     * Roads, quietest first, so a motorway is never buried under a service road.
     * `aeroway` and `ferry` are deliberately not drawn - a hiking map has no use for a
     * runway. Rail is; see below.
     */
    private fun StringBuilder.roads(land: Color, dark: Boolean) {
        zoomedLine(
            selector = """<rule e="way" k="highway" v="residential|unclassified|living_street|service|pedestrian">""",
            stroke = land.shifted(0.30f, dark).css(),
            widths = listOf(12 to 0.5f, 14 to 1.5f, 17 to 6f),
        )
        zoomedLine(
            selector = """<rule e="way" k="highway" v="motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link">""",
            stroke = land.shifted(0.45f, dark).css(),
            widths = listOf(8 to 0.5f, 12 to 2f, 17 to 10f),
        )

        // A tighter, evener dash than a path's - closer to the tick marks a rail line is
        // conventionally drawn with, and different enough from a path's longer dash that
        // the two do not read as the same kind of line at a glance. Subway is excluded: it
        // runs underground, so drawing it is drawing a line over ground it never crosses.
        zoomedLine(
            selector = """<rule e="way" k="railway" v="rail|light_rail|narrow_gauge">""",
            stroke = land.shifted(0.55f, dark).css(),
            widths = listOf(11 to 0.5f, 14 to 1f, 17 to 3f),
            dashes = "4,4",
        )

        // Dashed, so a path reads as different from a road at a glance rather than by
        // comparing two widths.
        //
        // Sidewalks and crossings are drawn here along with everything else, which they
        // were not before: the PMTiles schema carried a `kind_detail` that told a sidewalk
        // from the trail beside it, and mapsforge's default tag config does not record
        // `footway=sidewalk` at all. In a town this draws a second line beside every
        // street. Fixing it means cutting from files written with a custom tag config.
        zoomedLine(
            selector = """<rule e="way" k="highway" v="path|footway|cycleway|bridleway|track|steps">""",
            stroke = pathBrown(dark),
            widths = listOf(12 to 1f, 14 to 2.5f, 17 to 7f),
            dashes = "8,5",
        )
    }

    // --- Names -----------------------------------------------------------------------

    /**
     * Least to most important, since mapsforge resolves label collisions in document
     * order: a trail name beats a lake name beats a hamlet.
     */
    private fun StringBuilder.labels(label: Color, background: Color) {
        caption("natural", "water", label, background, minZoom = 9, size = 12)
        caption("place", "city|town|village|hamlet|locality", label, background, minZoom = 5, size = 14)

        // The names of the paths themselves, along them - "which trail is this" is the
        // question the app exists to help with, so this is last and wins every collision.
        append("""<rule e="way" k="highway" v="*" zoom-min="13">""")
        append(
            """<pathText k="name" font-size="11" fill="${label.css()}" """ +
                """stroke="${background.css()}" stroke-width="2.0"/></rule>""",
        )
    }

    private fun StringBuilder.caption(
        key: String,
        values: String,
        label: Color,
        halo: Color,
        minZoom: Int,
        size: Int,
    ) {
        append("""<rule e="any" k="$key" v="$values" zoom-min="$minZoom">""")
        append(
            """<caption k="name" font-size="$size" fill="${label.css()}" """ +
                """stroke="${halo.css()}" stroke-width="2.0" display="ifspace"/></rule>""",
        )
    }

    // --- Rule construction -------------------------------------------------------------

    private fun StringBuilder.area(key: String, values: String, fill: String) {
        append("""<rule e="way" k="$key" v="$values" closed="yes"><area fill="$fill"/></rule>""")
    }

    /**
     * A line whose width steps with zoom. Mapsforge has no interpolation, so each band in
     * [widths] becomes a nested rule that inherits the selector's filter and narrows the
     * zoom range. `e="any" k="*" v="*"` is the pass-through child selector.
     */
    private fun StringBuilder.zoomedLine(
        selector: String,
        stroke: String,
        widths: List<Pair<Int, Float>>,
        dashes: String? = null,
    ) {
        append(selector)
        widths.forEachIndexed { index, (zoom, width) ->
            val next = widths.getOrNull(index + 1)?.first
            val range = if (next == null) """zoom-min="$zoom"""" else """zoom-min="$zoom" zoom-max="${next - 1}""""
            append("""<rule e="any" k="*" v="*" $range>""")
            append("""<line stroke="$stroke" stroke-width="$width" """)
            append("""stroke-linecap="round" stroke-linejoin="round"""")
            if (dashes != null) append(""" stroke-dasharray="$dashes"""")
            append("/></rule>")
        }
        append("</rule>")
    }

    // --- The colours that are not the theme's to choose --------------------------------
    //
    // Buildings and roads follow Material You; water and vegetation do not - blue is water
    // and green is vegetation in every atlas, and that convention outranks colour harmony.

    private fun waterBlue(dark: Boolean) = if (dark) "#16323f" else "#a6cbe3"

    private fun greenVegetation(dark: Boolean) = if (dark) "#1e3220" else "#d5e9cf"

    /**
     * A desaturated red-brown, as paper maps have used for trails for a century. Muted,
     * since the dash already says "path".
     */
    private fun pathBrown(dark: Boolean) = if (dark) "#a9795d" else "#a8785f"

    /**
     * Below this luminance the theme is treated as dark, and every derived colour moves
     * towards white instead of towards black.
     */
    private const val DARK_THRESHOLD = 0.35f
}

/** `#rrggbb` - a token in a document, hence `Locale.ROOT`, not a number anybody reads. */
internal fun Color.css(): String = String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and toArgb())

/**
 * A step away from the background, for deriving a palette from one surface colour. The
 * `dark` flag matters: stepping toward black unconditionally is wrong on a dark theme,
 * where the ground is already near-black. Contrast is a direction, not a subtraction.
 */
private fun Color.shifted(amount: Float, dark: Boolean): Color =
    if (dark) lighten(amount) else darken(amount)

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
