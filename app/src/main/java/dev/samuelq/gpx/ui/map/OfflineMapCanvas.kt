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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.map.PmtilesHeader
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One track as the map should draw it: the positions themselves, in degrees.
 *
 * Deliberately not a projected shape any more. The old canvas normalised every route into
 * a shared unit square because it had to invent a coordinate space; a real map already has
 * one, and handing it anything other than latitude and longitude would mean projecting
 * twice and disagreeing with the ground.
 */
@Immutable
class RouteOverlay(
    val trackId: Long,
    val points: List<TrackPoint>,
    /** Indices into [points] where a new polyline starts. Never drawn across. */
    val segmentStartIndices: IntArray,
    val color: Color,
)

/**
 * Routes over an offline basemap, or over nothing if none is imported.
 *
 * This replaces a hand-rolled projection, a hand-rolled camera with its own pan clamp, and
 * a hand-rolled hit test. All three now belong to MapLibre, which is the point of taking
 * the dependency: the projection is Web Mercator and agrees with the tiles, the camera
 * handles pinch, rotate and fling without this file having an opinion, and a tap is
 * answered by asking the renderer what it actually drew at that pixel rather than by
 * measuring distance to every vertex.
 *
 * What is kept is the part that is about *this* app: which track a tap selects, where the
 * scrubber's marker sits, and the rule that the map moves as little as possible while a
 * chart is being dragged.
 */
@Composable
fun OfflineMapCanvas(
    routes: List<RouteOverlay>,
    basemaps: List<OfflineMap>,
    contentDescription: String,
    modifier: Modifier = Modifier,
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
    /** The hairline of the graph paper shown where no archive covers the ground. */
    gridColor: Color = Color.Unspecified,
    /** Reports how far a screen pixel spans on the ground, for the scale bar. */
    onScaleChange: (metersPerPixel: Double) -> Unit = {},
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val mapView = rememberMapViewWithLifecycle(backgroundColor)

    // The renderer, once it exists. Everything below waits on this rather than assuming
    // it: getMapAsync and setStyle are both asynchronous, and a recomposition can easily
    // arrive before either has finished.
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Framed once, when there is first something to frame. Re-fitting whenever the route
    // set changed would yank the map out from under someone who had just panned somewhere
    // - and the route set changes every few seconds during a recording.
    var hasFramed by remember { mutableStateOf(false) }

    // Kept here as well as reported outwards, because the graph paper is drawn from it:
    // a grid square is one scale-bar length, so the two always agree.
    var metersPerPixel by remember { mutableDoubleStateOf(0.0) }
    val units = LocalFormatters.current.units

    val currentRoutes by rememberUpdatedState(routes)
    val select by rememberUpdatedState(onSelect)
    val reportScale by rememberUpdatedState(onScaleChange)
    val selectNothing by rememberUpdatedState(onSelectNothing)

    // One grid square is exactly one scale-bar length, so the graph paper stops being
    // decoration and becomes something you can count across. Rounded to whole pixels
    // because that is the unit the pattern is drawn in, and because it means the bitmap is
    // rebuilt only when it would actually look different rather than on every frame of a
    // pinch.
    val gridSquarePx = with(density) {
        if (metersPerPixel <= 0.0 || !metersPerPixel.isFinite()) {
            FallbackGridSquare.roundToPx()
        } else {
            val span = roundDistance(metersPerPixel * MaxBarWidth.toPx(), units)
            (span / metersPerPixel).roundToInt().coerceIn(MinGridPx, MaxGridPx)
        }
    }

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
                // No rotation and no tilt. A route drawn north-up is a shape people
                // recognise; a rotated one is a shape they have to re-read, and neither
                // gesture has a use here that a pinch does not already cover.
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                // Both of MapLibre's own badges are off. The OpenStreetMap credit the
                // licence asks for is still shown - see AttributionBadge, which says the
                // same thing in a corner the map can spare.
                isAttributionEnabled = false
                isLogoEnabled = false
            }
            // Both, not just idle: the bar has to track a pinch while it happens, or it
            // reads as broken for as long as the finger is down.
            val publishScale = {
                val latitude = ready.cameraPosition.target?.latitude ?: 0.0
                val scale = ready.projection.getMetersPerPixelAtLatitude(latitude)
                metersPerPixel = scale
                reportScale(scale)
            }
            ready.addOnCameraMoveListener(publishScale)
            ready.addOnCameraIdleListener(publishScale)
            publishScale()

            ready.addOnMapClickListener { tapped ->
                val hit = ready.pick(tapped, currentRoutes)
                if (hit == null) selectNothing() else select(hit.first, hit.second)
                true
            }
            map = ready
        }
        onDispose { map = null }
    }

    // --- Style, which is where the basemap lives -----------------------------------

    // Keyed on the archive's path: a different basemap is a different style, and swapping
    // one in means reloading it. Reloading a style discards every source and layer added
    // to the old one, so the route layers are re-added by the effect below, which is
    // keyed on the style object it has to add them to.
    LaunchedEffect(
        map, basemaps, backgroundColor, landColor, labelColor, gridColor,
    ) {
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
            loaded.addRouteLayers(markerColor, markerRingColor, puckColor, labelColor)
            style = loaded
        }
    }

    LaunchedEffect(style, gridSquarePx, backgroundColor, gridColor) {
        style?.addGridBackground(gridSquarePx, backgroundColor, gridColor)
    }

    // --- What is drawn -------------------------------------------------------------

    LaunchedEffect(style, basemaps) {
        val loaded = style ?: return@LaunchedEffect
        loaded.getSourceAs<GeoJsonSource>(SOURCE_COVERAGE)
            ?.setGeoJson(basemaps.toCoverageOutline())
        loaded.getSourceAs<GeoJsonSource>(SOURCE_MASK)?.setGeoJson(basemaps.toOutsideMask())
    }

    LaunchedEffect(style, routes, focusedTrackId) {
        val loaded = style ?: return@LaunchedEffect
        (loaded.getSourceAs<GeoJsonSource>(SOURCE_ROUTES))
            ?.setGeoJson(routes.toFeatureCollection(focusedTrackId))
    }

    LaunchedEffect(style, routes, puckTrackId) {
        val loaded = style ?: return@LaunchedEffect
        val at = routes.firstOrNull { it.trackId == puckTrackId }?.points?.lastOrNull()
        loaded.getSourceAs<GeoJsonSource>(SOURCE_PUCK)?.setGeoJson(at.toFeatureCollection())
    }

    LaunchedEffect(style, routes, focusedTrackId, selectedIndex) {
        val loaded = style ?: return@LaunchedEffect
        val at = routes.firstOrNull { it.trackId == focusedTrackId }
            ?.points?.getOrNull(selectedIndex ?: -1)
        loaded.getSourceAs<GeoJsonSource>(SOURCE_MARKER)?.setGeoJson(at.toFeatureCollection())
    }

    // --- Where it is looked at from ------------------------------------------------

    // What there is to look at: every track and every shown map. This is both what the
    // opening view frames and the box the camera is afterwards kept inside.
    val extent = remember(routes, basemaps) { extentOf(routes, basemaps) }

    LaunchedEffect(map, extent, insets) {
        val ready = map ?: return@LaunchedEffect
        if (extent == null) return@LaunchedEffect

        // Zooming out is limited to the point where everything is already on screen.
        // Beyond that there is nothing to see but graph paper, and pinching into it is how
        // you end up lost on a blank grid with no way back but closing the app.
        val padding = intArrayOf(
            insets.left + EDGE_PADDING_PX,
            insets.top + EDGE_PADDING_PX,
            insets.right + EDGE_PADDING_PX,
            insets.bottom + EDGE_PADDING_PX,
        )
        val fitted = ready.getCameraForLatLngBounds(extent, padding)

        // A cap on the way in as well. MapLibre will happily go past zoom 20, where an
        // archive that stops at 15 is being magnified thirty times and every road is a
        // smooth fat band - detail that does not exist, drawn convincingly.
        val deepest = basemaps.maxOfOrNull { it.header.maxZoom } ?: DEFAULT_MAX_ZOOM
        val ceiling = (deepest + OVERZOOM_ALLOWANCE).toDouble()

        fitted?.zoom?.let { floor ->
            ready.setMinZoomPreference(floor.coerceAtMost(ceiling))
        }
        ready.setMaxZoomPreference(ceiling)
        ready.setLatLngBoundsForCameraTarget(extent)

        if (!hasFramed) {
            // Tracks are what the reader came for, so they frame it when there are any.
            // Failing that the shown maps do: someone with a basemap and no tracks near it
            // should be looking at their map, not at a speck on a sheet of graph paper.
            ready.moveCamera(
                CameraUpdateFactory.newLatLngBounds(
                    extent, padding[0], padding[1], padding[2], padding[3],
                )
            )
            hasFramed = true
        }
    }

    // Scrubbing a chart moves the marker, and a marker behind the sheet is the whole
    // argument for the sheet wasted. Moves the least it can rather than re-centring:
    // re-centring on every scrub frame turns reading a chart into a ride through a moving
    // map, and the surroundings that made the marker mean something go with it.
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
 * A [MapView] that follows the composition's lifecycle.
 *
 * MapLibre's view holds an EGL context and a native renderer, neither of which is garbage.
 * Miss one of these callbacks and it leaks the surface, or draws to one that is gone.
 */
@Composable
private fun rememberMapViewWithLifecycle(initialBackground: Color): MapView {
    val context = LocalContext.current
    val mapView = remember {
        // CJK glyphs are drawn from the device's own font rather than from bundled ranges.
        // The alternative is shipping them: the Han ranges alone are most of the 6.2 MB
        // of a complete Noto set, against the ~280 KB of Latin that is actually bundled -
        // and the phone already has a font that can draw them.
        val options = MapLibreMapOptions.createFromAttributes(context)
            .localIdeographFontFamily("sans-serif")
            // MapLibre's own default here is a pale beige, painted before any style has
            // loaded and visible through anything the style fails to cover. On a dark
            // theme that reads as the map being stuck in light mode, which is exactly
            // what it looks like - so it starts as the theme's own background instead.
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
 * Graph paper under everything, for the ground no archive covers.
 *
 * Two situations, one answer. With nothing imported the whole screen is empty, and a flat
 * rectangle reads as a map that failed to load rather than an app waiting to be given a
 * map. With an archive imported it still matters, because panning past the edge of a
 * town-sized extract is normal and the boundary should look like the edge of the *data*
 * rather than the edge of the world.
 *
 * Applied after the style loads rather than declared in its JSON, so the ordering is
 * guaranteed: the image exists before anything references it. A `background-pattern`
 * naming an image that has not been added yet renders as nothing at all, and "nothing"
 * here is the whole screen - so the JSON keeps its plain colour and this upgrades it only
 * once the upgrade is known to work.
 */
private fun Style.addGridBackground(squarePx: Int, background: Color, line: Color) {
    if (squarePx <= 0) return
    addImage(IMAGE_GRID, gridTile(squarePx, background, line))
    // Leaves the flat colour in place if the layer is missing for any reason.
    (getLayerAs<BackgroundLayer>(MapStyle.BACKGROUND_LAYER))
        ?.setProperties(PropertyFactory.backgroundPattern(IMAGE_GRID))
}

/**
 * One tile of the grid.
 *
 * Drawn on the top and left edges only. A line on all four would be drawn twice over
 * where tiles meet, at double weight, which on a faint hairline is the difference between
 * a grid and a plaid.
 */
private fun gridTile(squarePx: Int, background: Color, line: Color): Bitmap {
    val bitmap = createBitmap(squarePx, squarePx)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(background.toArgb())

    val paint = Paint().apply {
        color = line.toArgb()
        strokeWidth = (squarePx / GRID_LINE_DIVISOR).coerceAtLeast(1f)
        isAntiAlias = false
    }
    val edge = paint.strokeWidth / 2f
    canvas.drawLine(0f, edge, squarePx.toFloat(), edge, paint)
    canvas.drawLine(edge, 0f, edge, squarePx.toFloat(), paint)
    return bitmap
}

/**
 * The sources and layers the routes are drawn from, added once per style load.
 *
 * One source for every route rather than one per track: a colour is a feature property and
 * a width is an expression over one, so a hundred tracks are still one layer and one draw.
 * The alternative - a source and a layer per track - re-enters native code once per track
 * on every change, which during a recording is every few seconds.
 */
private fun Style.addRouteLayers(marker: Color, markerRing: Color, puck: Color, coverage: Color) {
    addSource(GeoJsonSource(SOURCE_MASK))
    addSource(GeoJsonSource(SOURCE_COVERAGE))
    addSource(GeoJsonSource(SOURCE_ROUTES))
    addSource(GeoJsonSource(SOURCE_MARKER))
    addSource(GeoJsonSource(SOURCE_PUCK))

    // Everything the archive does not contain, painted as the graph paper that stands for
    // "no map here" - because that is what it is.
    //
    // A mask is unavoidable. MapLibre falls back to a *parent* tile wherever a tile is
    // missing, and a low-zoom parent carries data for ground the extract never included,
    // so detail bleeds well past the real edge no matter what zoom floor the layers have.
    // The only way to stop it is to paint over it.
    //
    // Cut to the true coverage, not the bounding box: an extract from a drawn polygon
    // fills barely half its box, and a rectangular hole would both reveal ground the file
    // lacks and hide ground it has.
    //
    // Deliberately *below* the labels, which are clipped by a `within` filter instead -
    // the routes have to stay under the text, and one layer cannot be above the labels and
    // below the routes at once.
    val mask = FillLayer(LAYER_MASK, SOURCE_MASK)
        .withProperties(PropertyFactory.fillPattern(IMAGE_GRID))

    // The edge of the archive, under the routes. A PMTiles extract carries the whole
    // world at low zoom - that is how you can pinch out to a globe from a 2 MB file of one
    // town - so "there are tiles here" stops meaning "this area was downloaded". Without a
    // line saying where the detail ends, zooming out looks like the map degrading rather
    // than like leaving the area you cut.
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
    // Under the labels, not over them. Layers draw in the order they are added, so simply
    // appending put a 6px route line across every place name on the map. A route is a
    // thick opaque stroke and text is the one thing it must never cover: the name is how
    // you tell which trail the line is on.
    //
    // Falls back to appending when there is no basemap, because then there are no label
    // layers to sit beneath and the route is the only thing on screen.
    // Each insert lands immediately below the labels, so adding them in this order stacks
    // them mask - outline - routes, with every one of them still under the text.
    if (getLayer(MapStyle.lowestLabelLayer(0)) != null) {
        addLayerBelow(mask, MapStyle.lowestLabelLayer(0))
        addLayerBelow(coverageOutline, MapStyle.lowestLabelLayer(0))
        addLayerBelow(routes, MapStyle.lowestLabelLayer(0))
    } else {
        // No basemap: there is nothing to mask and no labels to sit beneath.
        addLayer(coverageOutline)
        addLayer(routes)
    }

    // Bigger than the scrub marker and wearing a halo: the two land on the same map and
    // mean different things. One points at a moment in a ride that is over; this is the
    // only thing on screen that is about right now.
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
 * Every route as line features, one per segment.
 *
 * Per segment rather than per track, because the gap between two segments is signal loss
 * and nothing should be drawn across it - the same rule the old canvas enforced with
 * `runStarts`. Each feature still carries its track's id, so a tap on any segment resolves
 * to the whole track.
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
            // A single position is not a line. It is still drawn - as the puck, if it is
            // the live track - but a one-point LineString is not valid GeoJSON.
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
 * The archive's real outline as line segments.
 *
 * Falls back to the declared bounding box when the directory could not be walked, which
 * is the old behaviour and is right for the common case of a rectangular extract.
 */
private fun List<OfflineMap>.toCoverageOutline(): FeatureCollection {

    // One feature per edge. They are already disjoint segments, and stitching them into
    // rings would be work for no gain: a dashed stroke looks the same either way.
    val features = flatMap { map ->
        if (map.coverage.isEmpty) {
            // The declared box, which is right for the ordinary rectangular extract and no
            // worse than before for any other.
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
 * The whole world with the archive's real coverage cut out of it.
 *
 * One hole per horizontal run of tiles rather than one per tile: the Kingston extract is
 * sixteen thousand tiles and a couple of hundred runs, and runs in adjacent rows share
 * their edges exactly, so they cut a seamless hole of any shape.
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

    // One hole set per shown map, all cut from the same world. They never overlap, so the
    // holes cannot either. Falls back to the declared box when a directory could not be
    // walked, which is correct for the ordinary rectangular extract.
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
 * Which track was tapped, and where along it.
 *
 * The track comes from the renderer: [MapLibreMap.queryRenderedFeatures] reports what was
 * actually drawn under those pixels, which is a better answer than any distance test
 * because it accounts for the line's drawn width and for the camera. The index along the
 * track is then found by scanning that one track's positions - the renderer knows what it
 * drew, not which sample of a ride it came from.
 */
private fun MapLibreMap.pick(
    tapped: LatLng,
    routes: List<RouteOverlay>,
): Pair<Long, Int>? {
    val screen = projection.toScreenLocation(tapped)
    val box = RectF(
        screen.x - TAP_REACH_PX,
        screen.y - TAP_REACH_PX,
        screen.x + TAP_REACH_PX,
        screen.y + TAP_REACH_PX,
    )
    val trackId = queryRenderedFeatures(box, LAYER_ROUTES)
        .firstNotNullOfOrNull { it.getNumberProperty(PROPERTY_TRACK_ID)?.toLong() }
        ?: return null

    val route = routes.firstOrNull { it.trackId == trackId } ?: return null
    val index = route.points.indexOfNearest(tapped) ?: return null
    return trackId to index
}

/**
 * The position closest to [target], as an index.
 *
 * A plain scan. It runs once per tap over one track, where the old canvas ran a
 * segment-distance test over every vertex of every track on every tap - and in degrees
 * squared rather than metres, which is only a comparison so the distortion cancels.
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
 * Everything worth looking at, as one box: every position of every route, and the extent
 * of every shown map.
 *
 * Null when there is neither - a fresh install with no tracks and no map, where there is
 * nothing to frame and nothing to keep the camera inside of.
 */
private fun extentOf(routes: List<RouteOverlay>, basemaps: List<OfflineMap>): LatLngBounds? {
    val builder = LatLngBounds.Builder()
    var count = 0

    routes.forEach { route ->
        route.points.forEach {
            builder.include(LatLng(it.latitude, it.longitude))
            count++
        }
    }
    basemaps.forEach { map ->
        builder.include(LatLng(map.header.minLatitude, map.header.minLongitude))
        builder.include(LatLng(map.header.maxLatitude, map.header.maxLongitude))
        count += 2
    }
    // A single position has no box, and LatLngBounds rejects a degenerate one - which is a
    // throw rather than an empty map, so it has to be caught here.
    if (count < 2) return null
    return runCatching { builder.build() }.getOrNull()
}

/** Frames what an archive covers, which is the next best thing to framing a ride. */
private fun MapLibreMap.frame(header: PmtilesHeader, insets: Insets) {
    val bounds = LatLngBounds.Builder()
        .include(LatLng(header.minLatitude, header.minLongitude))
        .include(LatLng(header.maxLatitude, header.maxLongitude))
        .build()
    moveCamera(
        CameraUpdateFactory.newLatLngBounds(
            bounds,
            insets.left + EDGE_PADDING_PX,
            insets.top + EDGE_PADDING_PX,
            insets.right + EDGE_PADDING_PX,
            insets.bottom + EDGE_PADDING_PX,
        )
    )
}

/** Frames every route, keeping the padded edges clear. */
private fun MapLibreMap.frame(routes: List<RouteOverlay>, insets: Insets) {
    val positions = routes.flatMap { route ->
        route.points.map { LatLng(it.latitude, it.longitude) }
    }
    if (positions.isEmpty()) return

    // A ride that never left one spot has no box to fit. LatLngBounds rejects a degenerate
    // one, so this is not an optimisation - it is the difference between a map and a throw.
    val spansGround = positions.any {
        abs(it.latitude - positions[0].latitude) > DEGENERATE_DEGREES ||
            abs(it.longitude - positions[0].longitude) > DEGENERATE_DEGREES
    }
    if (!spansGround) {
        moveCamera(CameraUpdateFactory.newLatLngZoom(positions[0], SINGLE_POINT_ZOOM))
        return
    }

    val bounds = LatLngBounds.Builder().includes(positions).build()
    moveCamera(
        CameraUpdateFactory.newLatLngBounds(
            bounds,
            insets.left + EDGE_PADDING_PX,
            insets.top + EDGE_PADDING_PX,
            insets.right + EDGE_PADDING_PX,
            insets.bottom + EDGE_PADDING_PX,
        )
    )
}

/**
 * Pans the least it can to bring [target] inside the uncovered box, or not at all.
 *
 * Expressed as a move of the camera's centre rather than a scroll, so the amount moved is
 * exactly the amount the target was outside by.
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
private const val SOURCE_MARKER = "route-marker"
private const val SOURCE_PUCK = "route-puck"

private const val LAYER_MASK = "coverage-mask-fill"
private const val LAYER_COVERAGE = "coverage-outline"
private const val LAYER_ROUTES = "routes-line"
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


private const val IMAGE_GRID = "grid"

/** Used only before the map has reported a scale to size the squares from. */
private val FallbackGridSquare = 48.dp

/** Below this the grid is a mesh; above it there is barely a grid on screen at all. */
private const val MinGridPx = 24
private const val MaxGridPx = 512

/** Line weight as a fraction of the square, so the grid keeps its proportions at any density. */
private const val GRID_LINE_DIVISOR = 48f

/** About a fingertip, in pixels rather than dp because it is a query box, not a drawing. */
private const val TAP_REACH_PX = 44f
private const val FOLLOW_MARGIN_PX = 96f
private const val EDGE_PADDING_PX = 64

/** Closer together than this and there is no box worth fitting - about 10 metres. */
private const val DEGENERATE_DEGREES = 1e-4

/** What a single position is shown at, since it implies no scale of its own. */
private const val SINGLE_POINT_ZOOM = 14.0

/** How far past an archive's deepest zoom the camera may still go. */
private const val OVERZOOM_ALLOWANCE = 2

/** The ceiling when no map is shown and there is nothing to derive one from. */
private const val DEFAULT_MAX_ZOOM = 16
