package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.data.map.OfflineMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.GroupLayer
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Polygon
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * One track as the map should draw it: the positions themselves, in degrees - not a
 * projected shape, since the map has its own coordinate space now.
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
 * Mapsforge renders from the map files directly and has no tile server behind it, so
 * there is nothing here that could reach the network even if the permission existed.
 *
 * Layers are held in stable [GroupLayer]s rather than rebuilt as one stack: the tile layer
 * is expensive to recreate, and the routes above it change every few seconds during a
 * recording. Each group is refilled on its own without disturbing the others' order.
 */
@Composable
fun OfflineMapCanvas(
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
    /** Which route the sheet is showing, drawn heavier than the rest. */
    focusedTrackId: Long? = null,
    /** Highlighted point within [focusedTrackId]'s route, as an index into its points. */
    selectedIndex: Int? = null,
    markerColor: Color = Color.Unspecified,
    markerRingColor: Color = Color.Unspecified,
    /** The track whose last point is where the recorder is standing, or null. */
    puckTrackId: Long? = null,
    puckColor: Color = Color.Unspecified,
    onSelect: (trackId: Long, index: Int) -> Unit = { _, _ -> },
    onSelectNothing: () -> Unit = {},
    /** Space kept clear of routes when framing, for the sheet and the controls. */
    contentPadding: PaddingValues = PaddingValues(),
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
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val mapView = rememberMapViewWithLifecycle(backgroundColor)

    // Every group is added once, in drawing order, and refilled in place afterwards. The
    // tap layer goes last: taps are offered to layers in reverse order, so last is first.
    val groups = remember {
        MapLayers(
            land = GroupLayer(),
            outline = GroupLayer(),
            routes = GroupLayer(),
            trace = GroupLayer(),
            markers = GroupLayer(),
        )
    }

    var tileLayer by remember { mutableStateOf<TileRendererLayer?>(null) }
    // Framed once, when there's first something to frame - re-fitting on every route-set
    // change (every few seconds during a recording) would yank the map out from under a pan.
    var hasFramed by remember { mutableStateOf(false) }

    val currentRoutes by rememberUpdatedState(routes)
    val currentLiveRoute by rememberUpdatedState(liveRoute)
    val select by rememberUpdatedState(onSelect)
    val selectNothing by rememberUpdatedState(onSelectNothing)
    val reportScale by rememberUpdatedState(onScaleChange)
    val reportCamera by rememberUpdatedState(onCameraChange)

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
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        update = { /* Every change below is applied through an effect, not on recomposition. */ },
    )

    // --- The stack, assembled once --------------------------------------------------

    DisposableEffect(mapView, groups) {
        val layers = mapView.layerManager.layers
        layers.add(groups.land)
        layers.add(groups.outline)
        layers.add(groups.routes)
        layers.add(groups.trace)
        layers.add(groups.markers)
        layers.add(
            TapLayer { tapped ->
                val hit = pick(tapped, mapView, currentRoutes, currentLiveRoute)
                if (hit == null) selectNothing() else select(hit.first, hit.second)
            }
        )
        onDispose { layers.clear() }
    }

    // --- Position reporting ---------------------------------------------------------

    DisposableEffect(mapView) {
        val position = mapView.model.mapViewPosition
        val observer = {
            val centre = position.center
            val zoom = position.zoom
            val mapSize = MercatorProjection.getMapSizeWithScaleFactor(
                position.scaleFactor, mapView.model.displayModel.tileSize,
            )
            reportScale(MercatorProjection.calculateGroundResolution(centre.latitude, mapSize))
            reportCamera(CameraSnapshot(centre.latitude, centre.longitude, zoom))
        }
        position.addObserver(observer)
        observer()
        onDispose { position.removeObserver(observer) }
    }

    // --- The basemap ----------------------------------------------------------------

    // Keyed on the shown files and the theme colours - either means a new render theme and
    // a new data store, which is the one thing worth rebuilding the tile layer for.
    LaunchedEffect(mapView, basemaps, backgroundColor, landColor, labelColor) {
        val layers = mapView.layerManager.layers
        tileLayer?.let { layers.remove(it); it.onDestroy() }
        tileLayer = null

        // Land under the tiles, not painted by them: the render theme's background is
        // transparent so ground no imported file covers reads as empty rather than as land.
        groups.land.replaceWith(
            basemaps.map { map -> boxPolygon(map, landColor) }
        )
        groups.outline.replaceWith(
            basemaps.map { map -> outlinePolyline(map, labelColor) }
        )
        mapView.setBackgroundColor(backgroundColor.toArgb())

        if (basemaps.isEmpty()) return@LaunchedEffect

        val store = withContext(Dispatchers.IO) {
            MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).apply {
                // A file that will not open is skipped rather than fatal - the rest of the
                // maps, and the routes above them, are still worth drawing.
                basemaps.forEach { map ->
                    runCatching { addMapDataStore(MapFile(map.file), false, false) }
                }
            }
        }
        val theme = GeneratedRenderTheme(
            MapRenderTheme.xml(land = landColor, label = labelColor, background = backgroundColor)
        )

        val cache = AndroidUtil.createTileCache(
            context,
            TILE_CACHE_ID,
            mapView.model.displayModel.tileSize,
            SCREEN_RATIO,
            mapView.model.frameBufferModel.overdrawFactor,
        )
        val layer = TileRendererLayer(
            cache,
            store,
            mapView.model.mapViewPosition,
            /* isTransparent = */ true,
            /* renderLabels = */ true,
            /* cacheLabels = */ false,
            AndroidGraphicFactory.INSTANCE,
        ).apply { setXmlRenderTheme(theme) }

        // Directly above the land, below everything the app draws itself.
        layers.add(1, layer)
        tileLayer = layer
    }

    // --- What is drawn --------------------------------------------------------------

    // Each of these builds its polylines off the main thread - a long ride is a few hundred
    // thousand LatLong allocations, which don't belong on the frame the user sees.
    LaunchedEffect(groups, routes, focusedTrackId) {
        val built = withContext(Dispatchers.Default) { routes.toPolylines(focusedTrackId) }
        groups.routes.replaceWith(built)
    }

    // Its own effect: runs every few seconds for the length of a ride, touching nothing else.
    LaunchedEffect(groups, liveRoute) {
        val built = withContext(Dispatchers.Default) {
            listOfNotNull(liveRoute).toPolylines(focusedTrackId = null)
        }
        groups.trace.replaceWith(built)
    }

    LaunchedEffect(groups, routes, liveRoute, puckTrackId, focusedTrackId, selectedIndex) {
        // The recording is the usual answer for the puck and isn't in `routes`, so it is
        // asked first.
        val puckAt = (liveRoute?.takeIf { it.trackId == puckTrackId }
            ?: routes.firstOrNull { it.trackId == puckTrackId })
            ?.points?.lastOrNull()
        val markerAt = routes.firstOrNull { it.trackId == focusedTrackId }
            ?.points?.getOrNull(selectedIndex ?: -1)

        groups.markers.replaceWith(
            buildList {
                if (puckAt != null) {
                    // Bigger than the scrub marker and wearing a halo: one points at a
                    // moment in a ride that's over, this is the only thing on screen about
                    // right now.
                    add(circle(puckAt, PUCK_HALO_RADIUS, puckColor.copy(alpha = PUCK_HALO_ALPHA), null))
                    add(circle(puckAt, PUCK_RADIUS, puckColor, markerRingColor))
                }
                // Last, so the point being read about is never underneath anything.
                if (markerAt != null) add(circle(markerAt, MARKER_RADIUS, markerColor, markerRingColor))
            }
        )
    }

    // --- Where it is looked at from -------------------------------------------------

    // Every track and every shown map, unioned - the box the camera is kept inside and the
    // zoom-out floor are both about what's there at all, not what the view opens on.
    val extent = remember(routes, liveRoute, basemaps) {
        extentOf(routes, liveRoute, basemaps)
    }

    LaunchedEffect(mapView, extent, insets, basemaps) {
        if (extent == null) return@LaunchedEffect
        val position = mapView.model.mapViewPosition

        // A cap on the way in - magnifying a file's deepest zoom many times over draws
        // detail that does not exist, convincingly.
        val deepest = basemaps.maxOfOrNull { it.header.maxZoom } ?: DEFAULT_MAX_ZOOM
        val ceiling = (deepest + OVERZOOM_ALLOWANCE).coerceAtMost(MAX_ZOOM_LEVEL)
        position.zoomLevelMax = ceiling.toByte()

        // Zooming out is limited to where everything is already on screen - beyond that is
        // nothing but flat background, with no way back.
        val dimension = mapView.dimensionOrNull(insets)
        if (dimension != null) {
            val floor = LatLongUtils.zoomForBounds(dimension, extent, mapView.model.displayModel.tileSize)
            position.zoomLevelMin = minOf(floor.toInt(), ceiling).toByte()
        }
    }

    // Panning can push the near edge of everything there is up to PAN_OVERSHOOT_FRACTION
    // off screen, not clamped dead against it - a hard wall exactly at the last point reads
    // as the map being broken, not as having reached the edge of the data.
    DisposableEffect(mapView, extent) {
        val bounds = extent?.padded(PAN_OVERSHOOT_FRACTION)
        if (bounds == null) return@DisposableEffect onDispose { }
        val position = mapView.model.mapViewPosition
        var clamping = false
        val observer = {
            // setCenter notifies observers again; without this the clamp re-enters itself.
            if (!clamping) {
                val centre = position.center
                val clamped = LatLong(
                    centre.latitude.coerceIn(bounds.minLatitude, bounds.maxLatitude),
                    centre.longitude.coerceIn(bounds.minLongitude, bounds.maxLongitude),
                )
                if (clamped != centre) {
                    clamping = true
                    position.center = clamped
                    clamping = false
                }
            }
        }
        position.addObserver(observer)
        onDispose { position.removeObserver(observer) }
    }

    // Only reached once per process at most: the moment a camera is ever remembered (see
    // initialCamera), every later visit to this screen - even after navigating away and
    // back - skips straight past it. Tracks first, since that's almost always what someone
    // opened the app to look at; falls back to the shown maps' own extent only once
    // tracksLoading says there is nothing recorded or imported yet.
    val initialExtent = remember(routes, liveRoute, basemaps) {
        extentOf(routes, liveRoute, emptyList()) ?: extentOf(emptyList(), null, basemaps)
    }

    LaunchedEffect(mapView, initialCamera, initialExtent, tracksLoading, insets) {
        if (hasFramed) return@LaunchedEffect
        val position = mapView.model.mapViewPosition

        val remembered = initialCamera
        if (remembered != null) {
            position.setCenter(LatLong(remembered.latitude, remembered.longitude))
            position.zoom = remembered.zoom
            hasFramed = true
            return@LaunchedEffect
        }
        if (tracksLoading) return@LaunchedEffect
        val target = initialExtent ?: return@LaunchedEffect
        val dimension = mapView.dimensionOrNull(insets) ?: return@LaunchedEffect

        position.setCenter(target.centerPoint)
        position.zoomLevel =
            LatLongUtils.zoomForBounds(dimension, target, mapView.model.displayModel.tileSize)
        hasFramed = true
    }

    // Scrubbing a chart moves the marker; moves the camera the least it can rather than
    // re-centring, which would turn reading a chart into a ride through a moving map.
    LaunchedEffect(mapView, focusedTrackId, selectedIndex, insets) {
        val at = currentRoutes.firstOrNull { it.trackId == focusedTrackId }
            ?.points?.getOrNull(selectedIndex ?: -1) ?: return@LaunchedEffect
        mapView.nudgeIntoView(LatLong(at.latitude, at.longitude), insets)
    }
}

/** Pixels kept clear on each edge, for whatever is floating over the map. */
private class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The stable layer groups, in the order they are drawn. */
private class MapLayers(
    val land: GroupLayer,
    val outline: GroupLayer,
    val routes: GroupLayer,
    val trace: GroupLayer,
    val markers: GroupLayer,
)

private fun GroupLayer.replaceWith(next: List<Layer>) {
    synchronized(this) {
        layers.clear()
        layers.addAll(next)
    }
    requestRedraw()
}

/**
 * A [MapView] that follows the composition's lifecycle - it owns a render thread and a
 * frame buffer, and leaking one leaks both.
 */
@Composable
private fun rememberMapViewWithLifecycle(background: Color): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            // No rotation and no built-in controls: a route drawn north-up is a shape
            // people recognise, and the app draws its own chrome.
            isClickable = true
            setBuiltInZoomControls(false)
            mapScaleBar.isVisible = false
            setBackgroundColor(background.toArgb())
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) mapView.destroyAll()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.destroyAll()
            AndroidGraphicFactory.clearResourceMemoryCache()
        }
    }
    return mapView
}

/** The generated theme, handed to mapsforge as the stream it insists on. */
private class GeneratedRenderTheme(xml: String) : XmlRenderTheme {
    private val bytes = xml.toByteArray()
    private var menuCallback: XmlRenderThemeMenuCallback? = null
    private var resourceProvider: XmlThemeResourceProvider? = null

    override fun getMenuCallback(): XmlRenderThemeMenuCallback? = menuCallback
    override fun setMenuCallback(callback: XmlRenderThemeMenuCallback?) { menuCallback = callback }
    override fun getRelativePathPrefix(): String = ""
    override fun getRenderThemeAsStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getResourceProvider(): XmlThemeResourceProvider? = resourceProvider
    override fun setResourceProvider(provider: XmlThemeResourceProvider?) { resourceProvider = provider }
}

/** A tap anywhere on the map, offered before any drawn layer gets a look. */
private class TapLayer(private val onTap: (LatLong) -> Unit) : Layer() {
    override fun draw(
        boundingBox: BoundingBox?,
        zoomLevel: Byte,
        canvas: org.mapsforge.core.graphics.Canvas?,
        topLeftPoint: Point?,
        rotation: org.mapsforge.core.model.Rotation?,
    ) = Unit

    override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
        onTap(tapLatLong)
        return true
    }
}

// --- Drawing ----------------------------------------------------------------------

private fun stroke(color: Color, width: Float, dashed: Boolean = false) =
    AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(color.toArgb())
        setStrokeWidth(width)
        setStyle(Style.STROKE)
        setStrokeCap(Cap.ROUND)
        setStrokeJoin(Join.ROUND)
        if (dashed) setDashPathEffect(floatArrayOf(6f, 4f))
    }

private fun fill(color: Color) = AndroidGraphicFactory.INSTANCE.createPaint().apply {
    setColor(color.toArgb())
    setStyle(Style.FILL)
}

/**
 * Every route as polylines, one per segment - the gap between segments is signal loss and
 * nothing should be drawn across it.
 */
private fun List<RouteOverlay>.toPolylines(focusedTrackId: Long?): List<Layer> {
    val out = ArrayList<Layer>()
    forEach { route ->
        val width = if (route.trackId == focusedTrackId) FOCUSED_WIDTH else ROUTE_WIDTH
        val paint = stroke(route.color, width)
        // Both producers hand these over ascending and starting at 0, so this neither
        // sorts nor dedupes - the data already carries that guarantee.
        val starts = route.segmentStartIndices
        val runs = if (starts.isEmpty()) 1 else starts.size

        for (i in 0 until runs) {
            val from = if (starts.isEmpty()) 0 else starts[i]
            val to = if (i + 1 < starts.size) starts[i + 1] else route.points.size
            // A single position isn't a line; still drawn as the puck if it is live.
            if (to - from < 2) continue

            val coordinates = ArrayList<LatLong>(to - from)
            for (index in from until to) {
                val point = route.points[index]
                coordinates.add(LatLong(point.latitude, point.longitude))
            }
            out.add(Polyline(paint, AndroidGraphicFactory.INSTANCE).apply { setPoints(coordinates) })
        }
    }
    return out
}

private fun circle(at: TrackPoint, radius: Float, fill: Color, ring: Color?) = Circle(
    LatLong(at.latitude, at.longitude),
    radius,
    fill(fill),
    ring?.let { stroke(it, MARKER_RING_WIDTH) },
)

/** A map's own box, filled - the ground it actually covers. */
private fun boxPolygon(map: OfflineMap, land: Color) =
    Polygon(fill(land), null, AndroidGraphicFactory.INSTANCE).apply {
        val h = map.header
        setPoints(
            listOf(
                LatLong(h.minLatitude, h.minLongitude),
                LatLong(h.minLatitude, h.maxLongitude),
                LatLong(h.maxLatitude, h.maxLongitude),
                LatLong(h.maxLatitude, h.minLongitude),
                LatLong(h.minLatitude, h.minLongitude),
            )
        )
    }

/** The dashed boundary marking where an imported file's detail stops. */
private fun outlinePolyline(map: OfflineMap, label: Color) =
    Polyline(stroke(label.copy(alpha = COVERAGE_OPACITY), COVERAGE_WIDTH, dashed = true), AndroidGraphicFactory.INSTANCE)
        .apply {
            val h = map.header
            setPoints(
                listOf(
                    LatLong(h.minLatitude, h.minLongitude),
                    LatLong(h.minLatitude, h.maxLongitude),
                    LatLong(h.maxLatitude, h.maxLongitude),
                    LatLong(h.maxLatitude, h.minLongitude),
                    LatLong(h.minLatitude, h.minLongitude),
                )
            )
        }

// --- Hit testing ------------------------------------------------------------------

/**
 * Which track was tapped, and where along it.
 *
 * Mapsforge draws overlays rather than indexing them, so there is nothing to ask what was
 * under those pixels - this projects every drawn position and takes the nearest within a
 * fingertip. One scan per tap over the routes on screen.
 */
private fun pick(
    tapped: LatLong,
    mapView: MapView,
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
): Pair<Long, Int>? {
    val mapSize = MercatorProjection.getMapSizeWithScaleFactor(
        mapView.model.mapViewPosition.scaleFactor, mapView.model.displayModel.tileSize,
    )
    val tapX = MercatorProjection.longitudeToPixelX(tapped.longitude, mapSize)
    val tapY = MercatorProjection.latitudeToPixelY(tapped.latitude, mapSize)

    var bestTrack: Long? = null
    var bestIndex = 0
    var bestDistance = TAP_REACH_PX * TAP_REACH_PX

    // The recording is checked too - a tap on it must not read as a tap on the bare map.
    for (route in (routes + listOfNotNull(liveRoute))) {
        route.points.forEachIndexed { index, point ->
            val dx = MercatorProjection.longitudeToPixelX(point.longitude, mapSize) - tapX
            val dy = MercatorProjection.latitudeToPixelY(point.latitude, mapSize) - tapY
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestTrack = route.trackId
                bestIndex = index
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

    return runCatching { BoundingBox(south, west, north, east) }.getOrNull()
}

/**
 * [this] expanded outward by [fraction] of its own span on every side, for a pan clamp
 * that stops just past the edge of the data rather than dead against it.
 */
private fun BoundingBox.padded(fraction: Double): BoundingBox = runCatching {
    val latitudePad = (maxLatitude - minLatitude) * fraction
    val longitudePad = (maxLongitude - minLongitude) * fraction
    BoundingBox(
        (minLatitude - latitudePad).coerceAtLeast(LatLongUtils.LATITUDE_MIN),
        (minLongitude - longitudePad).coerceAtLeast(LatLongUtils.LONGITUDE_MIN),
        (maxLatitude + latitudePad).coerceAtMost(LatLongUtils.LATITUDE_MAX),
        (maxLongitude + longitudePad).coerceAtMost(LatLongUtils.LONGITUDE_MAX),
    )
}.getOrDefault(this)

/** The view's size minus whatever is floating over it, or null before it is laid out. */
private fun MapView.dimensionOrNull(insets: Insets): Dimension? {
    val usableWidth = width - insets.left - insets.right - 2 * EDGE_PADDING_PX
    val usableHeight = height - insets.top - insets.bottom - 2 * EDGE_PADDING_PX
    return if (usableWidth > 0 && usableHeight > 0) Dimension(usableWidth, usableHeight) else null
}

/**
 * Pans the least it can to bring [target] inside the uncovered box, or not at all -
 * expressed as a camera-centre move so the amount moved equals the amount out of bounds.
 */
private fun MapView.nudgeIntoView(target: LatLong, insets: Insets) {
    if (width <= 0 || height <= 0) return
    val position = model.mapViewPosition
    val mapSize = MercatorProjection.getMapSizeWithScaleFactor(
        position.scaleFactor, model.displayModel.tileSize,
    )
    val centre = position.center
    val centreX = MercatorProjection.longitudeToPixelX(centre.longitude, mapSize)
    val centreY = MercatorProjection.latitudeToPixelY(centre.latitude, mapSize)

    val atX = MercatorProjection.longitudeToPixelX(target.longitude, mapSize) - centreX + width / 2.0
    val atY = MercatorProjection.latitudeToPixelY(target.latitude, mapSize) - centreY + height / 2.0

    val left = insets.left + FOLLOW_MARGIN_PX
    val top = insets.top + FOLLOW_MARGIN_PX
    val right = width - insets.right - FOLLOW_MARGIN_PX
    val bottom = height - insets.bottom - FOLLOW_MARGIN_PX
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
    position.center = MercatorProjection.fromPixels(centreX - dx, centreY - dy, mapSize)
}

private const val ROUTE_WIDTH = 8f
private const val FOCUSED_WIDTH = 12f
private const val MARKER_RING_WIDTH = 4f
private const val MARKER_RADIUS = 10f
private const val PUCK_RADIUS = 12f
private const val PUCK_HALO_RADIUS = 28f
private const val PUCK_HALO_ALPHA = 0.24f

/** Visible as a boundary, not as a feature of the landscape. */
private const val COVERAGE_WIDTH = 3f
private const val COVERAGE_OPACITY = 0.55f

/** About a fingertip, in pixels rather than dp because it is a hit test, not a drawing. */
private const val TAP_REACH_PX = 44.0
private const val FOLLOW_MARGIN_PX = 96
private const val EDGE_PADDING_PX = 64

/** How far past the edge of the data the pan clamp allows, as a fraction of its own span. */
private const val PAN_OVERSHOOT_FRACTION = 0.05

/** How far past a file's deepest zoom the camera may still go. */
private const val OVERZOOM_ALLOWANCE = 2

/** The ceiling when no map is shown and there is nothing to derive one from. */
private const val DEFAULT_MAX_ZOOM = 16

/** Mapsforge addresses zoom as a byte; this is where its own tile maths stops. */
private const val MAX_ZOOM_LEVEL = 22

private const val TILE_CACHE_ID = "basemap"

/** How much of the screen the in-memory tile cache is sized against. */
private const val SCREEN_RATIO = 1.5f
