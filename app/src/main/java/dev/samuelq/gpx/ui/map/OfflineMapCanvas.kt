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
import androidx.compose.ui.unit.dp
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
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.labels.LabelLayer
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Polygon
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.model.common.Observer
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

    var basemap by remember { mutableStateOf<Basemap?>(null) }
    // Framed once, when there's first something to frame - re-fitting on every route-set
    // change (every few seconds during a recording) would yank the map out from under a pan.
    var hasFramed by remember { mutableStateOf(false) }

    val currentRoutes by rememberUpdatedState(routes)
    val currentLiveRoute by rememberUpdatedState(liveRoute)
    val select by rememberUpdatedState(onSelect)
    val selectNothing by rememberUpdatedState(onSelectNothing)
    val reportScale by rememberUpdatedState(onScaleChange)
    val reportCamera by rememberUpdatedState(onCameraChange)

    // Resolved here, where there is a density to resolve it against: a fingertip is a
    // physical size, and 44 raw pixels is a third of one on a modern screen.
    val tapReach = remember(density) { with(density) { TAP_REACH_DP.dp.toPx() } }

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
                val hit = pick(tapped, mapView, currentRoutes, currentLiveRoute, tapReach)
                if (hit == null) selectNothing() else select(hit.first, hit.second)
            }
        )
        onDispose {
            layers.clear()
            // The layers themselves are destroyed with the view; the cache is not owned by
            // any of them, so nothing else would ever free it.
            basemap?.cache?.destroy()
        }
    }

    // --- Position reporting ---------------------------------------------------------

    DisposableEffect(mapView) {
        val position = mapView.model.mapViewPosition
        // Explicitly an Observer, not a Kotlin lambda: removeObserver has to be handed the
        // same instance addObserver got, and a function value converted at each call site
        // would be two different objects and a leak per recomposition.
        val observer = Observer {
            val centre = position.center
            val zoom = position.zoom
            val mapSize = MercatorProjection.getMapSizeWithScaleFactor(
                position.scaleFactor, mapView.model.displayModel.tileSize,
            )
            reportScale(MercatorProjection.calculateGroundResolution(centre.latitude, mapSize))
            // Not before the first frame is placed. Until then the position is mapsforge's
            // own default - the whole world - and remembering that as "where the user was"
            // is both wrong and, since it feeds back in as initialCamera, self-fulfilling.
            if (hasFramed) reportCamera(CameraSnapshot(centre.latitude, centre.longitude, zoom))
        }
        position.addObserver(observer)
        observer.onChange()
        onDispose { position.removeObserver(observer) }
    }

    // The size the map is actually laid out at. Observed rather than read off the view:
    // the first composition runs before any layout, so the camera effects below would see
    // 0 x 0, bail, and never be asked again - which is a map stuck at whole-world zoom.
    var viewSize by remember { mutableStateOf<Dimension?>(null) }

    DisposableEffect(mapView) {
        val dimension = mapView.model.mapViewDimension
        val observer = Observer { viewSize = dimension.dimension }
        dimension.addObserver(observer)
        viewSize = dimension.dimension
        onDispose { dimension.removeObserver(observer) }
    }

    // --- The basemap ----------------------------------------------------------------

    // Keyed on the shown files and the theme colours - either means a new render theme and
    // a new data store, which is the one thing worth rebuilding the tile layer for.
    LaunchedEffect(mapView, basemaps, backgroundColor, landColor, labelColor) {
        val layers = mapView.layerManager.layers
        basemap?.let { current ->
            layers.remove(current.labels)
            layers.remove(current.tiles)
            current.labels.onDestroy()
            current.tiles.onDestroy()
            // Not destroyed by either layer - a TileLayer holds its cache but never frees
            // it, so a rebuild without this leaks a screenful of tiles every time.
            current.cache.destroy()
        }
        basemap = null

        // Land under the tiles, not painted by them: the render theme's background is
        // transparent so ground no imported file covers reads as empty rather than as land.
        groups.land.replaceWith(
            basemaps.map { map -> boxPolygon(map, landColor) },
            mapView.model.displayModel,
        )
        groups.outline.replaceWith(
            basemaps.map { map -> outlinePolyline(map, labelColor) },
            mapView.model.displayModel,
        )
        // The frame buffer's colour, not the view's: mapsforge clears every frame with the
        // display model's own background (a fixed light grey) straight over whatever the
        // view is painted. Ground beyond every imported file is most of the screen on a
        // dark theme, and it was staying light.
        mapView.model.displayModel.setBackgroundColor(backgroundColor.toArgb())

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

        // context.cacheDir, not the default: the no-argument overload puts rendered tiles in
        // app-specific *external* storage, where any app holding all-files access can read
        // where this one has been looking. Everything else this app writes is internal.
        val cache = AndroidUtil.createTileCache(
            context,
            context.cacheDir,
            TILE_CACHE_ID,
            mapView.model.displayModel.tileSize,
            SCREEN_RATIO,
            mapView.model.frameBufferModel.overdrawFactor,
            /* persistent = */ false,
        )
        val tiles = TileRendererLayer(
            cache,
            store,
            mapView.model.mapViewPosition,
            /* isTransparent = */ true,
            // Names collected for the layer above rather than baked into each tile: a name
            // that straddles a tile boundary is otherwise drawn once per tile it touches,
            // which is why every pond in a bay came out captioned twice.
            /* renderLabels = */ false,
            /* cacheLabels = */ true,
            AndroidGraphicFactory.INSTANCE,
        ).apply { setXmlRenderTheme(theme) }
        val labels = LabelLayer(AndroidGraphicFactory.INSTANCE, tiles.labelStore)

        // Directly above the land, below everything the app draws itself.
        layers.add(1, tiles)
        layers.add(2, labels)
        basemap = Basemap(tiles, labels, cache)
    }

    // --- What is drawn --------------------------------------------------------------

    // Each of these builds its polylines off the main thread - a long ride is a few hundred
    // thousand LatLong allocations, which don't belong on the frame the user sees.
    LaunchedEffect(groups, routes, focusedTrackId) {
        val built = withContext(Dispatchers.Default) { routes.toPolylines(focusedTrackId) }
        groups.routes.replaceWith(built, mapView.model.displayModel)
    }

    // Its own effect: runs every few seconds for the length of a ride, touching nothing else.
    LaunchedEffect(groups, liveRoute) {
        val built = withContext(Dispatchers.Default) {
            listOfNotNull(liveRoute).toPolylines(focusedTrackId = null)
        }
        groups.trace.replaceWith(built, mapView.model.displayModel)
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
            },
            mapView.model.displayModel,
        )
    }

    // --- Where it is looked at from -------------------------------------------------

    // Every track and every shown map, unioned - the box the camera is kept inside and the
    // zoom-out floor are both about what's there at all, not what the view opens on.
    val extent = remember(routes, liveRoute, basemaps) {
        extentOf(routes, liveRoute, basemaps)
    }

    LaunchedEffect(mapView, extent, insets, basemaps, viewSize) {
        if (extent == null) return@LaunchedEffect
        val position = mapView.model.mapViewPosition

        // A cap on the way in - magnifying a file's deepest zoom many times over draws
        // detail that does not exist, convincingly.
        //
        // Measured from the deepest zoom the file *stores*, not the one it advertises: a
        // published file keeps tiles at z14 and claims z21, and mapsforge answers everything
        // above the base zoom by scaling that tile up. Taking the claim at face value let a
        // short track frame itself at z21, where the map is eight doublings of blur.
        val deepest = basemaps.maxOfOrNull { it.header.baseZoom } ?: DEFAULT_MAX_ZOOM
        val ceiling = (deepest + OVERZOOM_ALLOWANCE).coerceAtMost(MAX_ZOOM_LEVEL)
        position.zoomLevelMax = ceiling.toByte()

        // Zooming out is limited to where everything is already on screen - beyond that is
        // nothing but flat background, with no way back.
        val dimension = viewSize.usable(insets)
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
        val observer = Observer {
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

    // Read once, not per recomposition: the camera is republished as the map moves, so
    // keying the framing effect on it would let the map's own position come back around as
    // the place it is supposed to be restored to.
    val rememberedCamera = remember { initialCamera }

    LaunchedEffect(mapView, initialExtent, tracksLoading, insets, viewSize) {
        if (hasFramed) return@LaunchedEffect
        val position = mapView.model.mapViewPosition

        val remembered = rememberedCamera
        if (remembered != null) {
            position.setCenter(LatLong(remembered.latitude, remembered.longitude))
            position.zoom = remembered.zoom
            hasFramed = true
            return@LaunchedEffect
        }
        if (tracksLoading) return@LaunchedEffect
        val target = initialExtent ?: return@LaunchedEffect
        val dimension = viewSize.usable(insets) ?: return@LaunchedEffect

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

/**
 * The basemap as one thing to put up and take down: the tiles, the names drawn over them,
 * and the cache they are rendered into.
 */
private class Basemap(
    val tiles: TileRendererLayer,
    val labels: LabelLayer,
    val cache: TileCache,
)

/** The stable layer groups, in the order they are drawn. */
private class MapLayers(
    val land: GroupLayer,
    val outline: GroupLayer,
    val routes: GroupLayer,
    val trace: GroupLayer,
    val markers: GroupLayer,
)

/**
 * Refills a group in place.
 *
 * Each child is handed the display model by hand. A layer normally receives it from
 * `Layers.add`, and going straight into [GroupLayer.layers] bypasses that; the group
 * propagates its own only to the children present at the moment it is itself added, and
 * these groups are added empty and filled afterwards. Without this every overlay draws
 * with a null display model and takes the render thread down with it.
 */
internal fun GroupLayer.replaceWith(next: List<Layer>, displayModel: DisplayModel) {
    synchronized(this) {
        layers.clear()
        next.forEach { it.displayModel = displayModel }
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

    // layerXY is null for a layer with no position of its own, which this is - declaring it
    // non-null makes Kotlin's own check throw on the first tap anyone makes.
    override fun onTap(tapLatLong: LatLong, layerXY: Point?, tapXY: Point?): Boolean {
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
 * under those pixels - this projects the drawn positions itself and measures to the *line*
 * rather than to its vertices. An imported route can be a point per kilometre, and a tap
 * halfway along one of those must still land on the track someone can plainly see.
 *
 * The index reported back is the nearer end of whichever segment was hit, since that is
 * what the charts and the marker are addressed by. One scan per tap over the routes on
 * screen, allocating nothing.
 */
private fun pick(
    tapped: LatLong,
    mapView: MapView,
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
    reachPx: Float,
): Pair<Long, Int>? {
    val mapSize = MercatorProjection.getMapSizeWithScaleFactor(
        mapView.model.mapViewPosition.scaleFactor, mapView.model.displayModel.tileSize,
    )
    val tapX = MercatorProjection.longitudeToPixelX(tapped.longitude, mapSize)
    val tapY = MercatorProjection.latitudeToPixelY(tapped.latitude, mapSize)

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
                val x = MercatorProjection.longitudeToPixelX(point.longitude, mapSize)
                val y = MercatorProjection.latitudeToPixelY(point.latitude, mapSize)

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
private fun Dimension?.usable(insets: Insets): Dimension? {
    if (this == null) return null
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

/** About a fingertip. In dp: a finger is a physical size, whatever the screen's density. */
private const val TAP_REACH_DP = 40f
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
