package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.map.OfflineMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oscim.android.MapView
import org.oscim.android.canvas.AndroidBitmap
import org.oscim.core.MapPosition
import org.oscim.core.MercatorProjection
import org.oscim.layers.marker.ItemizedLayer
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.map.Map

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
    tracksLoading: Boolean,
    /**
     * The recording drawing itself, kept apart from [routes] since it changes every few
     * seconds and they don't - growth this way costs only its own geometry.
     */
    liveRoute: RouteOverlay?,
    /** Which route the sheet is showing. */
    focusedTrackId: Long?,
    /** Highlighted point within [focusedTrackId]'s route, as an index into its points. */
    selectedIndex: Int?,
    markerColor: Color,
    /** The track whose last point is where the recorder is standing, or null. */
    puckTrackId: Long?,
    puckColor: Color,
    /**
     * Drawn on whichever track they belong to - the caller has already narrowed this to
     * the focused track's own and the live recording's, since a waypoint is a note on one
     * ride, not a landmark on the map itself.
     */
    waypoints: List<Waypoint>,
    onSelect: (trackId: Long, index: Int) -> Unit,
    onSelectNothing: () -> Unit,
    onSelectWaypoint: (Waypoint) -> Unit,
    /** A waypoint whose pin's screen position goes to [onFollowedWaypointMove] on every camera move. */
    followedWaypoint: Waypoint?,
    /**
     * The pin's tip, where the waypoint actually is - not the tap. Also called as soon as
     * [followedWaypoint] changes, before it is next laid out.
     */
    onFollowedWaypointMove: (Offset) -> Unit,
    /** Space kept clear of routes when framing, for the sheet and the controls. */
    contentPadding: PaddingValues,
    /**
     * How much of the bottom a sheet covers, zero with none open. Panning is measured from
     * its top edge, so nothing can be stranded underneath it.
     */
    sheetHeight: Dp,
    /** The same for a panel down the start edge, as landscape shows the track in. */
    panelWidth: Dp,
    backgroundColor: Color,
    landColor: Color,
    labelColor: Color,
    /** Reports how far a screen pixel spans on the ground, for the scale bar. */
    onScaleChange: (metersPerPixel: Double) -> Unit,
    /**
     * Where to put the camera on the cold-start frame, if the caller already knows - a
     * screen this composable doesn't survive navigating away from. Takes priority over
     * fitting to tracks or maps; only a fresh instance with nothing remembered yet falls
     * back to that.
     */
    initialCamera: CameraSnapshot?,
    /** Reports the camera's own position on every move, for [initialCamera] next time. */
    onCameraChange: (CameraSnapshot) -> Unit,
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
    val pinTipLength = remember(density) { with(density) { PIN_TIP_LENGTH_DP.dp.toPx() } }
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
                val waypointHit = pickWaypoint(
                    x, y, map, currentWaypoints, pinHeadRadius, pinTipLength, onTop = currentFollowedWaypoint,
                )
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

    // Before layout, so a newly followed pin's first frame is already placed - it may have
    // come from a scrub, with no camera move to report it.
    SideEffect {
        followedWaypoint?.let { reportWaypointAt(map.screenPosition(it.point)) }
    }

    DisposableEffect(map) {
        val listener = Map.UpdateListener { _, position ->
            reportScale(MercatorProjection.groundResolution(position))
            currentFollowedWaypoint?.let { reportWaypointAt(map.screenPosition(it.point, position)) }
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

    // Keyed on the shown files and the theme colours - either means a new render theme and
    // a new data source, which is the one thing worth rebuilding the tile layer for.
    LaunchedEffect(map, basemaps, backgroundColor, landColor, labelColor) {
        basemap?.let(map::detach)
        basemap = null
        map.attachBasemap(
            basemaps,
            BasemapColors(background = backgroundColor, land = landColor, label = labelColor),
            density,
        ) { basemap = it }
    }

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
        followedWaypoint,
    ) {
        // The recording is the usual answer for the puck and isn't in `routes`, so it is
        // asked first.
        val puckAt = (liveRoute?.takeIf { it.trackId == puckTrackId }
            ?: routes.firstOrNull { it.trackId == puckTrackId })
            ?.points?.lastOrNull()
        val markerAt = routes.firstOrNull { it.trackId == focusedTrackId }
            ?.points?.getOrNull(selectedIndex ?: -1)

        markerLayer.removeAllItems(false)
        markerLayer.addItems(symbols.items(waypoints, followedWaypoint, puckAt, markerAt))
        markerLayer.update()
        map.render()
    }

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
        map.nudgeIntoView(at, insets, followMargin)
    }
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

/** About a fingertip. In dp: a finger is a physical size, whatever the screen's density. */
private const val TAP_REACH_DP = 40f

/** How far inside the uncovered box a scrubbed point is kept. */
private const val FOLLOW_MARGIN_DP = 36f

/** The ceiling when no map is shown and there is nothing to derive one from. */
private const val DEFAULT_MAX_ZOOM = 18
