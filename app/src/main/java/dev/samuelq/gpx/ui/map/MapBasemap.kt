package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.data.map.OfflineMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oscim.layers.Layer
import org.oscim.layers.tile.vector.OsmTileLayer
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.layers.vector.geometries.LineDrawable
import org.oscim.layers.vector.geometries.RectangleDrawable
import org.oscim.layers.vector.geometries.Style
import org.oscim.map.Map
import org.oscim.map.Viewport
import org.oscim.renderer.MapRenderer
import org.oscim.theme.IRenderTheme
import org.oscim.theme.ThemeLoader

/** The basemap as one thing to put up and take down. Tiles are null with no map shown. */
internal class Basemap(
    val land: OverlayLayer,
    val outline: OverlayLayer,
    val tiles: OsmTileLayer?,
    val labels: LabelLayer?,
    val mask: OverlayLayer?,
    val theme: IRenderTheme?,
) {
    val all: List<Layer> get() = listOfNotNull(land, outline, tiles, labels, mask)
}

/** The colours a basemap is drawn in, from the app theme. */
internal class BasemapColors(val background: Color, val land: Color, val label: Color)

/**
 * Puts [maps] under the routes: land, tiles, labels, the mask past their edges and the
 * dashed outline of each. [onAttached] hears each stage as it goes up, so a build cancelled
 * halfway can still be taken down with [detach].
 */
internal suspend fun Map.attachBasemap(
    maps: List<OfflineMap>,
    colors: BasemapColors,
    density: Density,
    onAttached: (Basemap) -> Unit,
) {
    // VTM fails the whole source if any one file won't open, so each is tried alone
    // and a broken one is left out rather than blanking the rest.
    val (shown, theme) = withContext(Dispatchers.IO) {
        val shown = maps.filter { it.opens() }
        val theme = if (shown.isEmpty()) null else ThemeLoader.load(
            GeneratedRenderTheme(
                MapRenderTheme.xml(land = colors.land, label = colors.label, background = colors.background)
            )
        )
        shown to theme
    }

    // Land under the tiles, not painted by them: the render theme's background is
    // transparent so ground no imported file covers reads as empty rather than as land.
    val land = OverlayLayer(this)
    val outline = OverlayLayer(this)
    layers().add(land, LayerGroup.Land.ordinal)
    layers().add(outline, LayerGroup.Outline.ordinal)
    onAttached(Basemap(land, outline, null, null, null, null))

    val landStyle = Style.builder().fillColor(colors.land.toArgb()).fillAlpha(1f)
        .strokeColor(TRANSPARENT).build()
    val outlineStyle = with(density) {
        Style.builder()
            .strokeColor(colors.label.copy(alpha = COVERAGE_OPACITY).toArgb())
            .strokeWidth(COVERAGE_WIDTH_DP.dp.toPx())
            .stipple(COVERAGE_DASH_DP.dp.roundToPx()).stippleColor(TRANSPARENT).stippleWidth(1f)
            .fixed(true)
            .build()
    }
    shown.forEach {
        land.add(boxDrawable(it, landStyle))
        outline.add(outlineDrawable(it, outlineStyle))
    }
    land.update()
    outline.update()

    if (theme == null) {
        MapRenderer.setBackgroundColor(colors.background.toArgb())
        updateMap(true)
        return
    }

    val source = ClippedMapSource(shown)
    val tiles = OsmTileLayer(this, Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL)
    if (!tiles.setTileSource(source)) {
        source.close()
        theme.dispose()
        return
    }
    // Against the full zoom range, not the camera's: the label layer copies the
    // viewport's limits at construction, throws if they are narrower than its own, and
    // places no labels outside them - and the camera's limits change with the extent.
    val labels = viewport().withFullZoomRange { LabelLayer(this, tiles) }
    // The source already cuts each file to its box; this covers what still overhangs
    // it, like half a road's width at the edge.
    val mask = OverlayLayer(this)
    layers().add(tiles, LayerGroup.Tiles.ordinal)
    layers().add(labels, LayerGroup.Labels.ordinal)
    layers().add(mask, LayerGroup.Mask.ordinal)
    onAttached(Basemap(land, outline, tiles, labels, mask, theme))
    outsideDrawables(shown, colors.background).forEach { mask.add(it) }
    mask.update()
    // Also clears to the theme's map-background-outside, which is the screen background.
    setTheme(theme)
}

internal fun Map.detach(basemap: Basemap) {
    basemap.all.forEach { layers().remove(it); it.onDetach() }
    basemap.theme?.dispose()
}

/** A map's own box, filled - the ground it actually covers. */
private fun boxDrawable(map: OfflineMap, style: Style): RectangleDrawable {
    val h = map.header
    return RectangleDrawable(h.minLatitude, h.minLongitude, h.maxLatitude, h.maxLongitude, style)
}

/**
 * Everywhere but the maps' own boxes, in the background colour, as plain rectangles: the
 * world cut along every box edge, keeping the cells no box covers. Not one polygon with
 * holes - VTM fills the holes in.
 */
private fun outsideDrawables(maps: List<OfflineMap>, background: Color): List<RectangleDrawable> {
    val style = Style.builder().fillColor(background.toArgb()).fillAlpha(1f).strokeColor(TRANSPARENT).build()
    // Not the whole world: the camera is penned to the extent and can't zoom out past it,
    // so a few spans' margin always covers the screen, and world-sized rectangles were
    // sometimes not drawn at all.
    val extent = extentOf(emptyList(), null, maps) ?: return emptyList()
    val outer = extent.padded(MASK_MARGIN_SPANS)
    val latitudes = (maps.flatMap { listOf(it.header.minLatitude, it.header.maxLatitude) } +
        listOf(outer.minLatitude, outer.maxLatitude)).distinct().sorted()
    val longitudes = (maps.flatMap { listOf(it.header.minLongitude, it.header.maxLongitude) } +
        listOf(outer.minLongitude, outer.maxLongitude)).distinct().sorted()

    val out = ArrayList<RectangleDrawable>()
    for (i in 0 until latitudes.size - 1) for (j in 0 until longitudes.size - 1) {
        val midLatitude = (latitudes[i] + latitudes[i + 1]) / 2
        val midLongitude = (longitudes[j] + longitudes[j + 1]) / 2
        val covered = maps.any {
            val h = it.header
            midLatitude in h.minLatitude..h.maxLatitude && midLongitude in h.minLongitude..h.maxLongitude
        }
        if (!covered) out.add(RectangleDrawable(latitudes[i], longitudes[j], latitudes[i + 1], longitudes[j + 1], style))
    }
    return out
}

/** The dashed boundary marking where an imported file's detail stops. */
private fun outlineDrawable(map: OfflineMap, style: Style): LineDrawable {
    val h = map.header
    return LineDrawable(
        doubleArrayOf(
            h.minLongitude, h.minLatitude,
            h.maxLongitude, h.minLatitude,
            h.maxLongitude, h.maxLatitude,
            h.minLongitude, h.maxLatitude,
            h.minLongitude, h.minLatitude,
        ),
        style,
    )
}

private const val TRANSPARENT = 0

/** How far past the maps the outside mask reaches, in spans of their extent. */
private const val MASK_MARGIN_SPANS = 3.0

/** Visible as a boundary, not as a feature of the landscape. */
private const val COVERAGE_WIDTH_DP = 1.2f

private const val COVERAGE_DASH_DP = 4f

private const val COVERAGE_OPACITY = 0.55f
