package dev.samuelq.gpx.ui.map

import android.view.ViewConfiguration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.map.maxViewZoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oscim.android.MapView
import org.oscim.android.canvas.AndroidBitmap
import org.oscim.core.BoundingBox
import org.oscim.core.MapPosition
import org.oscim.core.MercatorProjection
import org.oscim.core.Tile
import org.oscim.layers.marker.ItemizedLayer
import org.oscim.layers.marker.MarkerInterface
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.map.Map
import java.util.concurrent.atomic.AtomicReference

/**
 * Routes over an offline basemap, or over nothing if none is imported. Each layer lives in
 * a fixed [LayerGroup] so it can be swapped alone: the tile layer is expensive to recreate,
 * and the routes change every few seconds during a recording.
 */
@Composable
internal fun OfflineMapCanvas(
    /** Drawn in this order, the last on top. */
    routes: List<RouteOverlay>,
    basemaps: List<OfflineMap>,
    contentDescription: String,
    controller: MapController,
    modifier: Modifier = Modifier,
    /**
     * True while [routes] may still be missing tracks. Without it an empty first frame would
     * frame the basemap and latch there.
     */
    tracksLoading: Boolean,
    /** The recording, apart from [routes] so its growth rebuilds only its own geometry. */
    liveRoute: RouteOverlay?,
    /** In [routes], or [liveRoute]. */
    focusedTrackId: Long?,
    /** Highlighted point within [focusedTrackId]'s route, as an index into its points. */
    selectedIndex: Int?,
    /** Marks both ends of [focusedTrackId]'s route, which a trim being set up has cut to. */
    markEnds: Boolean,
    /** [focusedTrackId]'s colour, for its selected point and its [trackWaypoints]. */
    markerColor: Color,
    /** Whether to mark [liveRoute]'s last point. */
    showPuck: Boolean,
    /** [liveRoute]'s colour, for the puck, [position] and [liveWaypoints]. */
    puckColor: Color,
    /** The user's position, marked like the puck: it becomes one when a recording starts. */
    position: TrackPoint?,
    /** [focusedTrackId]'s waypoints. */
    trackWaypoints: List<Waypoint>,
    /** [liveRoute]'s waypoints. */
    liveWaypoints: List<Waypoint>,
    onSelect: (trackId: Long, index: Int) -> Unit,
    onSelectNothing: () -> Unit,
    onSelectWaypoint: (Waypoint) -> Unit,
    /** A waypoint whose pin's screen position goes to [onFollowedWaypointMove] on every camera move. */
    followedWaypoint: Waypoint?,
    /** The pin's tip, not the tap. Also called as soon as [followedWaypoint] changes. */
    onFollowedWaypointMove: (Offset) -> Unit,
    /** Space kept clear of routes, for the sheet and the controls. */
    contentPadding: PaddingValues,
    /** A track to frame once, then [onFramed]. Map taps pass null. */
    frameTrackId: Long?,
    /** [contentPadding] as it will be once the sheet or panel has settled. */
    framePadding: PaddingValues,
    onFramed: () -> Unit,
    /** Bottom covered by a sheet; panning is clamped to its top edge. */
    sheetHeight: Dp,
    /** The same for the landscape side panel. */
    panelWidth: Dp,
    basemapColors: BasemapColors,
    onScaleChange: (metersPerPixel: Double) -> Unit,
    /** Kept centred through zooms while followed; centring on each new one is the caller's. */
    followed: TrackPoint?,
    /** A user's drag, which ends following; never a move the app makes. */
    onDrag: () -> Unit,
    /** Cold-start camera, if remembered; takes priority over fitting to tracks or maps. */
    initialCamera: CameraSnapshot?,
    onCameraChange: (CameraSnapshot) -> Unit,
    /** True while framing every track was asked for but would leave them all specks; see [tooFarApart]. */
    onTooFarApartChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current

    val mapView = rememberMapViewWithLifecycle()
    val map = mapView.map()

    var basemap by remember { mutableStateOf<Basemap?>(null) }
    // Framed once: re-fitting on every route change would yank the map out from under a pan.
    var hasFramed by remember { mutableStateOf(false) }
    // Kept so a rotation doesn't drop back to the specks.
    var spread by rememberSaveable { mutableStateOf(false) }
    // From layout: first composition precedes it, and fitting to 0 x 0 zooms to the world.
    var viewSize by remember { mutableStateOf<IntSize?>(null) }

    val currentRoutes by rememberUpdatedState(routes)
    val currentLiveRoute by rememberUpdatedState(liveRoute)
    val currentWaypoints by rememberUpdatedState(trackWaypoints + liveWaypoints)
    val select by rememberUpdatedState(onSelect)
    val selectNothing by rememberUpdatedState(onSelectNothing)
    val selectWaypoint by rememberUpdatedState(onSelectWaypoint)
    val currentFollowedWaypoint by rememberUpdatedState(followedWaypoint)
    val reportWaypointAt by rememberUpdatedState(onFollowedWaypointMove)
    val reportScale by rememberUpdatedState(onScaleChange)
    val reportCamera by rememberUpdatedState(onCameraChange)
    val drag by rememberUpdatedState(onDrag)
    // Plain, not state: a drag clears it at once, before the GL thread's next update reads it.
    val followedNow = remember { AtomicReference<TrackPoint?>(null) }

    val tapReach = remember(density) { with(density) { TAP_REACH_DP.dp.toPx() } }
    val pinHeadRadius = remember(density) { with(density) { WaypointPinHeadRadius.toPx() } }
    val pinTipLength = remember(density) { with(density) { PIN_TIP_LENGTH_DP.dp.toPx() } }
    val doublingPx = remember(density) { with(density) { QUICK_ZOOM_DOUBLING_DP.dp.toPx() } }
    // A 48dp target, the pin itself being narrower.
    val pinMinHalf = remember(density) { with(density) { 24.dp.toPx() } }
    val followMargin = remember(density) { with(density) { FOLLOW_MARGIN_DP.dp.roundToPx() } }
    val speckPx = remember(density) { with(density) { SPECK_DP.dp.toPx() } }

    val insets = remember(contentPadding, layoutDirection, density) {
        contentPadding.toInsets(density, layoutDirection)
    }
    val frameInsets = remember(framePadding, layoutDirection, density) {
        framePadding.toInsets(density, layoutDirection)
    }
    val framed by rememberUpdatedState(onFramed)

    val routeLayer = remember(map) { LineLayer(map) }
    val traceLayer = remember(map) { LineLayer(map) }
    // Bottom first; separate layers since VTM z-sorts a layer's markers by screen y. The
    // selected dot sits under the pins, unless a tapped pin put it there: then it's over the
    // rest, under only that pin. The puck is always over pins, or a waypoint dropped where
    // the user stands would hide them.
    val belowPinsLayer = remember(map) { markerLayer(map) }
    val pinLayer = remember(map) { markerLayer(map) }
    val abovePinsLayer = remember(map) { markerLayer(map) }
    val onTopLayer = remember(map) { markerLayer(map) }

    DisposableEffect(map) {
        val layers = map.layers()
        LayerGroup.entries.forEach { layers.addGroup(it.ordinal) }
        layers.add(routeLayer.layer, LayerGroup.Routes.ordinal)
        layers.add(traceLayer.layer, LayerGroup.Trace.ordinal)
        listOf(belowPinsLayer, pinLayer, abovePinsLayer, onTopLayer)
            .forEach { layers.add(it, LayerGroup.Markers.ordinal) }
        val taps = TapDetector(
            ViewConfiguration.get(context), map, doublingPx,
            onDrag = {
                if (followedNow.getAndSet(null) != null) drag()
            },
        ) { x, y ->
            // Waypoints first: a pin sits on its own track's line and is the more specific hit.
            val waypointHit = pickWaypoint(
                x, y, { map.screenPosition(it) }, currentWaypoints, pinHeadRadius, pinTipLength, pinMinHalf,
                onTop = currentFollowedWaypoint,
            )
            if (waypointHit != null) {
                selectWaypoint(waypointHit)
            } else {
                val hit = pick(x, y, map, currentRoutes, currentLiveRoute, tapReach)
                if (hit == null) selectNothing() else select(hit.first, hit.second)
            }
        }
        map.input.bind(taps)
        // North-up and flat, so a route keeps a recognisable shape.
        map.eventLayer.enableRotation(false)
        map.eventLayer.enableTilt(false)
        onDispose { map.input.unbind(taps) }
    }

    // Before layout, so a newly followed pin (maybe from a scrub, with no camera move) is placed.
    SideEffect {
        followedWaypoint?.let { reportWaypointAt(map.screenPosition(it.point)) }
    }

    // New files, colours or font scale need a new theme and data source; the only reason to
    // rebuild tiles. Font scale changes without recreating the activity.
    LaunchedEffect(map, basemaps, basemapColors, density.fontScale) {
        basemap?.let(map::detach)
        basemap = null
        map.attachBasemap(basemaps, basemapColors, density) { basemap = it }
    }

    val routeStyles = remember(density) { RouteStyles(density) }

    // Built off the main thread: a long ride is hundreds of thousands of coordinates.
    LaunchedEffect(routeLayer, routes, focusedTrackId) {
        val built = withContext(Dispatchers.Default) { routes.toLines(routeStyles, focusedTrackId) }
        routeLayer.replaceWith(built)
    }

    // Separate so the per-few-seconds live update touches nothing else.
    LaunchedEffect(traceLayer, liveRoute) {
        val built = withContext(Dispatchers.Default) {
            listOfNotNull(liveRoute).toLines(routeStyles)
        }
        traceLayer.replaceWith(built)
    }

    // Per colour, not per selection: a new bitmap per scrub frame is a texture upload per frame.
    val symbols = remember(markerColor, puckColor, basemapColors.land, density) {
        MarkerSymbols(markerColor, puckColor, basemapColors.land, density)
    }

    LaunchedEffect(
        belowPinsLayer, pinLayer, abovePinsLayer, onTopLayer, symbols, routes, liveRoute, showPuck, focusedTrackId, selectedIndex, markEnds,
        trackWaypoints, liveWaypoints, followedWaypoint, position,
    ) {
        val puckRoute = liveRoute?.takeIf { showPuck }
        val here = symbols.puck(position, bearing = null)
        val focused = routeFor(focusedTrackId, routes, liveRoute)?.points
        val markerAt = focused?.getOrNull(selectedIndex ?: -1)
        // An out-and-back's kept part lies over its cut part; only its ends tell them apart.
        val ends = if (markEnds) symbols.selectedDot(focused?.getOrNull(0)) + symbols.selectedDot(focused?.lastOrNull()) else emptyList()

        val selected = symbols.selectedDot(markerAt) + ends
        val pinTapped = followedWaypoint != null
        belowPinsLayer.show(if (pinTapped) emptyList() else selected)
        pinLayer.show(symbols.pins(trackWaypoints, liveWaypoints, followedWaypoint))
        abovePinsLayer.show(symbols.puck(puckRoute?.points?.lastOrNull(), puckRoute?.headingDegrees()) + here + if (pinTapped) selected else emptyList())
        onTopLayer.show(symbols.onTopPin(followedWaypoint, liveWaypoints))
        map.render()
    }

    // Everything shown, unioned: bounds for both the pan clamp and the zoom-out floor.
    val extent = remember(routes, liveRoute, basemaps) {
        extentOf(routes, liveRoute, basemaps)
    }

    // Past a file's deepest zoom, magnification invents detail.
    LaunchedEffect(map, basemaps) {
        map.viewport().setMaxZoomLevel(basemaps.maxOfOrNull { it.maxViewZoom } ?: DEFAULT_MAX_ZOOM)
        map.updateMap(true)
    }

    // Zoom-out floor: everything fits on the tighter axis. Against the whole view, not the
    // uncovered part, since the sheet comes and goes.
    LaunchedEffect(map, extent, basemaps, viewSize) {
        if (extent == null) return@LaunchedEffect
        val size = viewSize ?: return@LaunchedEffect
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val viewport = map.viewport()
        val floor = MapPosition().apply { setByBoundingBox(extent, size.width, size.height) }.scale
        viewport.setMinScale(minOf(floor, viewport.maxScale))
    }

    // Clamp panning to the extent, measuring covered edges from the sheet/panel so anything
    // under one can be pulled out. Re-checked on every move since the range depends on scale.
    val cover = with(density) {
        val panel = panelWidth.roundToPx()
        Insets(
            left = if (layoutDirection == LayoutDirection.Ltr) panel else 0,
            top = 0,
            right = if (layoutDirection == LayoutDirection.Rtl) panel else 0,
            bottom = sheetHeight.roundToPx(),
        )
    }
    // The clamp admits the last framed view, or a track at the extent's edge couldn't be centred.
    var framedView by remember { mutableStateOf<BoundingBox?>(null) }
    val clampExtent = remember(extent, framedView) { extent?.including(framedView) }
    val currentExtent by rememberUpdatedState(extent)
    // Read fresh, not via recomposition: a framing move's own update must already see its view.
    fun currentClamp() = currentExtent?.including(framedView)
    val currentCover by rememberUpdatedState(cover)

    // Tracks first, else the maps. Too far apart, the camera stays put and the screen says so.
    fun frameAll(): Boolean {
        val usable = viewSize?.usable(insets) ?: return false
        val maxScale = map.viewport().maxScale
        val tracks = extentOf(currentRoutes, currentLiveRoute, emptyList())
        val boxes = (currentRoutes + listOfNotNull(currentLiveRoute)).mapNotNull { it.bounds?.toBoundingBox() }
        spread = tracks != null && tooFarApart(tracks, boxes, usable, maxScale, speckPx)
        if (spread) return true
        val target = tracks ?: extentOf(emptyList(), null, basemaps) ?: return false
        map.moveTo(fit(target, usable, insets, maxScale), currentClamp(), currentCover)
        hasFramed = true
        return true
    }

    // Centred in the uncovered part; with zoomIn, zooming in if too far out to place it. Admitted to the
    // clamp like a framed track, but only if some of the extent would show: a blank view
    // says nothing about where the user is.
    fun centreOn(point: TrackPoint, zoomIn: Boolean, camera: MapPosition = map.mapPosition): CentreResult {
        val size = viewSize?.takeIf { it.usable(insets) != null } ?: return CentreResult.NotLaidOut
        camera.setPosition(point.latitude, point.longitude)
        if (zoomIn) camera.setScale(maxOf(camera.scale, LOCATE_SCALE).coerceAtMost(map.viewport().maxScale))
        val mapSize = Tile.SIZE * camera.scale
        camera.x -= (insets.left - insets.right) / 2.0 / mapSize
        camera.y -= (insets.top - insets.bottom) / 2.0 / mapSize
        val view = camera.visibleBox(size)
        if (currentExtent?.intersects(view) == false) return CentreResult.OutOfBounds
        spread = false
        framedView = view
        map.moveTo(camera, currentClamp(), currentCover)
        return CentreResult.Centred
    }

    // For the screen reader actions: pinching needs two fingers, and nothing else zooms out.
    fun zoomBy(factor: Double): Boolean {
        val position = map.mapPosition
        val viewport = map.viewport()
        position.setScale((position.scale * factor).coerceIn(viewport.minScale, viewport.maxScale))
        val followed = followedNow.get()
        if (followed == null || centreOn(followed, zoomIn = false, position) != CentreResult.Centred) {
            map.moveTo(position, currentClamp(), currentCover)
        }
        return true
    }

    SideEffect {
        controller.zoomBy = ::zoomBy
        controller.showAll = ::frameAll
        controller.centre = { point, zoomIn -> centreOn(point, zoomIn) }
        followedNow.set(followed)
        // Zooms pivot on the view's centre then, so the followed point drifts least.
        map.eventLayer.setFixOnCenter(followed != null)
    }

    // Also as screen reader actions, so a TalkBack or switch user needn't turn the buttons on.
    // On the view: Compose semantics on an AndroidView don't reach its native node.
    val zoomIn = stringResource(R.string.map_zoom_in)
    val zoomOut = stringResource(R.string.map_zoom_out)
    val showAll = stringResource(R.string.map_show_all)
    DisposableEffect(mapView, controller, zoomIn, zoomOut, showAll) {
        val ids = listOf(
            zoomIn to controller::zoomIn,
            zoomOut to controller::zoomOut,
            showAll to controller::showAllTracks,
        ).map { (label, act) -> ViewCompat.addAccessibilityAction(mapView, label) { _, _ -> act() } }
        onDispose { ids.forEach { ViewCompat.removeAccessibilityAction(mapView, it) } }
    }

    AndroidView(
        factory = { mapView },
        update = { it.contentDescription = contentDescription },
        modifier = modifier.onSizeChanged { viewSize = it },
    )
    DisposableEffect(map) {
        val listener = Map.UpdateListener { event, position ->
            // Back onto the followed point after a pinch or a double tap's zoom, which pivot elsewhere.
            val followed = followedNow.get()
            if (followed != null && (event == Map.SCALE_EVENT || event == Map.ANIM_END || map.animator().isActive)) {
                centreOn(followed, zoomIn = false, MapPosition().apply { copy(position) })
            }
            reportScale(MercatorProjection.groundResolution(position))
            currentFollowedWaypoint?.let { reportWaypointAt(map.screenPosition(it.point, position)) }
            // Not before first framing: until then VTM sits at whole-world, and remembering that
            // would feed back in as initialCamera.
            if (hasFramed) {
                reportCamera(CameraSnapshot(position.latitude, position.longitude, position.zoom))
            }
            currentClamp()?.let { map.keepInView(it, currentCover) }
        }
        map.events.bind(listener)
        onDispose { map.events.unbind(listener) }
    }
    // Fresh too: launched for a new extent, it can run after a centring in the same frame
    // widened the clamp, and would pull the camera back to the old one.
    LaunchedEffect(map, clampExtent, viewSize, cover) {
        currentClamp()?.let { map.keepInView(it, cover) }
    }

    // Only used until a camera is remembered, i.e. once per process. Tracks first; the maps'
    // extent only once tracksLoading confirms there are none.
    val initialExtent = remember(routes, liveRoute, basemaps) {
        extentOf(routes, liveRoute, emptyList()) ?: extentOf(emptyList(), null, basemaps)
    }

    val reportSpread by rememberUpdatedState(onTooFarApartChange)
    LaunchedEffect(spread) { reportSpread(spread) }
    // Hiding or adding tracks may bring them close enough to frame after all.
    LaunchedEffect(routes, liveRoute, viewSize, insets) {
        if (spread) frameAll()
    }

    // Read once: the camera is republished as the map moves, so keying on it would restore
    // the map to its own current position.
    val rememberedCamera = remember { initialCamera }

    LaunchedEffect(map, initialExtent, tracksLoading, insets, viewSize) {
        if (hasFramed) return@LaunchedEffect

        val remembered = rememberedCamera
        if (remembered != null) {
            map.moveTo(
                MapPosition().setPosition(remembered.latitude, remembered.longitude).setZoom(remembered.zoom),
                currentClamp(),
                currentCover,
            )
            hasFramed = true
            return@LaunchedEffect
        }
        if (tracksLoading || initialExtent == null) return@LaunchedEffect
        frameAll()
    }

    // After the cold-start frame, so it wins when both land on the same composition.
    LaunchedEffect(map, frameTrackId, frameInsets, routes, viewSize) {
        val id = frameTrackId ?: return@LaunchedEffect
        val route = routes.firstOrNull { it.trackId == id } ?: return@LaunchedEffect
        val size = viewSize ?: return@LaunchedEffect
        val usable = size.usable(frameInsets) ?: return@LaunchedEffect
        // Null for a single point, which is left where the camera already is.
        extentOf(listOf(route), null, emptyList())?.let { target ->
            val position = fit(target, usable, frameInsets, map.viewport().maxScale)
            val view = position.visibleBox(size)
            spread = false
            framedView = view
            map.moveTo(position, currentClamp(), currentCover)
        }
        hasFramed = true
        framed()
    }

    // Nudge the least distance rather than re-centring, so scrubbing doesn't pan the map constantly.
    LaunchedEffect(map, focusedTrackId, selectedIndex, insets) {
        val at = routeFor(focusedTrackId, currentRoutes, currentLiveRoute)
            ?.points?.getOrNull(selectedIndex ?: -1) ?: return@LaunchedEffect
        map.nudgeIntoView(at, insets, followMargin)
    }
}

private fun routeFor(trackId: Long?, routes: List<RouteOverlay>, liveRoute: RouteOverlay?) =
    liveRoute?.takeIf { it.trackId == trackId } ?: routes.firstOrNull { it.trackId == trackId }

/** A [MapView] tied to the lifecycle; it owns a GL thread and surface that would otherwise leak. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    // Remembered first, so destroyed last, after the effects that unbind from it.
    DisposableEffect(mapView) { onDispose { mapView.onDestroy() } }
    LifecycleResumeEffect(mapView) {
        mapView.onResume()
        onPauseOrDispose { mapView.onPause() }
    }
    return mapView
}

/** About a fingertip. */
private const val TAP_REACH_DP = 40f

/** How far inside the uncovered box a scrubbed point is kept. */
private const val FOLLOW_MARGIN_DP = 36f

/** Across which every track framed together counts as a speck. */
private const val SPECK_DP = 16f

/** Street level, for finding yourself; 2^15. */
private const val LOCATE_SCALE = 32768.0

/** Used when no map is shown. */
private const val DEFAULT_MAX_ZOOM = 18

private fun markerLayer(map: Map) =
    ItemizedLayer(map, MarkerSymbol(AndroidBitmap(1, 1, 0), MarkerSymbol.HotspotPlace.CENTER))

/**
 * Replaces the layer's markers. Via addItems even when empty: removeAllItems(false) skips the
 * repopulate, so a layer emptied that way kept drawing its last markers.
 */
private fun ItemizedLayer.show(items: List<MarkerInterface>) {
    removeAllItems(false)
    addItems(items)
    update()
}

/** Camera moves asked of the map from outside it, wired once it's composed. */
@Stable
class MapController {
    internal var zoomBy: (Double) -> Boolean = { false }
    internal var showAll: () -> Boolean = { false }
    internal var centre: (TrackPoint, Boolean) -> CentreResult = { _, _ -> CentreResult.NotLaidOut }

    fun zoomIn() = zoomBy(2.0)
    fun zoomOut() = zoomBy(0.5)
    fun showAllTracks() = showAll()

    /** [zoomIn] to street level, if further out. */
    fun centreOn(point: TrackPoint, zoomIn: Boolean) = centre(point, zoomIn)
}

enum class CentreResult { Centred, OutOfBounds, NotLaidOut }

/** Drag per doubling of scale in a double-tap-and-drag zoom. */
private const val QUICK_ZOOM_DOUBLING_DP = 100f
