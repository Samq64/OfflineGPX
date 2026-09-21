package dev.samuelq.gpx.ui.map

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
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
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.data.map.OfflineMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

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
 * Routes over an offline basemap, or over nothing if none is imported. Projection, camera
 * and hit-testing all belong to MapLibre now; what's kept here is app-specific - which
 * track a tap selects, where the scrubber's marker sits, and minimal camera movement while
 * a chart is being dragged.
 */
@Composable
fun OfflineMapCanvas(
    routes: List<RouteOverlay>,
    basemaps: List<OfflineMap>,
    contentDescription: String,
    modifier: Modifier = Modifier,
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
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val mapView = rememberMapViewWithLifecycle(backgroundColor)

    // The renderer, once it exists - getMapAsync and setStyle are both async, so a
    // recomposition can arrive before either finishes.
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Framed once, when there's first something to frame - re-fitting on every route-set
    // change (every few seconds during a recording) would yank the map out from under a pan.
    var hasFramed by remember { mutableStateOf(false) }

    // Reported outwards for the scale bar. Held as state and deliberately never read `by`
    // in this function: the camera writes it every frame of a pan.
    val metersPerPixel = remember { mutableDoubleStateOf(0.0) }

    val currentRoutes by rememberUpdatedState(routes)
    val currentLiveRoute by rememberUpdatedState(liveRoute)
    val select by rememberUpdatedState(onSelect)
    val reportScale by rememberUpdatedState(onScaleChange)
    val selectNothing by rememberUpdatedState(onSelectNothing)

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

    // --- Renderer handle -----------------------------------------------------------

    DisposableEffect(mapView) {
        mapView.getMapAsync { ready ->
            ready.uiSettings.apply {
                // No rotation and no tilt: a route drawn north-up is a shape people
                // recognise, and neither gesture adds anything a pinch doesn't cover.
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                // Both of MapLibre's own badges are off - attribution is listed per-map on
                // the settings screen instead. See PmtilesMetadata.
                isAttributionEnabled = false
                isLogoEnabled = false
            }
            // Both, not just idle: the bar has to track a pinch while it happens.
            val publishScale = {
                val latitude = ready.cameraPosition.target?.latitude ?: 0.0
                val scale = ready.projection.getMetersPerPixelAtLatitude(latitude)
                metersPerPixel.doubleValue = scale
                reportScale(scale)
            }
            ready.addOnCameraMoveListener(publishScale)
            ready.addOnCameraIdleListener(publishScale)
            publishScale()

            ready.addOnMapClickListener { tapped ->
                val hit = ready.pick(tapped, currentRoutes, currentLiveRoute)
                if (hit == null) selectNothing() else select(hit.first, hit.second)
                true
            }
            map = ready
        }
        onDispose { map = null }
    }

    // --- Style, which is where the basemap lives -----------------------------------

    // Keyed on the archive's path - swapping a basemap means reloading the style, which
    // discards every source and layer, so the route layers are re-added below, keyed on
    // the new style object.
    LaunchedEffect(map, basemaps, backgroundColor, landColor, labelColor) {
        val ready = map ?: return@LaunchedEffect
        style = null
        ready.setStyle(
            Style.Builder().fromJson(
                MapStyle.json(
                    maps = basemaps,
                    background = backgroundColor,
                    land = landColor,
                    label = labelColor,
                )
            )
        ) { loaded ->
            loaded.addRouteLayers(markerColor, markerRingColor, puckColor, labelColor, backgroundColor)
            style = loaded
        }
    }

    // --- What is drawn -------------------------------------------------------------

    // Every one of these builds its GeoJSON off the main thread - a long ride is a few
    // hundred thousand `Point` allocations, which don't belong on the frame the user sees.
    // The handover back is on the main thread, since a source belongs to the renderer.
    LaunchedEffect(style, basemaps) {
        val loaded = style ?: return@LaunchedEffect
        val (outline, mask) = withContext(Dispatchers.Default) {
            basemaps.toCoverageOutline() to basemaps.toOutsideMask()
        }
        loaded.getSourceAs<GeoJsonSource>(SOURCE_COVERAGE)?.setGeoJson(outline)
        loaded.getSourceAs<GeoJsonSource>(SOURCE_MASK)?.setGeoJson(mask)
    }

    LaunchedEffect(style, routes, focusedTrackId) {
        val loaded = style ?: return@LaunchedEffect
        val collection = withContext(Dispatchers.Default) {
            routes.toFeatureCollection(focusedTrackId)
        }
        loaded.getSourceAs<GeoJsonSource>(SOURCE_ROUTES)?.setGeoJson(collection)
    }

    // Its own effect: runs every few seconds for the length of a ride, touching nothing else.
    LaunchedEffect(style, liveRoute) {
        val loaded = style ?: return@LaunchedEffect
        val collection = withContext(Dispatchers.Default) {
            listOfNotNull(liveRoute).toFeatureCollection(focusedTrackId = null)
        }
        loaded.getSourceAs<GeoJsonSource>(SOURCE_TRACE)?.setGeoJson(collection)
    }

    LaunchedEffect(style, routes, liveRoute, puckTrackId) {
        val loaded = style ?: return@LaunchedEffect
        // The recording is the usual answer and isn't in `routes`, so it's asked first.
        val overlay = liveRoute?.takeIf { it.trackId == puckTrackId }
            ?: routes.firstOrNull { it.trackId == puckTrackId }
        val at = overlay?.points?.lastOrNull()
        loaded.getSourceAs<GeoJsonSource>(SOURCE_PUCK)?.setGeoJson(at.toFeatureCollection())
    }

    LaunchedEffect(style, routes, focusedTrackId, selectedIndex) {
        val loaded = style ?: return@LaunchedEffect
        val at = routes.firstOrNull { it.trackId == focusedTrackId }
            ?.points?.getOrNull(selectedIndex ?: -1)
        loaded.getSourceAs<GeoJsonSource>(SOURCE_MARKER)?.setGeoJson(at.toFeatureCollection())
    }

    // --- Where it is looked at from ------------------------------------------------

    // Every track and every shown map: both what the opening view frames and the box the
    // camera is kept inside afterwards.
    val extent = remember(routes, liveRoute, basemaps) {
        extentOf(routes, liveRoute, basemaps)
    }

    LaunchedEffect(map, extent, insets) {
        val ready = map ?: return@LaunchedEffect
        if (extent == null) return@LaunchedEffect

        // Zooming out is limited to where everything is already on screen - beyond that is
        // nothing but flat background, with no way back.
        val padding = intArrayOf(
            insets.left + EDGE_PADDING_PX,
            insets.top + EDGE_PADDING_PX,
            insets.right + EDGE_PADDING_PX,
            insets.bottom + EDGE_PADDING_PX,
        )
        val fitted = ready.getCameraForLatLngBounds(extent, padding)

        // A cap on the way in too - MapLibre will happily magnify an archive's deepest
        // zoom thirty times over, drawing detail that doesn't exist convincingly.
        val deepest = basemaps.maxOfOrNull { it.header.maxZoom } ?: DEFAULT_MAX_ZOOM
        val ceiling = (deepest + OVERZOOM_ALLOWANCE).toDouble()

        fitted?.zoom?.let { floor ->
            ready.setMinZoomPreference(floor.coerceAtMost(ceiling))
        }
        ready.setMaxZoomPreference(ceiling)
        ready.setLatLngBoundsForCameraTarget(extent)

        if (!hasFramed) {
            // Tracks frame the view when there are any; failing that, the shown maps do.
            ready.moveCamera(
                CameraUpdateFactory.newLatLngBounds(
                    extent, padding[0], padding[1], padding[2], padding[3],
                )
            )
            hasFramed = true
        }
    }

    // Scrubbing a chart moves the marker; moves the camera the least it can rather than
    // re-centring, which would turn reading a chart into a ride through a moving map.
    LaunchedEffect(map, focusedTrackId, selectedIndex, insets) {
        val ready = map ?: return@LaunchedEffect
        val at = currentRoutes.firstOrNull { it.trackId == focusedTrackId }
            ?.points?.getOrNull(selectedIndex ?: -1) ?: return@LaunchedEffect
        ready.nudgeIntoView(LatLng(at.latitude, at.longitude), insets, mapView.width, mapView.height)
    }
}

/** Pixels kept clear on each edge, for whatever is floating over the map. */
private class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * A [MapView] that follows the composition's lifecycle - MapLibre's view holds an EGL
 * context and native renderer, and missing a callback leaks the surface or draws to a gone one.
 */
@Composable
private fun rememberMapViewWithLifecycle(initialBackground: Color): MapView {
    val context = LocalContext.current
    val mapView = remember {
        // CJK glyphs come from the device's own font, not bundled ranges - the Han ranges
        // alone dwarf the ~280 KB of Latin actually shipped.
        val options = MapLibreMapOptions.createFromAttributes(context)
            .localIdeographFontFamily("sans-serif")
            // MapLibre's default here is a pale beige painted before any style loads,
            // which on a dark theme reads as the map stuck in light mode.
            .foregroundLoadColor(initialBackground.toArgb())
        MapView(context, options).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

/**
 * The sources and layers the routes are drawn from, added once per style load. One source
 * for every *saved* route, not one per track - colour and width are feature properties, so
 * a hundred tracks are still one layer and one draw. The recording is the exception: its
 * own source and layer, since it changes every few seconds and the saved tracks don't.
 */
private fun Style.addRouteLayers(
    marker: Color,
    markerRing: Color,
    puck: Color,
    coverage: Color,
    background: Color,
) {
    addSource(GeoJsonSource(SOURCE_MASK))
    addSource(GeoJsonSource(SOURCE_COVERAGE))
    addSource(GeoJsonSource(SOURCE_ROUTES))
    addSource(GeoJsonSource(SOURCE_TRACE))
    addSource(GeoJsonSource(SOURCE_MARKER))
    addSource(GeoJsonSource(SOURCE_PUCK))

    // Everything the archive doesn't contain, painted to match the flat background so it
    // reads as "edge of the data" rather than "map that failed to load". A mask is
    // unavoidable: MapLibre falls back to a *parent* tile wherever one is missing, and a
    // low-zoom parent carries data past the real edge regardless of zoom floor. Cut to the
    // true coverage, not the bounding box, since a polygon extract fills barely half its
    // box. Below the labels, which are clipped by a `within` filter instead - one layer
    // can't be both above the labels and below the routes.
    val mask = FillLayer(LAYER_MASK, SOURCE_MASK)
        .withProperties(PropertyFactory.fillColor(background.toArgb()))

    // The edge of the archive, under the routes. A PMTiles extract carries the whole world
    // at low zoom, so without a line marking where detail ends, zooming out looks like the
    // map degrading rather than leaving the cut area.
    val coverageOutline = LineLayer(LAYER_COVERAGE, SOURCE_COVERAGE).withProperties(
            PropertyFactory.lineColor(coverage.toArgb()),
            PropertyFactory.lineWidth(COVERAGE_WIDTH),
            PropertyFactory.lineOpacity(COVERAGE_OPACITY),
            PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
        )

    val routes = LineLayer(LAYER_ROUTES, SOURCE_ROUTES).withProperties(
        PropertyFactory.lineColor(Expression.get(PROPERTY_COLOR)),
        PropertyFactory.lineWidth(Expression.get(PROPERTY_WIDTH)),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
    // Same paint as `routes`: a reader shouldn't be able to tell which layer a line is in.
    val trace = LineLayer(LAYER_TRACE, SOURCE_TRACE).withProperties(
        PropertyFactory.lineColor(Expression.get(PROPERTY_COLOR)),
        PropertyFactory.lineWidth(Expression.get(PROPERTY_WIDTH)),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
    // Under the labels, not over them: a route is a thick opaque stroke, and text is the
    // one thing it must never cover. Falls back to appending when there's no basemap and
    // so no label layers. Each insert lands just below the labels, so this order stacks
    // mask - outline - routes - trace, all still under the text.
    if (getLayer(MapStyle.lowestLabelLayer(0)) != null) {
        addLayerBelow(mask, MapStyle.lowestLabelLayer(0))
        addLayerBelow(coverageOutline, MapStyle.lowestLabelLayer(0))
        addLayerBelow(routes, MapStyle.lowestLabelLayer(0))
        addLayerBelow(trace, MapStyle.lowestLabelLayer(0))
    } else {
        // No basemap: there is nothing to mask and no labels to sit beneath.
        addLayer(coverageOutline)
        addLayer(routes)
        addLayer(trace)
    }

    // Bigger than the scrub marker and wearing a halo: one points at a moment in a ride
    // that's over, this is the only thing on screen about right now.
    addLayer(
        CircleLayer(LAYER_PUCK_HALO, SOURCE_PUCK).withProperties(
            PropertyFactory.circleRadius(14f),
            PropertyFactory.circleColor(puck.toArgb()),
            PropertyFactory.circleOpacity(PUCK_HALO_ALPHA),
        )
    )
    addLayer(
        CircleLayer(LAYER_PUCK, SOURCE_PUCK).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(puck.toArgb()),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(markerRing.toArgb()),
        )
    )

    // Last, so the point being read about is never underneath anything.
    addLayer(
        CircleLayer(LAYER_MARKER, SOURCE_MARKER).withProperties(
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleColor(marker.toArgb()),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(markerRing.toArgb()),
        )
    )
}

/**
 * Every route as line features, one per segment - the gap between segments is signal loss
 * and nothing should be drawn across it. Each feature carries its track's id, so a tap on
 * any segment resolves to the whole track.
 */
private fun List<RouteOverlay>.toFeatureCollection(focusedTrackId: Long?): FeatureCollection {
    val features = ArrayList<Feature>()
    forEach { route ->
        val width = if (route.trackId == focusedTrackId) FOCUSED_WIDTH else ROUTE_WIDTH
        val starts = route.segmentStartIndices.toSortedSet().toList()
        val boundaries = (if (starts.firstOrNull() == 0) starts else listOf(0) + starts) +
            route.points.size

        for (i in 0 until boundaries.size - 1) {
            val from = boundaries[i]
            val to = boundaries[i + 1]
            // A single position isn't a line (still drawn as the puck if live), and a
            // one-point LineString isn't valid GeoJSON.
            if (to - from < 2) continue

            val coordinates = ArrayList<Point>(to - from)
            for (index in from until to) {
                val point = route.points[index]
                coordinates.add(Point.fromLngLat(point.longitude, point.latitude))
            }
            features.add(
                Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply {
                    addNumberProperty(PROPERTY_TRACK_ID, route.trackId)
                    addStringProperty(PROPERTY_COLOR, route.color.css())
                    addNumberProperty(PROPERTY_WIDTH, width)
                }
            )
        }
    }
    return FeatureCollection.fromFeatures(features)
}

/**
 * The archive's real outline as line segments. Falls back to the declared bounding box
 * when the directory couldn't be walked, correct for the common rectangular-extract case.
 */
private fun List<OfflineMap>.toCoverageOutline(): FeatureCollection {

    // One feature per edge - already disjoint, and stitching into rings is no gain for a
    // dashed stroke.
    val features = flatMap { map ->
        if (map.coverage.isEmpty) {
            val h = map.header
            listOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(h.minLongitude, h.minLatitude),
                            Point.fromLngLat(h.maxLongitude, h.minLatitude),
                            Point.fromLngLat(h.maxLongitude, h.maxLatitude),
                            Point.fromLngLat(h.minLongitude, h.maxLatitude),
                            Point.fromLngLat(h.minLongitude, h.minLatitude),
                        )
                    )
                )
            )
        } else {
            map.coverage.edges.map { edge ->
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(edge.fromLongitude, edge.fromLatitude),
                            Point.fromLngLat(edge.toLongitude, edge.toLatitude),
                        )
                    )
                )
            }
        }
    }
    return FeatureCollection.fromFeatures(features)
}

/**
 * The whole world with the archive's real coverage cut out of it. One hole per horizontal
 * run of tiles, not per tile - runs in adjacent rows share edges exactly, cutting a
 * seamless hole of any shape.
 */
private fun List<OfflineMap>.toOutsideMask(): FeatureCollection {
    if (isEmpty()) return FeatureCollection.fromFeatures(emptyList())

    val outer = listOf(
        Point.fromLngLat(-180.0, -MERCATOR_LIMIT),
        Point.fromLngLat(180.0, -MERCATOR_LIMIT),
        Point.fromLngLat(180.0, MERCATOR_LIMIT),
        Point.fromLngLat(-180.0, MERCATOR_LIMIT),
        Point.fromLngLat(-180.0, -MERCATOR_LIMIT),
    )

    // One hole set per shown map; they never overlap, so the holes can't either.
    val holes = flatMap { map ->
        if (map.coverage.isEmpty) {
            listOf(
                holeOf(
                    map.header.minLongitude, map.header.minLatitude,
                    map.header.maxLongitude, map.header.maxLatitude,
                )
            )
        } else {
            map.coverage.runs.map {
                holeOf(it.westLongitude, it.southLatitude, it.eastLongitude, it.northLatitude)
            }
        }
    }

    return FeatureCollection.fromFeatures(
        listOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(outer) + holes)))
    )
}

/** Wound opposite to the outer ring, which is what makes it a hole and not an island. */
private fun holeOf(west: Double, south: Double, east: Double, north: Double): List<Point> =
    listOf(
        Point.fromLngLat(west, south),
        Point.fromLngLat(west, north),
        Point.fromLngLat(east, north),
        Point.fromLngLat(east, south),
        Point.fromLngLat(west, south),
    )

/** One position, or an empty collection - which is how a marker is hidden. */
private fun TrackPoint?.toFeatureCollection(): FeatureCollection =
    FeatureCollection.fromFeatures(
        if (this == null) {
            emptyList()
        } else {
            listOf(Feature.fromGeometry(Point.fromLngLat(longitude, latitude)))
        }
    )

/**
 * Which track was tapped, and where along it. The track comes from the renderer -
 * [MapLibreMap.queryRenderedFeatures] reports what was actually drawn under those pixels,
 * accounting for line width and camera - and the index is then found by scanning that
 * track's own positions.
 */
private fun MapLibreMap.pick(
    tapped: LatLng,
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
): Pair<Long, Int>? {
    val screen = projection.toScreenLocation(tapped)
    val box = RectF(
        screen.x - TAP_REACH_PX,
        screen.y - TAP_REACH_PX,
        screen.x + TAP_REACH_PX,
        screen.y + TAP_REACH_PX,
    )
    // Both layers - a tap on the recording resolves to a track id the caller ignores,
    // which is the point: it must not read as a tap on the bare map either.
    val trackId = queryRenderedFeatures(box, LAYER_ROUTES, LAYER_TRACE)
        .firstNotNullOfOrNull { it.getNumberProperty(PROPERTY_TRACK_ID)?.toLong() }
        ?: return null

    val route = liveRoute?.takeIf { it.trackId == trackId }
        ?: routes.firstOrNull { it.trackId == trackId }
        ?: return null
    val index = route.points.indexOfNearest(tapped) ?: return null
    return trackId to index
}

/**
 * The position closest to [target], as an index. A plain scan, once per tap over one
 * track, in degrees squared rather than metres - fine since it's only a comparison.
 */
private fun List<TrackPoint>.indexOfNearest(target: LatLng): Int? {
    if (isEmpty()) return null
    var best = 0
    var bestDistance = Double.MAX_VALUE
    forEachIndexed { index, point ->
        val dx = point.longitude - target.longitude
        val dy = point.latitude - target.latitude
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

/**
 * Everything worth looking at, as one box: every route's extent and every shown map's.
 * Null when there's neither - a fresh install with nothing to frame.
 */
internal fun extentOf(
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
    basemaps: List<OfflineMap>,
): LatLngBounds? {
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

    return runCatching {
        LatLngBounds.from(latNorth = north, lonEast = east, latSouth = south, lonWest = west)
    }.getOrNull()
}

/**
 * Pans the least it can to bring [target] inside the uncovered box, or not at all -
 * expressed as a camera-centre move so the amount moved equals the amount out of bounds.
 */
private fun MapLibreMap.nudgeIntoView(target: LatLng, insets: Insets, width: Int, height: Int) {
    if (width <= 0 || height <= 0) return

    val at = projection.toScreenLocation(target)
    val left = (insets.left + FOLLOW_MARGIN_PX)
    val top = (insets.top + FOLLOW_MARGIN_PX)
    val right = width - insets.right - FOLLOW_MARGIN_PX
    val bottom = height - insets.bottom - FOLLOW_MARGIN_PX
    if (left >= right || top >= bottom) return

    val dx = when {
        at.x < left -> left - at.x
        at.x > right -> right - at.x
        else -> 0f
    }
    val dy = when {
        at.y < top -> top - at.y
        at.y > bottom -> bottom - at.y
        else -> 0f
    }
    if (dx == 0f && dy == 0f) return

    // Moving the picture right by dx means moving the camera left by dx.
    val centre = projection.toScreenLocation(cameraPosition.target ?: return)
    val moved = projection.fromScreenLocation(PointF(centre.x - dx, centre.y - dy))
    // Not animated: this is answering a drag happening right now, and an easing curve
    // would arrive after the finger had moved on.
    moveCamera(CameraUpdateFactory.newLatLng(moved))
}

private fun Color.css(): String = String.format("#%06X", 0xFFFFFF and toArgb())

private const val SOURCE_MASK = "coverage-mask"
private const val SOURCE_COVERAGE = "coverage"
private const val SOURCE_ROUTES = "routes"
private const val SOURCE_TRACE = "trace"
private const val SOURCE_MARKER = "route-marker"
private const val SOURCE_PUCK = "route-puck"

private const val LAYER_MASK = "coverage-mask-fill"
private const val LAYER_COVERAGE = "coverage-outline"
private const val LAYER_ROUTES = "routes-line"
private const val LAYER_TRACE = "trace-line"
private const val LAYER_MARKER = "route-marker-circle"
private const val LAYER_PUCK = "route-puck-circle"
private const val LAYER_PUCK_HALO = "route-puck-halo"

private const val PROPERTY_TRACK_ID = "trackId"
private const val PROPERTY_COLOR = "color"
private const val PROPERTY_WIDTH = "width"

private const val ROUTE_WIDTH = 4f
private const val FOCUSED_WIDTH = 6f
private const val PUCK_HALO_ALPHA = 0.24f

/** Visible as a boundary, not as a feature of the landscape. */
private const val COVERAGE_WIDTH = 1.5f
private const val COVERAGE_OPACITY = 0.55f

/** The latitude Web Mercator stops at. Beyond it the projection has no answer. */
private const val MERCATOR_LIMIT = 85.051129

/** About a fingertip, in pixels rather than dp because it is a query box, not a drawing. */
private const val TAP_REACH_PX = 44f
private const val FOLLOW_MARGIN_PX = 96f
private const val EDGE_PADDING_PX = 64

/** How far past an archive's deepest zoom the camera may still go. */
private const val OVERZOOM_ALLOWANCE = 2

/** The ceiling when no map is shown and there is nothing to derive one from. */
private const val DEFAULT_MAX_ZOOM = 16
