package dev.samuelq.gpx.ui.map

import android.graphics.Paint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.map.OfflineMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oscim.android.MapView
import org.oscim.android.canvas.AndroidBitmap
import org.oscim.core.BoundingBox
import org.oscim.core.Box
import org.oscim.core.GeoPoint
import org.oscim.core.MapElement
import org.oscim.core.MapPosition
import org.oscim.core.MercatorProjection
import org.oscim.core.Tile
import org.oscim.event.Gesture
import org.oscim.event.GestureListener
import org.oscim.event.MotionEvent
import org.oscim.layers.Layer
import org.oscim.layers.marker.ItemizedLayer
import org.oscim.layers.marker.MarkerInterface
import org.oscim.layers.marker.MarkerItem
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.layers.tile.MapTile
import org.oscim.layers.tile.vector.OsmTileLayer
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.layers.vector.VectorLayer
import org.oscim.layers.vector.geometries.LineDrawable
import org.oscim.layers.vector.geometries.RectangleDrawable
import org.oscim.layers.vector.geometries.Style
import org.oscim.map.Map
import org.oscim.map.Viewport
import org.oscim.renderer.MapRenderer
import org.oscim.theme.IRenderTheme
import org.oscim.theme.ThemeFile
import org.oscim.theme.ThemeLoader
import org.oscim.theme.XmlRenderThemeMenuCallback
import org.oscim.theme.XmlThemeResourceProvider
import org.oscim.tiling.ITileDataSink
import org.oscim.tiling.ITileDataSource
import org.oscim.tiling.OverzoomTileDataSource
import org.oscim.tiling.QueryResult
import org.oscim.tiling.TileDataSink
import org.oscim.tiling.source.mapfile.MapFile
import org.oscim.tiling.source.mapfile.MapFileTileSource
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource
import org.oscim.utils.geom.TileClipper
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * One track as the map should draw it: the positions themselves, in degrees. The map
 * does its own projecting.
 */
@Immutable
class RouteOverlay(
    val trackId: Long,
    val points: List<TrackPoint>,
    /** Indices into [points] where a new polyline starts. Never drawn across. */
    val segmentStartIndices: IntArray,
    val color: Color,
) {
    /**
     * The box this route occupies, measured once and kept - four numbers rather than
     * re-walking every position on every change. Lazy, since most overlays are never
     * framed against. `PUBLICATION`, not a lock: a race just computes the same answer twice.
     */
    val bounds: RouteBounds? by lazy(LazyThreadSafetyMode.PUBLICATION) { RouteBounds.of(points) }
}

/** A route's extent, as the four numbers a camera fit actually needs. */
@Immutable
class RouteBounds(
    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
) {
    companion object {
        /** Null for no points at all: an empty route has no box, not a box of zero size. */
        fun of(points: List<TrackPoint>): RouteBounds? {
            if (points.isEmpty()) return null
            var south = Double.POSITIVE_INFINITY
            var west = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            // Indexed doubles, not objects: this touches every position of every drawn
            // route, so it allocates nothing.
            for (i in points.indices) {
                val point = points[i]
                if (point.latitude < south) south = point.latitude
                if (point.latitude > north) north = point.latitude
                if (point.longitude < west) west = point.longitude
                if (point.longitude > east) east = point.longitude
            }
            return RouteBounds(south, west, north, east)
        }
    }
}

/**
 * Where the camera is, in the three numbers worth remembering across a screen this
 * composable doesn't survive - navigating away and back recomposes it from scratch, and
 * without this the camera re-fits from nothing every time, discarding wherever the user
 * had actually panned to.
 */
@Immutable
data class CameraSnapshot(val latitude: Double, val longitude: Double, val zoom: Double)

/**
 * Routes over an offline basemap, or over nothing if none is imported.
 *
 * VTM renders from the map files directly and has no tile server behind it, so there is
 * nothing here that could reach the network even if the permission existed.
 *
 * Every layer lives in a fixed group (see [LayerGroup]), so each can be swapped on its own
 * without disturbing the stacking order: the tile layer is expensive to recreate, and the
 * routes above it change every few seconds during a recording.
 */
@Composable
fun OfflineMapCanvas(
    /** Drawn in this order, the last on top. */
    routes: List<RouteOverlay>,
    basemaps: List<OfflineMap>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /**
     * True while [routes] might still be missing tracks whose geometry hasn't finished
     * loading. Only matters for the cold-start frame: without it, an empty [routes] on the
     * first frame would be read as "no tracks, frame the basemap instead" and latch there,
     * with no second chance once the real tracks land a moment later.
     */
    tracksLoading: Boolean = false,
    /**
     * The recording drawing itself, kept apart from [routes] since it changes every few
     * seconds and they don't - growth this way costs only its own geometry.
     */
    liveRoute: RouteOverlay? = null,
    /** Which route the sheet is showing. */
    focusedTrackId: Long? = null,
    /** Highlighted point within [focusedTrackId]'s route, as an index into its points. */
    selectedIndex: Int? = null,
    markerColor: Color = Color.Unspecified,
    /** The track whose last point is where the recorder is standing, or null. */
    puckTrackId: Long? = null,
    puckColor: Color = Color.Unspecified,
    /**
     * Drawn on whichever track they belong to - the caller has already narrowed this to
     * the focused track's own and the live recording's, since a waypoint is a note on one
     * ride, not a landmark on the map itself.
     */
    waypoints: List<Waypoint> = emptyList(),
    onSelect: (trackId: Long, index: Int) -> Unit = { _, _ -> },
    onSelectNothing: () -> Unit = {},
    onSelectWaypoint: (Waypoint) -> Unit = {},
    /** A waypoint whose pin's screen position goes to [onFollowedWaypointMove] on every camera move. */
    followedWaypoint: Waypoint? = null,
    /**
     * The centre of the pin's head, [WaypointPinHeadRadius] across - not the tip, and not the
     * tap. Also called as soon as [followedWaypoint] changes, before it is next laid out.
     */
    onFollowedWaypointMove: (Offset) -> Unit = {},
    /** Space kept clear of routes when framing, for the sheet and the controls. */
    contentPadding: PaddingValues = PaddingValues(),
    /**
     * How much of the bottom a sheet covers, zero with none open. Panning is measured from
     * its top edge, so nothing can be stranded underneath it.
     */
    sheetHeight: Dp = 0.dp,
    /** The same for a panel down the start edge, as landscape shows the track in. */
    panelWidth: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    landColor: Color = Color.Unspecified,
    labelColor: Color = Color.Unspecified,
    /** Reports how far a screen pixel spans on the ground, for the scale bar. */
    onScaleChange: (metersPerPixel: Double) -> Unit = {},
    /**
     * Where to put the camera on the cold-start frame, if the caller already knows - a
     * screen this composable doesn't survive navigating away from. Takes priority over
     * fitting to tracks or maps; only a fresh instance with nothing remembered yet falls
     * back to that.
     */
    initialCamera: CameraSnapshot? = null,
    /** Reports the camera's own position on every move, for [initialCamera] next time. */
    onCameraChange: (CameraSnapshot) -> Unit = {},
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val mapView = rememberMapViewWithLifecycle()
    val map = mapView.map()

    var basemap by remember { mutableStateOf<Basemap?>(null) }
    // Framed once, when there's first something to frame - re-fitting on every route-set
    // change (every few seconds during a recording) would yank the map out from under a pan.
    var hasFramed by remember { mutableStateOf(false) }
    // Observed from layout rather than read off the view: the first composition runs before
    // any layout, and a camera fitted to 0 x 0 is a map stuck at whole-world zoom.
    var viewSize by remember { mutableStateOf<IntSize?>(null) }

    val currentRoutes by rememberUpdatedState(routes)
    val currentLiveRoute by rememberUpdatedState(liveRoute)
    val currentWaypoints by rememberUpdatedState(waypoints)
    val select by rememberUpdatedState(onSelect)
    val selectNothing by rememberUpdatedState(onSelectNothing)
    val selectWaypoint by rememberUpdatedState(onSelectWaypoint)
    val currentFollowedWaypoint by rememberUpdatedState(followedWaypoint)
    val reportWaypointAt by rememberUpdatedState(onFollowedWaypointMove)
    val reportScale by rememberUpdatedState(onScaleChange)
    val reportCamera by rememberUpdatedState(onCameraChange)

    // Resolved here, where there is a density to resolve it against: a fingertip is a
    // physical size, and 44 raw pixels is a third of one on a modern screen.
    val tapReach = remember(density) { with(density) { TAP_REACH_DP.dp.toPx() } }
    val pinHeadRadius = remember(density) { with(density) { WaypointPinHeadRadius.toPx() } }
    val pinHeadLift = remember(density) { with(density) { Offset(0f, PIN_TIP_LENGTH_DP.dp.toPx()) } }
    val followMargin = remember(density) { with(density) { FOLLOW_MARGIN_DP.dp.roundToPx() } }

    val insets = remember(contentPadding, layoutDirection, density) {
        with(density) {
            Insets(
                left = contentPadding.calculateStartPadding(layoutDirection).roundToPx(),
                top = contentPadding.calculateTopPadding().roundToPx(),
                right = contentPadding.calculateEndPadding(layoutDirection).roundToPx(),
                bottom = contentPadding.calculateBottomPadding().roundToPx(),
            )
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
            .onSizeChanged { viewSize = it }
            .semantics { this.contentDescription = contentDescription },
    )

    // --- The stack, assembled once --------------------------------------------------

    val routeLayer = remember(map) { LineLayer(map) }
    val traceLayer = remember(map) { LineLayer(map) }
    val markerLayer = remember(map) {
        ItemizedLayer(map, MarkerSymbol(AndroidBitmap(1, 1, 0), MarkerSymbol.HotspotPlace.CENTER))
    }

    DisposableEffect(map) {
        val layers = map.layers()
        LayerGroup.entries.forEach { layers.addGroup(it.ordinal) }
        // Taps are offered top layer first, so the tap layer gets its look before anything
        // drawn under it.
        layers.add(routeLayer.layer, LayerGroup.Routes.ordinal)
        layers.add(traceLayer.layer, LayerGroup.Trace.ordinal)
        layers.add(markerLayer, LayerGroup.Markers.ordinal)
        layers.add(
            TapLayer(map) { x, y ->
                // Checked first: a waypoint sits on top of its own track's line, and
                // reading its note is a more specific answer than scrubbing to that point.
                val waypointHit = pickWaypoint(x, y, map, currentWaypoints, pinHeadRadius, pinHeadLift.y)
                if (waypointHit != null) {
                    selectWaypoint(waypointHit)
                } else {
                    val hit = pick(x, y, map, currentRoutes, currentLiveRoute, tapReach)
                    if (hit == null) selectNothing() else select(hit.first, hit.second)
                }
            },
            LayerGroup.Tap.ordinal,
        )
        // North-up, flat: a route drawn north-up is a shape people recognise.
        map.eventLayer.enableRotation(false)
        map.eventLayer.enableTilt(false)
        onDispose { }
    }

    // --- Position reporting ---------------------------------------------------------

    // Before layout, so a newly followed pin's first frame is already placed - it may have
    // come from a scrub, with no camera move to report it.
    SideEffect {
        followedWaypoint?.let { reportWaypointAt(map.screenPosition(it.point) - pinHeadLift) }
    }

    DisposableEffect(map) {
        val listener = Map.UpdateListener { _, position ->
            reportScale(MercatorProjection.groundResolution(position))
            currentFollowedWaypoint?.let { reportWaypointAt(map.screenPosition(it.point, position) - pinHeadLift) }
            // Not before the first frame is placed. Until then the position is VTM's own
            // default - the whole world - and remembering that as "where the user was" is
            // both wrong and, since it feeds back in as initialCamera, self-fulfilling.
            if (hasFramed) {
                reportCamera(CameraSnapshot(position.latitude, position.longitude, position.zoom))
            }
        }
        map.events.bind(listener)
        onDispose { map.events.unbind(listener) }
    }

    // --- The basemap ----------------------------------------------------------------

    // Keyed on the shown files and the theme colours - either means a new render theme and
    // a new data source, which is the one thing worth rebuilding the tile layer for.
    LaunchedEffect(map, basemaps, backgroundColor, landColor, labelColor) {
        val layers = map.layers()
        basemap?.let { current ->
            current.all.forEach { layers.remove(it); it.onDetach() }
            current.theme?.dispose()
        }
        basemap = null

        // VTM fails the whole source if any one file won't open, so each is tried alone
        // and a broken one is left out rather than blanking the rest.
        val (shown, theme) = withContext(Dispatchers.IO) {
            val shown = basemaps.filter { it.opens() }
            val theme = if (shown.isEmpty()) null else ThemeLoader.load(
                GeneratedRenderTheme(
                    MapRenderTheme.xml(land = landColor, label = labelColor, background = backgroundColor)
                )
            )
            shown to theme
        }

        // Land under the tiles, not painted by them: the render theme's background is
        // transparent so ground no imported file covers reads as empty rather than as land.
        val land = OverlayLayer(map)
        val outline = OverlayLayer(map)
        layers.add(land, LayerGroup.Land.ordinal)
        layers.add(outline, LayerGroup.Outline.ordinal)
        basemap = Basemap(land, outline, null, null, null, null)

        val landStyle = Style.builder().fillColor(landColor.toArgb()).fillAlpha(1f)
            .strokeColor(TRANSPARENT).build()
        val outlineStyle = with(density) {
            Style.builder()
                .strokeColor(labelColor.copy(alpha = COVERAGE_OPACITY).toArgb())
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
            MapRenderer.setBackgroundColor(backgroundColor.toArgb())
            map.updateMap(true)
            return@LaunchedEffect
        }

        val source = ClippedMapSource(shown)
        val tiles = OsmTileLayer(map, Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL)
        if (!tiles.setTileSource(source)) {
            source.close()
            theme.dispose()
            return@LaunchedEffect
        }
        // Against the full zoom range, not the camera's: the label layer copies the
        // viewport's limits at construction, throws if they are narrower than its own, and
        // places no labels outside them - and the camera's limits change with the extent.
        val labels = map.viewport().withFullZoomRange { LabelLayer(map, tiles) }
        // The source already cuts each file to its box; this covers what still overhangs
        // it, like half a road's width at the edge.
        val mask = OverlayLayer(map)
        layers.add(tiles, LayerGroup.Tiles.ordinal)
        layers.add(labels, LayerGroup.Labels.ordinal)
        layers.add(mask, LayerGroup.Mask.ordinal)
        basemap = Basemap(land, outline, tiles, labels, mask, theme)
        outsideDrawables(shown, backgroundColor).forEach { mask.add(it) }
        mask.update()
        // Also clears to the theme's map-background-outside, which is the screen background.
        map.setTheme(theme)
    }

    // --- What is drawn --------------------------------------------------------------

    val routeStyles = remember(density) { RouteStyles(density) }

    // Each of these builds its lines off the main thread - a long ride is a few hundred
    // thousand coordinates, which don't belong on the frame the user sees.
    LaunchedEffect(routeLayer, routes) {
        val built = withContext(Dispatchers.Default) { routes.toLines(routeStyles) }
        routeLayer.replaceWith(built)
    }

    // Its own effect: runs every few seconds for the length of a ride, touching nothing else.
    LaunchedEffect(traceLayer, liveRoute) {
        val built = withContext(Dispatchers.Default) {
            listOfNotNull(liveRoute).toLines(routeStyles)
        }
        traceLayer.replaceWith(built)
    }

    // Built once per colour, not per selection: scrubbing a chart moves the marker on every
    // frame, and a new bitmap each time is a new texture upload each time.
    val darkTheme = isSystemInDarkTheme()
    val symbols = remember(markerColor, puckColor, darkTheme, density) {
        MarkerSymbols(markerColor, puckColor, darkTheme, density)
    }

    LaunchedEffect(
        markerLayer, symbols, routes, liveRoute, puckTrackId, focusedTrackId, selectedIndex, waypoints,
    ) {
        // The recording is the usual answer for the puck and isn't in `routes`, so it is
        // asked first.
        val puckAt = (liveRoute?.takeIf { it.trackId == puckTrackId }
            ?: routes.firstOrNull { it.trackId == puckTrackId })
            ?.points?.lastOrNull()
        val markerAt = routes.firstOrNull { it.trackId == focusedTrackId }
            ?.points?.getOrNull(selectedIndex ?: -1)

        val items = buildList<MarkerInterface> {
            // First, so the puck and the scrub marker - both about right now, one way or
            // another - are never underneath a waypoint dropped earlier in the same ride.
            waypoints.forEach { add(marker(it.point, symbols.waypoint)) }
            if (puckAt != null) add(marker(puckAt, symbols.puck))
            // Last, so the point being read about is never underneath anything.
            if (markerAt != null) add(marker(markerAt, symbols.marker))
        }
        markerLayer.removeAllItems(false)
        markerLayer.addItems(items)
        markerLayer.update()
        map.render()
    }

    // --- Where it is looked at from -------------------------------------------------

    // Every track and every shown map, unioned - the box the camera is kept inside and the
    // zoom-out floor are both about what's there at all, not what the view opens on.
    val extent = remember(routes, liveRoute, basemaps) {
        extentOf(routes, liveRoute, basemaps)
    }

    LaunchedEffect(map, extent, basemaps) {
        if (extent == null) return@LaunchedEffect
        val viewport = map.viewport()

        // A cap on the way in - magnifying a file's deepest zoom many times over draws
        // detail that does not exist, convincingly.
        viewport.setMaxZoomLevel(basemaps.maxOfOrNull { it.maxViewZoom } ?: DEFAULT_MAX_ZOOM)
        map.updateMap(true)
    }

    // Zooming out stops once everything fits edge to edge on whichever axis is tighter -
    // beyond that is nothing but flat background. Against the whole view, not the
    // uncovered part: it is a limit on scale, and the sheet comes and goes.
    LaunchedEffect(map, extent, basemaps, viewSize) {
        if (extent == null) return@LaunchedEffect
        val size = viewSize ?: return@LaunchedEffect
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val viewport = map.viewport()
        val floor = MapPosition().apply { setByBoundingBox(extent, size.width, size.height) }.scale
        viewport.setMinScale(minOf(floor, viewport.maxScale))
    }

    // Panning stops once an edge of everything there is reaches the screen's, and an axis
    // it all fits on is held centred - so fully zoomed out, zooming is the only move left.
    // Edges a sheet or panel covers are measured from that instead, so anything under one
    // can be pulled out. Re-checked on every camera move, since the allowed range depends
    // on the scale.
    val cover = with(density) {
        val panel = panelWidth.roundToPx()
        Cover(
            left = if (layoutDirection == LayoutDirection.Ltr) panel else 0,
            right = if (layoutDirection == LayoutDirection.Rtl) panel else 0,
            bottom = sheetHeight.roundToPx(),
        )
    }
    val currentExtent by rememberUpdatedState(extent)
    val currentCover by rememberUpdatedState(cover)
    DisposableEffect(map) {
        val listener = Map.UpdateListener { _, _ ->
            currentExtent?.let { map.keepInView(it, currentCover) }
        }
        map.events.bind(listener)
        onDispose { map.events.unbind(listener) }
    }
    LaunchedEffect(map, extent, viewSize, cover) {
        extent?.let { map.keepInView(it, cover) }
    }

    // Only reached once per process at most: the moment a camera is ever remembered (see
    // initialCamera), every later visit to this screen - even after navigating away and
    // back - skips straight past it. Tracks first, since that's almost always what someone
    // opened the app to look at; falls back to the shown maps' own extent only once
    // tracksLoading says there is nothing recorded or imported yet.
    val initialExtent = remember(routes, liveRoute, basemaps) {
        extentOf(routes, liveRoute, emptyList()) ?: extentOf(emptyList(), null, basemaps)
    }

    // Read once, not per recomposition: the camera is republished as the map moves, so
    // keying the framing effect on it would let the map's own position come back around as
    // the place it is supposed to be restored to.
    val rememberedCamera = remember { initialCamera }

    LaunchedEffect(map, initialExtent, tracksLoading, insets, viewSize) {
        if (hasFramed) return@LaunchedEffect

        val remembered = rememberedCamera
        if (remembered != null) {
            map.setMapPosition(
                MapPosition().setPosition(remembered.latitude, remembered.longitude).setZoom(remembered.zoom)
            )
            hasFramed = true
            return@LaunchedEffect
        }
        if (tracksLoading) return@LaunchedEffect
        val target = initialExtent ?: return@LaunchedEffect
        val size = viewSize ?: return@LaunchedEffect
        val usable = size.usable(insets) ?: return@LaunchedEffect

        map.setMapPosition(fit(target, size, usable, insets))
        hasFramed = true
    }

    // Scrubbing a chart moves the marker; moves the camera the least it can rather than
    // re-centring, which would turn reading a chart into a ride through a moving map.
    LaunchedEffect(map, focusedTrackId, selectedIndex, insets) {
        val at = currentRoutes.firstOrNull { it.trackId == focusedTrackId }
            ?.points?.getOrNull(selectedIndex ?: -1) ?: return@LaunchedEffect
        map.nudgeIntoView(at.latitude, at.longitude, insets, followMargin)
    }
}

/**
 * The stacking order, bottom first. VTM keeps each group's layers together however late
 * they are added, so a rebuilt basemap lands back under the routes rather than on top.
 *
 * Names over the routes, haloed, so a road stays readable where a track runs along it.
 * That puts them over the mask too, which is why [ClippedMapSource] drops what is past a
 * file's edge before a name can be placed on it.
 */
private enum class LayerGroup { Land, Tiles, Mask, Outline, Routes, Trace, Labels, Markers, Tap }

/** Runs [block] with VTM's own zoom limits in place, then puts the camera's back. */
private inline fun <T> Viewport.withFullZoomRange(block: () -> T): T {
    val min = minScale
    val max = maxScale
    setMinZoomLevel(Viewport.MIN_ZOOM_LEVEL)
    setMaxZoomLevel(Viewport.MAX_ZOOM_LEVEL)
    try {
        return block()
    } finally {
        minScale = min
        maxScale = max
    }
}

/** Pixels kept clear on each edge, for whatever is floating over the map. */
private class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The basemap as one thing to put up and take down. Tiles are null with no map shown. */
private class Basemap(
    val land: OverlayLayer,
    val outline: OverlayLayer,
    val tiles: OsmTileLayer?,
    val labels: LabelLayer?,
    val mask: OverlayLayer?,
    val theme: IRenderTheme?,
) {
    val all: List<Layer> get() = listOfNotNull(land, outline, tiles, labels, mask)
}

/**
 * A [MapView] that follows the composition's lifecycle - it owns a GL thread and its
 * surface, and leaking one leaks both.
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}

/** The generated theme, handed to VTM as the stream it insists on. */
private class GeneratedRenderTheme(xml: String) : ThemeFile {
    private val bytes = xml.toByteArray()
    private var menuCallback: XmlRenderThemeMenuCallback? = null
    private var resourceProvider: XmlThemeResourceProvider? = null
    private var mapsforgeTheme = false

    override fun getMenuCallback(): XmlRenderThemeMenuCallback? = menuCallback
    override fun setMenuCallback(callback: XmlRenderThemeMenuCallback?) { menuCallback = callback }
    override fun getRelativePathPrefix(): String = ""
    override fun getRenderThemeAsStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getResourceProvider(): XmlThemeResourceProvider? = resourceProvider
    override fun setResourceProvider(provider: XmlThemeResourceProvider?) { resourceProvider = provider }
    // Set by the parser itself on seeing the mapsforge namespace.
    override fun isMapsforgeTheme(): Boolean = mapsforgeTheme
    override fun setMapsforgeTheme(value: Boolean) { mapsforgeTheme = value }
}

/** A single tap anywhere on the map, in screen pixels. Double taps still zoom. */
private class TapLayer(map: Map, private val onTap: (x: Float, y: Float) -> Unit) :
    Layer(map), GestureListener {
    override fun onGesture(g: Gesture, e: MotionEvent): Boolean {
        if (g !is Gesture.Tap) return false
        onTap(e.x, e.y)
        return true
    }
}

// --- Drawing ----------------------------------------------------------------------

/**
 * One style per colour, shared by every line drawn with it. VectorLayer batches
 * consecutive lines of the *same* style into one draw, so a style per line would cost a
 * draw call each.
 */
private class RouteStyles(density: Density) {
    private val width = with(density) { ROUTE_WIDTH_DP.dp.toPx() }
    private val cache = HashMap<Int, Style>()

    @Synchronized
    fun of(color: Color): Style = cache.getOrPut(color.toArgb()) {
        Style.builder()
            .strokeColor(color.toArgb())
            .strokeWidth(width)
            .cap(org.oscim.backend.canvas.Paint.Cap.ROUND)
            .fixed(true)
            // Simplified to a pixel at the zoom it is drawn at: a long ride has far more
            // positions than the screen has pixels to show them with.
            .generalization(Style.GENERALIZATION_SMALL)
            .build()
    }
}

/**
 * Every route as lines, one per segment - the gap between segments is signal loss and
 * nothing should be drawn across it. Stacked in list order, the last on top.
 */
private fun List<RouteOverlay>.toLines(styles: RouteStyles): List<LineDrawable> {
    val out = ArrayList<LineDrawable>()
    forEachIndexed { priority, route ->
        // VTM draws by priority, higher later. Within one priority the order is whatever its
        // spatial index returns, not the order added, so each route gets its own.
        val style = styles.of(route.color)
        // Both producers hand these over ascending and starting at 0, so this neither
        // sorts nor dedupes - the data already carries that guarantee.
        val starts = route.segmentStartIndices
        val runs = if (starts.isEmpty()) 1 else starts.size

        for (i in 0 until runs) {
            val from = if (starts.isEmpty()) 0 else starts[i]
            val to = if (i + 1 < starts.size) starts[i + 1] else route.points.size
            // A single position isn't a line; still drawn as the puck if it is live.
            if (to - from < 2) continue

            val lonLat = DoubleArray((to - from) * 2)
            for (index in from until to) {
                val point = route.points[index]
                lonLat[(index - from) * 2] = point.longitude
                lonLat[(index - from) * 2 + 1] = point.latitude
            }
            out.add(LineDrawable(lonLat, style).also { it.priority = priority })
        }
    }
    return out
}

/**
 * A [VectorLayer] that keeps up with the camera. VTM recomputes one on camera events, but a
 * layer attached around the start-up framing move regularly missed it and went on showing
 * the whole-world view it was first computed for - the outside mask most visibly, which
 * left every map's surroundings unmasked until the camera next moved. So after any map
 * event, each layer checks whether its last pass was for the current camera, and goes
 * again until it was.
 */
private class OverlayLayer(private val owner: Map) : VectorLayer(owner) {
    private val drawnFor = MapPosition()
    private val current = MapPosition()
    @Volatile private var drawnValid = false
    @Volatile private var checking = false

    override fun processFeatures(t: Task, b: Box) {
        // Skipped by VTM while the view has no size; not a pass for any camera.
        if (b.xmin.isNaN()) return
        super.processFeatures(t, b)
        synchronized(drawnFor) { drawnFor.copy(t.position) }
        drawnValid = true
    }

    override fun onMapEvent(e: org.oscim.event.Event, pos: MapPosition) {
        super.onMapEvent(e, pos)
        if (!checking) {
            checking = true
            owner.postDelayed(::check, OVERLAY_CHECK_MS)
        }
    }

    private fun check() {
        owner.viewport().getMapPosition(current)
        val behind = synchronized(drawnFor) {
            !drawnValid || current.x != drawnFor.x || current.y != drawnFor.y || current.scale != drawnFor.scale
        }
        if (behind) {
            update()
            owner.postDelayed(::check, OVERLAY_CHECK_MS)
        } else {
            checking = false
        }
    }
}

/** A [VectorLayer] that remembers what it holds, since VTM offers no way to ask or to clear. */
private class LineLayer(map: Map) {
    val layer = OverlayLayer(map)
    private val drawn = ArrayList<LineDrawable>()

    /** Swaps every line for [next] and asks for one redraw. */
    fun replaceWith(next: List<LineDrawable>) {
        synchronized(layer) {
            drawn.forEach { layer.remove(it) }
            drawn.clear()
            next.forEach { layer.add(it) }
            drawn.addAll(next)
        }
        layer.update()
    }
}

/** The circles, drawn once per colour - see where [MarkerSymbols] is remembered. */
private class MarkerSymbols(marker: Color, puck: Color, darkTheme: Boolean, density: Density) {
    val marker: MarkerSymbol
    val puck: MarkerSymbol
    val waypoint: MarkerSymbol

    init {
        with(density) {
            val ringWidth = MARKER_RING_WIDTH_DP.dp.toPx()
            this@MarkerSymbols.marker = symbol(
                MARKER_RADIUS_DP.dp.toPx(), ringWidth, fill = marker, ring = MARKER_RING, halo = null, haloRadius = 0f,
            )
            // Bigger than the scrub marker and wearing a halo: one points at a moment in a
            // ride that's over, this is the only thing on screen about right now.
            this@MarkerSymbols.puck = symbol(
                PUCK_RADIUS_DP.dp.toPx(), ringWidth, fill = puck, ring = MARKER_RING,
                halo = puck.copy(alpha = PUCK_HALO_ALPHA), haloRadius = PUCK_HALO_RADIUS_DP.dp.toPx(),
            )
            // A pin, not a dot: a waypoint is a place someone marked, and the shape says so
            // on its own - the tip is the hotspot, so it points at the position exactly the
            // way the puck and the scrub marker sit centred on theirs.
            this@MarkerSymbols.waypoint = pin(
                PIN_RADIUS_DP.dp.toPx(), PIN_TIP_LENGTH_DP.dp.toPx(), ringWidth,
                fill = if (darkTheme) WAYPOINT_LIGHT_GREY else WAYPOINT_DARK_GREY,
                ring = if (darkTheme) WAYPOINT_DARK_GREY else MARKER_RING,
            )
        }
    }

    private fun symbol(
        radius: Float,
        ringWidth: Float,
        fill: Color,
        ring: Color,
        halo: Color?,
        haloRadius: Float,
    ): MarkerSymbol {
        val outer = maxOf(radius + ringWidth / 2, haloRadius)
        val size = kotlin.math.ceil(outer * 2).toInt() + 2
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val centre = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (halo != null) {
            paint.color = halo.toArgb()
            canvas.drawCircle(centre, centre, haloRadius, paint)
        }
        paint.color = fill.toArgb()
        canvas.drawCircle(centre, centre, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringWidth
        paint.color = ring.toArgb()
        canvas.drawCircle(centre, centre, radius, paint)
        return MarkerSymbol(AndroidBitmap(bitmap), MarkerSymbol.HotspotPlace.CENTER, false)
    }

    /**
     * A teardrop: a circle of [radius] tangent to two lines converging [tipLength] below its
     * centre, at the exact angle that meets the circle smoothly rather than a triangle
     * glued onto it. The tip is the hotspot - see [MarkerSymbol.HotspotPlace.BOTTOM_CENTER] -
     * so the point the pin actually marks is where the geometry says it is, not the middle
     * of the bitmap holding it.
     */
    private fun pin(radius: Float, tipLength: Float, ringWidth: Float, fill: Color, ring: Color): MarkerSymbol {
        // Full margin above and to the sides, for the stroke's overflow past the circle;
        // only half below, and a round join there rather than the default miter - a sharp
        // point mitred would spike well past the ring's own width.
        val topPad = ringWidth
        val bottomPad = ringWidth / 2
        val width = kotlin.math.ceil(2 * radius + 2 * topPad).toInt() + 1
        val height = kotlin.math.ceil(radius + tipLength + topPad + bottomPad).toInt() + 1
        val cx = width / 2f
        val cy = topPad + radius
        val tipY = cy + tipLength

        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val path = teardropPath(cx, cy, radius, tipY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = fill.toArgb()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = ringWidth
        paint.color = ring.toArgb()
        canvas.drawPath(path, paint)
        // Punched through rather than painted, so the map shows in it in either theme.
        paint.style = Paint.Style.FILL
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawCircle(cx, cy, radius * PIN_HOLE_RATIO, paint)
        return MarkerSymbol(AndroidBitmap(bitmap), MarkerSymbol.HotspotPlace.BOTTOM_CENTER, false)
    }
}

/**
 * A circle centred at ([cx],[cy]) with radius [radius], its outline replaced between the
 * two points tangent to a line down to ([cx],[tipY]) with the two tangent lines themselves -
 * the classic map pin, computed exactly rather than approximated with a fixed-width wedge.
 */
private fun teardropPath(cx: Float, cy: Float, radius: Float, tipY: Float): android.graphics.Path {
    val d = tipY - cy
    val angle = kotlin.math.acos((radius / d).coerceIn(-1f, 1f))
    val a1 = (Math.PI / 2).toFloat() - angle
    val a2 = (Math.PI / 2).toFloat() + angle
    val a1Degrees = Math.toDegrees(a1.toDouble()).toFloat()
    // The long way round, over the top of the circle - the short way is the wedge the
    // tangent lines replace.
    val sweepDegrees = -(360f - Math.toDegrees((2 * angle).toDouble()).toFloat())

    return android.graphics.Path().apply {
        moveTo(cx + radius * kotlin.math.cos(a1), cy + radius * kotlin.math.sin(a1))
        arcTo(
            android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            a1Degrees,
            sweepDegrees,
        )
        lineTo(cx, tipY)
        close()
    }
}

private fun marker(at: TrackPoint, symbol: MarkerSymbol) =
    MarkerItem("", "", GeoPoint(at.latitude, at.longitude)).apply { marker = symbol }

/** Whether VTM can read this file at all. Opened and closed again; the tile layer reopens it. */
private fun OfflineMap.opens(): Boolean = MapFileTileSource().run {
    setMapFile(file.path)
    open().isSuccess.also { close() }
}

/**
 * How far in the camera may go over this map. Measured from the deepest zoom the file
 * *stores*, not the one it advertises: a published file keeps tiles at z14 and claims
 * z21, and everything past the base zoom is that tile's geometry drawn bigger.
 */
private val OfflineMap.maxViewZoom: Int
    get() = (header.baseZoom + OVERZOOM_ALLOWANCE).coerceAtMost(Viewport.MAX_ZOOM_LEVEL)

/** A map's own box, filled - the ground it actually covers. */
private fun boxDrawable(map: OfflineMap, style: Style): RectangleDrawable {
    val h = map.header
    return RectangleDrawable(h.minLatitude, h.minLongitude, h.maxLatitude, h.maxLongitude, style)
}

/**
 * The maps as one source, each file's data cut to its own box. A file's low zooms are
 * whole tiles tens of kilometres wide, so it carries lakes, roads and towns well past its
 * box - painted over by the mask, but names are drawn above that, and a town floating on
 * empty ground reads as broken.
 *
 * Queries the files itself rather than through [MultiMapFileTileSource]'s own data source,
 * which hands every file the same sink and so can't say which box an element belongs to.
 * No de-duplication across files: they don't overlap, and what two share past their
 * edges is cut away here.
 */
private class ClippedMapSource(maps: List<OfflineMap>) : MultiMapFileTileSource() {
    private val files = maps.map { map ->
        val h = map.header
        val source = MapFileTileSource().apply { setMapFile(map.file.path) }
        add(source)
        source to BoundingBox(h.minLatitude, h.minLongitude, h.maxLatitude, h.maxLongitude)
    }

    override fun getDataSource(): ITileDataSource {
        val opened = files.mapNotNull { (source, box) ->
            try {
                MapFile(source) to box
            } catch (_: IOException) {
                null
            }
        }
        return OverzoomTileDataSource(ClippedMapData(opened), overZoom)
    }
}

private class ClippedMapData(private val files: List<Pair<MapFile, BoundingBox>>) : ITileDataSource {
    override fun query(tile: MapTile, sink: ITileDataSink) {
        val clipping = ClippingSink(sink)
        try {
            files.forEachIndexed { i, (file, box) ->
                if (!file.supportsTile(tile)) return@forEachIndexed
                clipping.level = i + 1
                clipping.levels = files.size
                clipping.clipTo(tile, box)
                file.query(tile, clipping)
            }
            sink.completed(QueryResult.SUCCESS)
        } catch (_: Exception) {
            sink.completed(QueryResult.FAILED)
        }
    }

    override fun dispose() = files.forEach { it.first.dispose() }

    override fun cancel() = files.forEach { it.first.cancel() }
}

/**
 * Passes on what lies inside one box, in the tile's own pixels. A [TileDataSink] because
 * that is what a [MapFile] expects to be handed, and it keeps the file's own completion
 * from reaching the real sink once per file.
 */
private class ClippingSink(sink: ITileDataSink) : TileDataSink(sink) {
    private val clipper = TileClipper(0f, 0f, 0f, 0f)
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f
    private var whole = false

    fun clipTo(tile: Tile, box: BoundingBox) {
        val scale = Tile.SIZE.toDouble() * (1 shl tile.zoomLevel.toInt())
        left = (MercatorProjection.longitudeToX(box.minLongitude) * scale - tile.tileX * Tile.SIZE).toFloat()
        right = (MercatorProjection.longitudeToX(box.maxLongitude) * scale - tile.tileX * Tile.SIZE).toFloat()
        top = (MercatorProjection.latitudeToY(box.maxLatitude) * scale - tile.tileY * Tile.SIZE).toFloat()
        bottom = (MercatorProjection.latitudeToY(box.minLatitude) * scale - tile.tileY * Tile.SIZE).toFloat()
        // Most tiles are wholly inside, and those need no clipping at all.
        whole = left <= 0f && top <= 0f && right >= Tile.SIZE && bottom >= Tile.SIZE
        clipper.setRect(left, top, right, bottom)
    }

    override fun process(element: MapElement) {
        if (!whole) {
            if (element.isPoint) {
                if (!inside(element.getPointX(0), element.getPointY(0))) return
            } else if (!clipper.clip(element)) {
                return
            }
            element.labelPosition?.let { if (!inside(it.x, it.y)) element.labelPosition = null }
            element.centroidPosition?.let { if (!inside(it.x, it.y)) element.centroidPosition = null }
        }
        super.process(element)
    }

    private fun inside(x: Float, y: Float) = x in left..right && y in top..bottom
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

// --- Hit testing ------------------------------------------------------------------

/** Where [point] sits on screen with the camera at [position]; north-up and flat, see above. */
private fun Map.screenPosition(point: TrackPoint, position: MapPosition = mapPosition): Offset {
    val mapSize = Tile.SIZE * position.scale
    val x = (MercatorProjection.longitudeToX(point.longitude) - position.x) * mapSize + width / 2.0
    val y = (MercatorProjection.latitudeToY(point.latitude) - position.y) * mapSize + height / 2.0
    return Offset(x.toFloat(), y.toFloat())
}

/**
 * The waypoint whose drawn pin a tap landed on, nearest head first, or null - checked ahead
 * of [pick] so a waypoint sitting on a track's line is read as itself rather than as a scrub.
 */
private fun pickWaypoint(
    screenX: Float,
    screenY: Float,
    map: Map,
    waypoints: List<Waypoint>,
    headRadiusPx: Float,
    tipLengthPx: Float,
): Waypoint? {
    val tap = Offset(screenX, screenY)
    var best: Waypoint? = null
    var bestDistance = Float.MAX_VALUE
    for (waypoint in waypoints) {
        val tip = map.screenPosition(waypoint.point)
        // The icon's bounds: head width across, from the tip up to the top of the head.
        val onIcon = kotlin.math.abs(tap.x - tip.x) <= headRadiusPx &&
            tap.y <= tip.y && tap.y >= tip.y - tipLengthPx - headRadiusPx
        if (!onIcon) continue
        val distance = (tip - Offset(0f, tipLengthPx) - tap).getDistanceSquared()
        if (distance < bestDistance) {
            bestDistance = distance
            best = waypoint
        }
    }
    return best
}

/**
 * Which track was tapped, and where along it.
 *
 * VTM's vector layers can say whether a tap hit *something*, not what or where along it,
 * so this projects the drawn positions itself and measures to the *line* rather than to
 * its vertices. An imported route can be a point per kilometre, and a tap halfway along one
 * of those must still land on the track someone can plainly see.
 *
 * The index reported back is the nearer end of whichever segment was hit, since that is
 * what the charts and the marker are addressed by. One scan per tap over every route,
 * allocating nothing.
 */
private fun pick(
    screenX: Float,
    screenY: Float,
    map: Map,
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
    reachPx: Float,
): Pair<Long, Int>? {
    // In map pixels at the current scale, where a screen pixel is a map pixel: the map is
    // never rotated or tilted.
    val position = map.mapPosition
    val mapSize = Tile.SIZE * position.scale
    val tapX = position.x * mapSize + (screenX - map.width / 2.0)
    val tapY = position.y * mapSize + (screenY - map.height / 2.0)

    var bestTrack: Long? = null
    var bestIndex = 0
    var bestDistance = (reachPx * reachPx).toDouble()

    // The recording is checked too - a tap on it must not read as a tap on the bare map.
    for (route in (routes + listOfNotNull(liveRoute))) {
        val starts = route.segmentStartIndices
        val runs = if (starts.isEmpty()) 1 else starts.size

        for (run in 0 until runs) {
            val from = if (starts.isEmpty()) 0 else starts[run]
            val to = if (run + 1 < starts.size) starts[run + 1] else route.points.size
            if (to <= from) continue

            // Nothing is drawn across a segment break, so nothing is hit across one either.
            var previousX = 0.0
            var previousY = 0.0
            for (index in from until to) {
                val point = route.points[index]
                val x = MercatorProjection.longitudeToX(point.longitude) * mapSize
                val y = MercatorProjection.latitudeToY(point.latitude) * mapSize

                if (index == from) {
                    // A lone position is a point, not a line: measured to itself.
                    if (to - from == 1) {
                        val dx = x - tapX
                        val dy = y - tapY
                        val distance = dx * dx + dy * dy
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestTrack = route.trackId
                            bestIndex = index
                        }
                    }
                } else {
                    val spanX = x - previousX
                    val spanY = y - previousY
                    val lengthSquared = spanX * spanX + spanY * spanY
                    val along = if (lengthSquared == 0.0) {
                        0.0
                    } else {
                        (((tapX - previousX) * spanX + (tapY - previousY) * spanY) / lengthSquared)
                            .coerceIn(0.0, 1.0)
                    }
                    val dx = previousX + along * spanX - tapX
                    val dy = previousY + along * spanY - tapY
                    val distance = dx * dx + dy * dy
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestTrack = route.trackId
                        bestIndex = if (along < 0.5) index - 1 else index
                    }
                }
                previousX = x
                previousY = y
            }
        }
    }
    return bestTrack?.let { it to bestIndex }
}

// --- Camera -----------------------------------------------------------------------

/**
 * Everything worth looking at, as one box: every route's extent and every shown map's.
 * Null when there's neither - a fresh install with nothing to frame.
 */
internal fun extentOf(
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
    basemaps: List<OfflineMap>,
): BoundingBox? {
    var south = Double.POSITIVE_INFINITY
    var west = Double.POSITIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY

    // A box of boxes: each route already knows its own extent, so this doesn't walk every
    // position of every ride on the map each time the one being recorded grows.
    for (route in routes) {
        val bounds = route.bounds ?: continue
        if (bounds.southLatitude < south) south = bounds.southLatitude
        if (bounds.northLatitude > north) north = bounds.northLatitude
        if (bounds.westLongitude < west) west = bounds.westLongitude
        if (bounds.eastLongitude > east) east = bounds.eastLongitude
    }
    liveRoute?.bounds?.let { bounds ->
        if (bounds.southLatitude < south) south = bounds.southLatitude
        if (bounds.northLatitude > north) north = bounds.northLatitude
        if (bounds.westLongitude < west) west = bounds.westLongitude
        if (bounds.eastLongitude > east) east = bounds.eastLongitude
    }
    for (map in basemaps) {
        val header = map.header
        if (header.minLatitude < south) south = header.minLatitude
        if (header.maxLatitude > north) north = header.maxLatitude
        if (header.minLongitude < west) west = header.minLongitude
        if (header.maxLongitude > east) east = header.maxLongitude
    }

    if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite()) return null
    // A single position isn't a box - nothing to fit a camera to, same as no routes at all.
    if (north == south && east == west) return null

    return BoundingBox(south, west, north, east)
}

/**
 * [this] expanded outward by [fraction] of its own span on every side, for a pan clamp
 * that stops just past the edge of the data rather than dead against it.
 */
private fun BoundingBox.padded(fraction: Double): BoundingBox {
    val latitudePad = latitudeSpan * fraction
    val longitudePad = longitudeSpan * fraction
    return BoundingBox(
        (minLatitude - latitudePad).coerceAtLeast(MercatorProjection.LATITUDE_MIN),
        (minLongitude - longitudePad).coerceAtLeast(MercatorProjection.LONGITUDE_MIN),
        (maxLatitude + latitudePad).coerceAtMost(MercatorProjection.LATITUDE_MAX),
        (maxLongitude + longitudePad).coerceAtMost(MercatorProjection.LONGITUDE_MAX),
    )
}

/** The view's size minus whatever is floating over it, or null before it is laid out. */
private fun IntSize?.usable(insets: Insets): IntSize? {
    if (this == null) return null
    val usableWidth = width - insets.left - insets.right
    val usableHeight = height - insets.top - insets.bottom
    return if (usableWidth > 0 && usableHeight > 0) IntSize(usableWidth, usableHeight) else null
}

/**
 * [target] fitted into the uncovered part of the view, centred there rather than on the
 * screen - otherwise the sheet covers the bottom of whatever was just framed.
 */
private fun fit(target: BoundingBox, size: IntSize, usable: IntSize, insets: Insets): MapPosition {
    val position = MapPosition().apply { setByBoundingBox(target, usable.width, usable.height) }
    val mapSize = Tile.SIZE * position.scale
    // Where the uncovered box's centre sits relative to the screen's, in pixels.
    val offsetX = (insets.left - insets.right) / 2.0
    val offsetY = (insets.top - insets.bottom) / 2.0
    position.x -= offsetX / mapSize
    position.y -= offsetY / mapSize
    return position
}

/** Pixels a sheet or panel hides along each edge. */
private data class Cover(val left: Int, val right: Int, val bottom: Int)

/**
 * Moves the camera the least it can so no edge of [extent] comes inside the screen's - or
 * inside whatever [cover] hides - holding it centred on any axis where it fits.
 *
 * The range is also handed to VTM as its map limit, so a drag stops cleanly against it; a
 * pinch changes the scale that range was worked out for, which the correction here catches.
 */
private fun Map.keepInView(extent: BoundingBox, cover: Cover) {
    if (width <= 0 || height <= 0) return
    val position = mapPosition
    val mapSize = Tile.SIZE * position.scale

    val (minX, maxX) = centreRange(
        MercatorProjection.longitudeToX(extent.minLongitude),
        MercatorProjection.longitudeToX(extent.maxLongitude),
        view = width, visibleStart = cover.left, visibleEnd = width - cover.right, mapSize,
    )
    val (minY, maxY) = centreRange(
        MercatorProjection.latitudeToY(extent.maxLatitude),
        MercatorProjection.latitudeToY(extent.minLatitude),
        view = height, visibleStart = 0, visibleEnd = height - cover.bottom, mapSize,
    )
    viewport().setMapLimit(minX, minY, maxX, maxY)

    val x = position.x.coerceIn(minX, maxX)
    val y = position.y.coerceIn(minY, maxY)
    if (x == position.x && y == position.y) return
    position.x = x
    position.y = y
    setMapPosition(position)
}

/**
 * Where the camera centre may sit on one axis, in projected units: [start] no further in
 * than [visibleStart] and [end] no further in than [visibleEnd] - the screen's edges, or
 * those of whatever covers them. When both can't hold at once the extent is narrower than
 * what is visible, and the only answer is centred in it.
 */
private fun centreRange(
    start: Double,
    end: Double,
    view: Int,
    visibleStart: Int,
    visibleEnd: Int,
    mapSize: Double,
): Pair<Double, Double> {
    val min = start + (view / 2.0 - visibleStart) / mapSize
    val max = end + (view / 2.0 - visibleEnd) / mapSize
    if (min <= max) return min to max
    val centred = (start + end) / 2 + (view - visibleStart - visibleEnd) / 2.0 / mapSize
    return centred to centred
}

/**
 * Pans the least it can to bring the target inside the uncovered box, or not at all -
 * expressed as a camera-centre move so the amount moved equals the amount out of bounds.
 */
private fun Map.nudgeIntoView(latitude: Double, longitude: Double, insets: Insets, margin: Int) {
    if (width <= 0 || height <= 0) return
    val position = mapPosition
    val mapSize = Tile.SIZE * position.scale

    val atX = (MercatorProjection.longitudeToX(longitude) - position.x) * mapSize + width / 2.0
    val atY = (MercatorProjection.latitudeToY(latitude) - position.y) * mapSize + height / 2.0

    val left = insets.left + margin
    val top = insets.top + margin
    val right = width - insets.right - margin
    val bottom = height - insets.bottom - margin
    if (left >= right || top >= bottom) return

    val dx = when {
        atX < left -> left - atX
        atX > right -> right - atX
        else -> 0.0
    }
    val dy = when {
        atY < top -> top - atY
        atY > bottom -> bottom - atY
        else -> 0.0
    }
    if (dx == 0.0 && dy == 0.0) return

    // Moving the picture right by dx means moving the camera left by dx. Not animated:
    // this answers a drag happening right now, and an easing curve would arrive after the
    // finger had moved on.
    position.x -= dx / mapSize
    position.y -= dy / mapSize
    setMapPosition(position)
}

private const val TRANSPARENT = 0

private const val ROUTE_WIDTH_DP = 3f
private const val MARKER_RING_WIDTH_DP = 1.5f
private const val MARKER_RADIUS_DP = 7f
private const val PUCK_RADIUS_DP = 8f
// 40dp tall with the ring's top edge: big enough to tap, since taps only hit the icon.
private const val PIN_RADIUS_DP = 14f
private const val PIN_TIP_LENGTH_DP = 25.25f
private const val PIN_HOLE_RATIO = 0.4f

/** The pin's head, ring included - for keeping a tooltip clear of it. */
val WaypointPinHeadRadius = (PIN_RADIUS_DP + MARKER_RING_WIDTH_DP).dp

/** White in both themes: a surface-coloured ring vanished against dark-mode land. */
private val MARKER_RING = Color.White

// Greys rather than a theme colour, so a pin never reads as belonging to any one track.
// Inverted in dark mode, where a dark pin sank into the land.
private val WAYPOINT_DARK_GREY = Color(0xFF424242)
private val WAYPOINT_LIGHT_GREY = Color(0xFFE0E0E0)
private const val PUCK_HALO_RADIUS_DP = 14f
private const val PUCK_HALO_ALPHA = 0.24f

/** Visible as a boundary, not as a feature of the landscape. */
private const val COVERAGE_WIDTH_DP = 1.2f
private const val COVERAGE_DASH_DP = 4f
private const val COVERAGE_OPACITY = 0.55f

/** About a fingertip. In dp: a finger is a physical size, whatever the screen's density. */
private const val TAP_REACH_DP = 40f
/** How far inside the uncovered box a scrubbed point is kept. */
private const val FOLLOW_MARGIN_DP = 36f

/** How long an overlay lets the camera settle before checking it drew for it. */
private const val OVERLAY_CHECK_MS = 150L

/** How far past the maps the outside mask reaches, in spans of their extent. */
private const val MASK_MARGIN_SPANS = 3.0


/** How far past a file's deepest zoom the camera may still go. */
private const val OVERZOOM_ALLOWANCE = 4

/** The ceiling when no map is shown and there is nothing to derive one from. */
private const val DEFAULT_MAX_ZOOM = 18
