package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.map.PmtilesHeader
import org.json.JSONArray
import org.json.JSONObject

/**
 * The style the renderer is handed, built at runtime rather than shipped as an asset.
 *
 * It has to be built, because the one thing that varies is the path of the archive the
 * user imported, and a style is where a source's URL lives. Building it also means the
 * no-basemap case is the same code with one branch taken: routes on a flat background,
 * which is what this app looked like before there were basemaps at all and is still a
 * perfectly good map of a ride.
 *
 * Labels come from glyph ranges bundled in assets, because there is no other way: an app
 * with no network cannot fetch them, and MapLibre Native has no local-font fallback - a
 * style without a `glyphs` URL renders text layers as nothing at all, silently. Only the
 * Latin ranges are shipped, which is ~280 KB; see [LABEL_FONT] for what that leaves out
 * and how to extend it.
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
            // Fully specified, as MapLibre Native requires - it does not resolve a
            // pmtiles:// URL against the style's own base the way GL JS does. The style is
            // built from a string here and has no base to resolve against anyway.
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
            // Font ranges out of app assets, never a URL. This is the whole reason labels
            // took a second pass: MapLibre Native has no local fallback - omit `glyphs`
            // and a layer with a text-field silently renders nothing at all - so text
            // means shipping the glyph PBFs and there is no lighter option.
            .put("glyphs", "asset://glyphs/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    /**
     * A walking and cycling basemap, in the Protomaps schema.
     *
     * The schema is not a guess - it is read off an archive cut from the Protomaps daily
     * build: `earth`, `landuse`, `water`, `roads` and `buildings`, with `roads` carrying
     * `kind` (a coarse grouping in which every footway, cycleway, bridleway and path
     * collapses to `path`) and `kind_detail` (the original highway tag). If someone
     * imports an archive in another schema the basemap will be blank and the routes will
     * still draw, which is the correct failure: the file is fine, this style does not
     * know it.
     *
     * Paths are drawn *wider than residential roads and in a colour of their own*. On a
     * general-purpose basemap a trail is a hairline you lose against a car park; on this
     * one the trail is the subject and the roads are there to tell you where the trail is.
     */
    private fun groundLayers(
        index: Int,
        header: PmtilesHeader,
        land: Color,
        background: Color,
    ): List<JSONObject> {
        // Every derived colour below has to move *away* from the background, not toward
        // black. Darkening unconditionally is what made roads vanish in dark mode: the
        // land is already near-black there, so a darker road is an invisible one.
        val dark = background.luminance() < DarkThreshold
        val source = sourceId(index)

        return listOf(
            fill("earth-$index", source, "earth", land.css()),

            // Built-up ground follows the theme, because it is the part of the map that is
            // just context. Green and blue do not - see below.
            fill(
                id = "landuse-urban-$index",
                source = source,
                sourceLayer = "landuse",
                color = land.shifted(0.05f, dark).css(),
                filter = kindIsOneOf(
                    "residential", "commercial", "industrial", "neighbourhood",
                    "military", "railway", "aerodrome",
                ),
            ),

            // Greens, palest and broadest first so the specific cover wins where they
            // overlap - a national park drawn over its own forest would erase it.
            fill(
                id = "landuse-protected-$index",
                source = source,
                sourceLayer = "landuse",
                color = greenProtected(dark),
                filter = kindIsOneOf("national_park", "protected_area", "nature_reserve"),
            ),
            fill(
                id = "landuse-farmland-$index",
                source = source,
                sourceLayer = "landuse",
                color = greenFarmland(dark),
                filter = kindIsOneOf("farmland", "farmyard", "orchard", "meadow"),
            ),
            fill(
                id = "landuse-grass-$index",
                source = source,
                sourceLayer = "landuse",
                color = greenGrass(dark),
                filter = kindIsOneOf(
                    "park", "grass", "garden", "golf_course", "recreation_ground",
                    "pitch", "dog_park", "playground", "camp_site", "cemetery",
                ),
            ),
            fill(
                id = "landuse-forest-$index",
                source = source,
                sourceLayer = "landuse",
                color = greenForest(dark),
                filter = kindIsOneOf("forest", "wood", "scrub"),
            ),

            // Polygons only. The water layer carries rivers and streams as *lines* as
            // well as lakes as areas, and a fill layer handed a LineString closes it and
            // fills the loop - which paints a town-sized blue blob following the river's
            // course, and repaints a differently-shaped one at every zoom as the line is
            // generalised. The geometry test is what separates a lake from a river.
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

            // Faint: at trail zooms buildings are texture that says "town", not something
            // anyone is reading.
            fill("buildings-$index", source, "buildings", land.shifted(0.12f, dark).css()),

            // Roads, quietest first, so a motorway is never buried under a service road.
            //
            // The kinds are the schema's complete list for this purpose - highway,
            // major_road, minor_road, path - not a guess. `aerialway`, `ferry`, `pier`,
            // `rail` and `aeroway` also live in this layer and are deliberately not drawn;
            // a hiking map has no use for a runway, and rail is the only arguable omission.
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

            // The point of the map, but no longer shouting it. Dashed, because a path is
            // not a road and that difference should be legible at a glance rather than by
            // comparing two widths - which means the dash carries the signal and the
            // colour does not have to.
            line(
                id = "roads-path-$index",
                source = source,
                sourceLayer = "roads",
                color = pathBrown(dark),
                widths = listOf(12 to 1f, 14 to 2.5f, 17 to 7f),
                filter = JSONArray().put("==").put(JSONArray().put("get").put("kind")).put("path"),
                dashes = listOf(2f, 1.2f),
                opacity = 0.85,
            ),

        )
    }

    /**
     * The names, which are drawn after every map's ground rather than with their own.
     *
     * Ordered least to most important within one map, because MapLibre resolves collisions
     * by layer order and the thing you most want to read should win: a trail name beats a
     * lake name beats a hamlet.
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
                // Deliberately not clipped, unlike the other two. A lake is a polygon, and
                // MapLibre's `within` only understands points and lines - it rejects
                // polygons outright and would filter every one of them out, silently
                // deleting every lake name on the map.
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

            // The names of the paths themselves, written along them. This is the layer
            // that answers "which trail is this", which is the question the app exists to
            // help with - so it is last, and it wins every collision.
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

    // There is deliberately no zoom floor on the ground layers any more. One was added to
    // stop detail bleeding past the edge of an extract at low zoom, where a single tile
    // spans a region; the exact coverage mask does that properly and at every zoom, so the
    // floor only survived as a way to make the map vanish at the zoom you first see it -
    // labels drawn over nothing, because those kept their own minimums.

    /** The lowest text layer of a map. Routes go below the first so they never cover a name. */
    fun lowestLabelLayer(index: Int): String = "water-label-$index"

    /**
     * Below this luminance the theme is treated as dark, and every derived colour moves
     * towards white instead of towards black.
     */
    private const val DarkThreshold = 0.35f

    // --- The colours that are not the theme's to choose ----------------------------
    //
    // Buildings and roads follow Material You, because they are context and it is pleasant
    // when they match the rest of the app. These do not. Water that is theme-coloured is a
    // grey blob you have to decode rather than read, and a forest that comes out mauve
    // because of someone's wallpaper is not a map. Blue is water and green is vegetation
    // in every atlas ever printed, and that convention is worth more than colour harmony.
    //
    // Each has a light and a dark variant rather than being derived, because deriving is
    // what produced an invisible road: a single value cannot sit correctly against both a
    // near-white and a near-black ground.

    private fun waterBlue(dark: Boolean) = if (dark) "#16323f" else "#a6cbe3"

    /** Deepest of the greens and drawn last, so woodland wins where cover overlaps. */
    private fun greenForest(dark: Boolean) = if (dark) "#1f3a24" else "#c2ddbd"

    private fun greenGrass(dark: Boolean) = if (dark) "#1e3220" else "#d5e9cf"

    /** Olive rather than green, so cultivated ground is not read as woodland. */
    private fun greenFarmland(dark: Boolean) = if (dark) "#2f3122" else "#e6e7cd"

    /** Palest, broadest, drawn first: a park boundary is context, not ground cover. */
    private fun greenProtected(dark: Boolean) = if (dark) "#1b291d" else "#e9f0e3"

    /**
     * Paths are their own colour rather than a shade of the road colour: this has to read
     * as "trail" against both grounds, and a hue derived from the surface would go grey in
     * one of them. A desaturated red-brown is what paper maps have used for a century -
     * muted here, and drawn at 85% opacity, because the dash already says "path" and the
     * colour was doing the job twice.
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
     * A line layer whose width is interpolated across zoom.
     *
     * Every road layer needs this and the expression is six nested arrays, so it is worth
     * one helper: a fixed-width road is a thread at z10 and a ribbon at z17.
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
     * A text layer.
     *
     * `text-font` is deliberately a single-element stack. MapLibre joins the array with
     * commas and asks for a directory of exactly that name, so `["A","B"]` requests a
     * folder literally called `A,B` - which does not exist, and the failure is silent
     * text-free rendering rather than an error.
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
     * `["match", ["get","kind"], [values...], true, false]`.
     *
     * Not `["in", ...]`, which is the trap here: the modern `in` operator takes exactly a
     * needle and one haystack, while the *legacy* filter syntax spells the same idea
     * `["in", "kind", "a", "b"]` with a bare property name. Writing one with the other's
     * shape produces a filter that is silently wrong rather than an error, and the symptom
     * is an empty layer. `match` has one spelling and means it.
     */
    private fun kindIsOneOf(vararg kinds: String): JSONArray = JSONArray()
        .put("match")
        .put(JSONArray().put("get").put("kind"))
        .put(JSONArray().apply { kinds.forEach(::put) })
        .put(true)
        .put(false)

    /**
     * `["==", ["geometry-type"], type]`.
     *
     * Needed because a source layer is not one shape: `water` holds lakes as polygons and
     * rivers as lines in the same layer, and each needs a different kind of layer to draw
     * it correctly.
     */
    private fun isGeometry(type: String): JSONArray = JSONArray()
        .put("==")
        .put(JSONArray().put("geometry-type"))
        .put(type)

    /**
     * `["within", <polygon>]` - true for features that fall inside the archive's box.
     *
     * Labels need this and the fills do not, because the two are hidden by different
     * means. Everything with an area is covered by the mask painted over the ground
     * outside; text cannot be, because the mask has to sit *below* the labels for the
     * routes to stay below them too, and that ordering leaves names floating over graph
     * paper with nothing under them. Filtering the features themselves sidesteps the
     * stacking order entirely.
     *
     * The box, not the true coverage: `within` takes one polygon, and a shape of several
     * hundred tile runs is not that. So on an extract cut from a drawn polygon a name can
     * still appear in an empty corner - a smaller wrong than a name with no map under it.
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

    /** Upgraded to graph paper once the pattern image exists. See `addGridBackground`. */
    const val BACKGROUND_LAYER = "background"

    /** One source per imported map, whatever format each turns out to be. */
    private fun sourceId(index: Int): String = "basemap-$index"

    /**
     * The one font shipped, and the directory name under `assets/glyphs` it must match
     * exactly, spaces included.
     *
     * Only the Latin ranges are bundled - 0-255, 256-511 and the general punctuation at
     * 8192-8447, about 280 KB between them against 6.2 MB for the complete set of 256.
     * A map of somewhere written in another script will render its names blank, and the
     * fix is to drop that range's .pbf into the same folder. CJK is handled separately:
     * the map view asks the device for those glyphs rather than bundling them.
     */
    private const val LABEL_FONT = "Noto Sans Regular"
}

/** `#rrggbb`, which is the only colour spelling worth emitting into a style. */
private fun Color.css(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * A step away from the background, for deriving a palette from one surface colour.
 *
 * The `dark` flag is the whole point. The previous version always stepped towards black,
 * which is correct on a light theme and exactly wrong on a dark one - there the ground is
 * already near-black, so every "darker" road was a road painted the colour of the thing
 * it was drawn on. Contrast is a direction, not a subtraction.
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
