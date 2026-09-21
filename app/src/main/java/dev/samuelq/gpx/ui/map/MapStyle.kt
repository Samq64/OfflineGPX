package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.map.PmtilesHeader
import org.json.JSONArray
import org.json.JSONObject

/**
 * The style the renderer is handed, built at runtime rather than shipped as an asset,
 * since the one thing that varies is the imported archive's path - a style is where a
 * source's URL lives. No basemap is the same code with one branch taken: routes on a flat
 * background.
 *
 * Labels come from glyph ranges bundled in assets: no network to fetch them, and MapLibre
 * Native silently renders no text at all without a `glyphs` URL. Only Latin is shipped
 * (~280 KB); see [LABEL_FONT].
 */
object MapStyle {

    /**
     * A style for [map], or an empty one if there is no basemap.
     *
     * @param background what to paint where there are no tiles, which is the whole screen
     *   when nothing is imported. Comes from the theme so the empty state is not a white
     *   rectangle in a dark app.
     */
    fun json(
        maps: List<OfflineMap>,
        background: Color,
        land: Color,
        label: Color,
    ): String {
        val layers = JSONArray().put(
            JSONObject()
                .put("id", BACKGROUND_LAYER)
                .put("type", "background")
                .put("paint", JSONObject().put("background-color", background.css()))
        )

        val sources = JSONObject()
        maps.forEachIndexed { index, map ->
            // Fully specified: MapLibre Native, unlike GL JS, doesn't resolve a pmtiles://
            // URL against a style base, and this style has no base to resolve against anyway.
            val url = "pmtiles://file://${map.file.absolutePath}"
            if (map.header.isVector) {
                sources.put(sourceId(index), JSONObject().put("type", "vector").put("url", url))
            } else {
                sources.put(
                    sourceId(index),
                    JSONObject()
                        .put("type", "raster")
                        .put("url", url)
                        // 512 is what every raster archive cut from a web basemap uses;
                        // getting this wrong shows as a map at the wrong zoom, not as an
                        // error, so it is worth stating rather than defaulting.
                        .put("tileSize", 512),
                )
            }
        }

        // Every map's ground before any map's text, so no archive's labels end up buried
        // under the next archive's fills. The maps never overlap, so nothing else about
        // their relative order can matter.
        maps.forEachIndexed { index, map ->
            if (map.header.isVector) {
                groundLayers(index, map.header, land, background).forEach(layers::put)
            } else {
                layers.put(
                    JSONObject()
                        .put("id", "basemap-raster-$index")
                        .put("type", "raster")
                        .put("source", sourceId(index))
                )
            }
        }
        maps.forEachIndexed { index, map ->
            if (map.header.isVector) {
                labelLayers(index, map.header, label, background).forEach(layers::put)
            }
        }

        return JSONObject()
            .put("version", 8)
            .put("name", "gpx-offline")
            // Font ranges out of app assets, never a URL - omitting `glyphs` silently
            // renders no text at all, and there's no lighter option than shipping the PBFs.
            .put("glyphs", "asset://glyphs/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    /**
     * A walking and cycling basemap, in the Protomaps schema: `earth`, `landuse`, `water`,
     * `roads`, with `roads` carrying `kind` (footway/cycleway/bridleway/path all collapse
     * to `path`) and, more finely, `kind_detail` - which is what tells a sidewalk apart
     * from the trail it runs beside, both otherwise `kind: path`. An archive in another
     * schema draws a blank basemap with routes still on top - the file is fine, this style
     * just doesn't know it.
     *
     * Deliberately plain otherwise: one green for anything vegetated, no urban landuse
     * tint. Water, paths and buildings are worth reading at a glance; the rest is context.
     */
    private fun groundLayers(
        index: Int,
        header: PmtilesHeader,
        land: Color,
        background: Color,
    ): List<JSONObject> {
        // Every derived colour moves away from the background, not toward black -
        // darkening unconditionally made roads vanish against near-black dark-mode land.
        val dark = background.luminance() < DarkThreshold
        val source = sourceId(index)

        return listOf(
            fill("earth-$index", source, "earth", land.css()),

            // One green for every vegetated/protected kind - the distinction between a
            // park, a forest and farmland isn't one this app has a reason to draw.
            fill(
                id = "landuse-green-$index",
                source = source,
                sourceLayer = "landuse",
                color = greenVegetation(dark),
                filter = kindIsOneOf(
                    "national_park", "protected_area", "nature_reserve",
                    "farmland", "farmyard", "orchard", "meadow",
                    "park", "grass", "garden", "golf_course", "recreation_ground",
                    "pitch", "dog_park", "playground", "camp_site", "cemetery",
                    "forest", "wood", "scrub",
                ),
            ),

            // Polygons only: the water layer carries rivers as *lines* too, and a fill
            // layer handed a LineString closes and fills the loop, painting a town-sized
            // blue blob along the river's course. Water stays a fixed blue regardless of
            // theme - see [waterBlue] - so it never reads as anything else.
            fill(
                id = "water-$index",
                source = source,
                sourceLayer = "water",
                color = waterBlue(dark),
                filter = isGeometry("Polygon"),
            ),
            line(
                id = "water-line-$index",
                source = source,
                sourceLayer = "water",
                color = waterBlue(dark),
                widths = listOf(10 to 0.6f, 13 to 1.6f, 16 to 5f),
                filter = isGeometry("LineString"),
            ),

            // A lone building is a landmark on a country road - "the farmhouse" or "the
            // barn" is how a route gets described - so this wants to read as a shape, not
            // vanish as texture the way dense urban infill would if it were this dark.
            fill("buildings-$index", source, "buildings", land.shifted(0.16f, dark).css()),

            // Roads, quietest first, so a motorway is never buried under a service road.
            // `aerialway`, `ferry`, `pier`, `rail` and `aeroway` also live in this schema
            // and are deliberately not drawn - a hiking map has no use for a runway.
            line(
                id = "roads-minor-$index",
                source = source,
                sourceLayer = "roads",
                color = land.shifted(0.30f, dark).css(),
                widths = listOf(12 to 0.5f, 14 to 1.5f, 17 to 6f),
                filter = kindIsOneOf("minor_road"),
            ),
            line(
                id = "roads-major-$index",
                source = source,
                sourceLayer = "roads",
                color = land.shifted(0.45f, dark).css(),
                widths = listOf(8 to 0.5f, 12 to 2f, 17 to 10f),
                filter = kindIsOneOf("highway", "major_road"),
            ),

            // Dashed, so a path reads as different from a road at a glance rather than by
            // comparing two widths. A sidewalk is excluded by kind_detail rather than kind:
            // it's the pavement beside a street this app already draws as a road, not a
            // trail in its own right, and drawing both doubled every street in town. An
            // untagged path (no kind_detail at all) isn't caught by this and still shows.
            line(
                id = "roads-path-$index",
                source = source,
                sourceLayer = "roads",
                color = pathBrown(dark),
                widths = listOf(12 to 1f, 14 to 2.5f, 17 to 7f),
                filter = allOf(kindIsOneOf("path"), isNotKindDetail("sidewalk")),
                dashes = listOf(2f, 1.2f),
                opacity = 0.85,
            ),

        )
    }

    /**
     * The names, drawn after every map's ground. Ordered least to most important, since
     * MapLibre resolves label collisions by layer order: a trail name beats a lake name
     * beats a hamlet.
     */
    private fun labelLayers(
        index: Int,
        header: PmtilesHeader,
        label: Color,
        background: Color,
    ): List<JSONObject> {
        val source = sourceId(index)
        return listOf(
            // Lakes and rivers, for orienting inside a park where there is little else.
            symbol(
                id = lowestLabelLayer(index),
                source = source,
                sourceLayer = "water",
                minZoom = 9,
                sizes = listOf(9 to 10f, 14 to 13f),
                color = label.css(),
                halo = background.css(),
                // Deliberately not clipped, unlike the other two: a lake is a polygon, and
                // MapLibre's `within` rejects polygons outright.
            ),

            // Towns. The "where am I" layer, and the one worth having at almost every zoom.
            symbol(
                id = "places-label-$index",
                source = source,
                sourceLayer = "places",
                minZoom = 5,
                sizes = listOf(5 to 11f, 10 to 13f, 14 to 16f),
                color = label.css(),
                halo = background.css(),
                filter = allOf(within(header), kindIsOneOf("country", "region", "locality")),
            ),

            // The names of the paths themselves, along them - "which trail is this" is the
            // question the app exists to help with, so this is last and wins every collision.
            symbol(
                id = "roads-label-$index",
                source = source,
                sourceLayer = "roads",
                minZoom = 12,
                sizes = listOf(12 to 10f, 16 to 13f),
                color = label.css(),
                halo = background.css(),
                alongLine = true,
                filter = within(header),
            ),
        )
    }

    // Deliberately no zoom floor on the ground layers - the coverage mask now does what a
    // floor was a crude stand-in for, at every zoom rather than just past one threshold.

    /** The lowest text layer of a map. Routes go below the first so they never cover a name. */
    fun lowestLabelLayer(index: Int): String = "water-label-$index"

    /**
     * Below this luminance the theme is treated as dark, and every derived colour moves
     * towards white instead of towards black.
     */
    private const val DarkThreshold = 0.35f

    // --- The colours that are not the theme's to choose ----------------------------
    //
    // Buildings and roads follow Material You; water and vegetation do not - blue is water
    // and green is vegetation in every atlas, and that convention outranks colour harmony.
    // Each has a light and dark variant rather than being derived, since a single value
    // can't sit correctly against both a near-white and a near-black ground.

    private fun waterBlue(dark: Boolean) = if (dark) "#16323f" else "#a6cbe3"

    private fun greenVegetation(dark: Boolean) = if (dark) "#1e3220" else "#d5e9cf"

    /**
     * A desaturated red-brown, as paper maps have used for trails for a century - muted
     * and at 85% opacity, since the dash already says "path".
     */
    private fun pathBrown(dark: Boolean) = if (dark) "#a9795d" else "#a8785f"


    // --- Layer construction --------------------------------------------------------

    private fun fill(
        id: String,
        source: String,
        sourceLayer: String,
        color: String,
        filter: JSONArray? = null,
    ) = JSONObject()
        .put("id", id)
        .put("type", "fill")
        .put("source", source)
        .put("source-layer", sourceLayer)
        .put("paint", JSONObject().put("fill-color", color))
        .apply { if (filter != null) put("filter", filter) }

    /**
     * A line layer whose width is interpolated across zoom - a fixed-width road is a
     * thread at z10 and a ribbon at z17.
     */
    private fun line(
        id: String,
        source: String,
        sourceLayer: String,
        color: String,
        widths: List<Pair<Int, Float>>,
        filter: JSONArray? = null,
        dashes: List<Float>? = null,
        opacity: Double? = null,
    ): JSONObject {
        val width = JSONArray()
            .put("interpolate")
            .put(JSONArray().put("exponential").put(1.4))
            .put(JSONArray().put("zoom"))
        widths.forEach { (zoom, pixels) -> width.put(zoom).put(pixels) }

        val paint = JSONObject()
            .put("line-color", color)
            .put("line-width", width)
        if (dashes != null) {
            paint.put("line-dasharray", JSONArray().apply { dashes.forEach(::put) })
        }
        if (opacity != null) paint.put("line-opacity", opacity)

        return JSONObject()
            .put("id", id)
            .put("type", "line")
            .put("source", source)
            .put("source-layer", sourceLayer)
            .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
            .put("paint", paint)
            .apply { if (filter != null) put("filter", filter) }
    }

    /**
     * A text layer. `text-font` is deliberately a single-element stack - MapLibre joins a
     * multi-element one with commas into a literal folder name like `A,B`, which doesn't
     * exist, and fails silently with no text rendered.
     */
    private fun symbol(
        id: String,
        source: String,
        sourceLayer: String,
        minZoom: Int,
        sizes: List<Pair<Int, Float>>,
        color: String,
        halo: String,
        filter: JSONArray? = null,
        alongLine: Boolean = false,
    ): JSONObject {
        val size = JSONArray()
            .put("interpolate")
            .put(JSONArray().put("linear"))
            .put(JSONArray().put("zoom"))
        sizes.forEach { (zoom, points) -> size.put(zoom).put(points) }

        val layout = JSONObject()
            .put("text-field", JSONArray().put("get").put("name"))
            .put("text-font", JSONArray().put(LABEL_FONT))
            .put("text-size", size)
            // Long names wrap rather than running off across the map.
            .put("text-max-width", 7)
        if (alongLine) {
            layout.put("symbol-placement", "line")
                .put("text-rotation-alignment", "map")
                .put("text-pitch-alignment", "viewport")
        }

        return JSONObject()
            .put("id", id)
            .put("type", "symbol")
            .put("source", source)
            .put("source-layer", sourceLayer)
            .put("minzoom", minZoom)
            .put("layout", layout)
            .put(
                "paint",
                JSONObject()
                    .put("text-color", color)
                    .put("text-halo-color", halo)
                    .put("text-halo-width", 1.4)
            )
            .apply { if (filter != null) put("filter", filter) }
    }

    // --- Filters ---------------------------------------------------------------------

    /**
     * `["match", ["get","kind"], [values...], true, false]`. Not `["in", ...]` - the
     * modern and legacy `in` filter syntaxes look alike but take different shapes, and
     * mixing them up produces a silently empty layer rather than an error.
     */
    private fun kindIsOneOf(vararg kinds: String): JSONArray = JSONArray()
        .put("match")
        .put(JSONArray().put("get").put("kind"))
        .put(JSONArray().apply { kinds.forEach(::put) })
        .put(true)
        .put(false)

    /**
     * `["==", ["geometry-type"], type]`. Needed because `water` holds both lakes as
     * polygons and rivers as lines in the same source layer.
     */
    private fun isGeometry(type: String): JSONArray = JSONArray()
        .put("==")
        .put(JSONArray().put("geometry-type"))
        .put(type)

    /**
     * `["!=", ["get","kind_detail"], value]`. A feature with no `kind_detail` at all is
     * `null != value`, which MapLibre treats as true - so this only ever excludes the one
     * sub-kind named, never anything the archive left untagged.
     */
    private fun isNotKindDetail(value: String): JSONArray = JSONArray()
        .put("!=")
        .put(JSONArray().put("get").put("kind_detail"))
        .put(value)

    /**
     * `["within", <polygon>]` - true for features inside the archive's box. Labels need
     * this and fills don't: fills are hidden by the outside mask, but that mask sits below
     * the labels (for routes to stay below them too), which would leave names floating
     * over nothing. The box, not the true coverage - `within` takes one polygon - so a
     * name can still appear in an empty corner of a non-rectangular extract.
     */
    private fun within(header: PmtilesHeader): JSONArray {
        val ring = JSONArray()
            .put(JSONArray().put(header.minLongitude).put(header.minLatitude))
            .put(JSONArray().put(header.maxLongitude).put(header.minLatitude))
            .put(JSONArray().put(header.maxLongitude).put(header.maxLatitude))
            .put(JSONArray().put(header.minLongitude).put(header.maxLatitude))
            .put(JSONArray().put(header.minLongitude).put(header.minLatitude))
        return JSONArray()
            .put("within")
            .put(
                JSONObject()
                    .put("type", "Polygon")
                    .put("coordinates", JSONArray().put(ring))
            )
    }

    private fun allOf(vararg filters: JSONArray): JSONArray =
        JSONArray().put("all").apply { filters.forEach(::put) }

    // --- Ids -------------------------------------------------------------------------

    private const val BACKGROUND_LAYER = "background"

    /** One source per imported map, whatever format each turns out to be. */
    private fun sourceId(index: Int): String = "basemap-$index"

    /**
     * The one font shipped, matching the `assets/glyphs` directory name exactly. Only
     * Latin ranges are bundled (~280 KB of 6.2 MB); a map in another script renders blank
     * names until that range's .pbf is added. CJK is handled separately, from the device.
     */
    private const val LABEL_FONT = "Noto Sans Regular"
}

/** `#rrggbb`, which is the only colour spelling worth emitting into a style. */
private fun Color.css(): String = String.format("#%06X", 0xFFFFFF and toArgb())

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
