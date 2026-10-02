package dev.samuelq.gpx.core.model

import java.time.Instant

/** One fix or GPX point; [elevation] and [time] are often absent in real files. */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    /** Metres above sea level; older recordings from this app hold WGS84 ellipsoid height. */
    val elevation: Double? = null,
    val time: Instant? = null,
    /** Horizontal accuracy in metres, while recording only: `<hdop>` is unitless, not this. */
    val accuracyMeters: Double? = null,
)

fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0

/** A GPX `<wpt>`: a note at the last known position, not a fix of its own. */
data class Waypoint(
    val point: TrackPoint,
    /** The `<name>`, else the `<desc>` or `<cmt>`. */
    val name: String? = null,
)

/** Raw geometry only; derived values live in [dev.samuelq.gpx.core.analysis.TrackProfile]. */
data class Track(
    val name: String?,
    val points: TrackPoints,
    /** The `<trk><desc>`. */
    val description: String? = null,
    /** Unordered. */
    val waypoints: List<Waypoint> = emptyList(),
) {
    val isEmpty: Boolean get() = points.size == 0
}

/**
 * A track's points as parallel columns, a third the memory of an object per point.
 * Nothing (distance, chart lines) connects across a segment start.
 *
 * A [TrackPointsBuilder.snapshot] shares the builder's arrays, which it only ever writes past
 * [size], so a snapshot never changes.
 */
class TrackPoints internal constructor(
    val size: Int,
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    private val elevations: FloatArray,
    private val times: LongArray,
    private val accuracies: FloatArray,
    private val starts: IntArray,
    val segmentCount: Int,
) {
    val indices: IntRange get() = 0 until size

    fun latitude(i: Int): Double = latitudes[check(i)]
    fun longitude(i: Int): Double = longitudes[check(i)]

    /** NaN where absent. */
    fun elevation(i: Int): Float = elevations[check(i)]

    /** Epoch millis, or [NO_TIME]. */
    fun timeMillis(i: Int): Long = times[check(i)]
    fun hasTime(i: Int): Boolean = times[check(i)] != NO_TIME

    /** NaN where absent. */
    fun accuracy(i: Int): Float = accuracies[check(i)]

    /** Index into the points at which segment [segment] begins. */
    fun segmentStart(segment: Int): Int = starts[segment]

    /** Exclusive. */
    fun segmentEnd(segment: Int): Int = if (segment + 1 < segmentCount) starts[segment + 1] else size

    /** Index at which each segment begins; a copy. */
    fun segmentStarts(): IntArray = starts.copyOf(segmentCount)

    /** Allocates; for single points, not loops over the track. */
    operator fun get(i: Int): TrackPoint = TrackPoint(
        latitude = latitude(i),
        longitude = longitude(i),
        elevation = elevation(i).takeUnless(Float::isNaN)?.toDouble(),
        time = timeMillis(i).takeIf { it != NO_TIME }?.let(Instant::ofEpochMilli),
        accuracyMeters = accuracy(i).takeUnless(Float::isNaN)?.toDouble(),
    )

    fun first(): TrackPoint = get(0)
    fun last(): TrackPoint = get(size - 1)
    fun lastOrNull(): TrackPoint? = if (size > 0) last() else null
    fun getOrNull(i: Int): TrackPoint? = if (i in 0 until size) get(i) else null

    /** Points [range] alone, copied, still cut where segments begin within it. */
    fun slice(range: IntRange): TrackPoints {
        val out = TrackPointsBuilder(capacity = maxOf(1, range.count()))
        var segment = 0
        for (i in range) {
            while (segment + 1 < segmentCount && segmentStart(segment + 1) <= i) segment++
            if (i == range.first || segmentStart(segment) == i) out.startSegment()
            out.add(latitude(i), longitude(i), elevation(i), timeMillis(i), accuracy(i))
        }
        return out.build()
    }

    /** The same points cut at [starts] instead, which must begin with 0 when non-empty. */
    fun withSegmentStarts(starts: IntArray): TrackPoints =
        TrackPoints(size, latitudes, longitudes, elevations, times, accuracies, starts, starts.size)

    /** Bounds-checked against [size], not the possibly longer shared array. */
    private fun check(i: Int): Int {
        if (i !in 0 until size) throw IndexOutOfBoundsException("Index $i, size $size")
        return i
    }

    companion object {
        const val NO_TIME = Long.MIN_VALUE

        val EMPTY: TrackPoints = TrackPointsBuilder().build()

        /** One list per segment; empty lists are dropped. */
        fun of(vararg segments: List<TrackPoint>): TrackPoints = TrackPointsBuilder().apply {
            for (segment in segments) {
                startSegment()
                segment.forEach(::add)
            }
        }.build()
    }
}

/** Appends points, growing its arrays. Not thread-safe. */
class TrackPointsBuilder(capacity: Int = 256) {
    private var latitudes = DoubleArray(capacity)
    private var longitudes = DoubleArray(capacity)
    private var elevations = FloatArray(capacity)
    private var times = LongArray(capacity)
    private var accuracies = FloatArray(capacity)
    private var starts = IntArray(8)
    private var segmentCount = 0
    private var startPending = true

    var size: Int = 0
        private set

    /** The next point begins a segment. Repeats and trailing calls add nothing. */
    fun startSegment() {
        startPending = true
    }

    fun add(point: TrackPoint) = add(
        latitude = point.latitude,
        longitude = point.longitude,
        elevation = point.elevation?.toFloat() ?: Float.NaN,
        timeMillis = point.time?.toEpochMilli() ?: TrackPoints.NO_TIME,
        accuracy = point.accuracyMeters?.toFloat() ?: Float.NaN,
    )

    fun add(
        latitude: Double,
        longitude: Double,
        elevation: Float = Float.NaN,
        timeMillis: Long = TrackPoints.NO_TIME,
        accuracy: Float = Float.NaN,
    ) {
        if (startPending) {
            if (segmentCount == starts.size) starts = starts.copyOf(maxOf(8, segmentCount * 2))
            starts[segmentCount++] = size
            startPending = false
        }
        if (size == latitudes.size) grow()
        latitudes[size] = latitude
        longitudes[size] = longitude
        elevations[size] = elevation
        times[size] = timeMillis
        accuracies[size] = accuracy
        size++
    }

    /** Trimmed to [size], since growth leaves up to half the arrays spare. */
    fun build(): TrackPoints {
        if (latitudes.size != size) {
            latitudes = latitudes.copyOf(size)
            longitudes = longitudes.copyOf(size)
            elevations = elevations.copyOf(size)
            times = times.copyOf(size)
            accuracies = accuracies.copyOf(size)
        }
        if (starts.size != segmentCount) starts = starts.copyOf(segmentCount)
        return snapshot()
    }

    /** Without copying, for a track still growing; keeps the spare capacity. */
    fun snapshot(): TrackPoints =
        TrackPoints(size, latitudes, longitudes, elevations, times, accuracies, starts, segmentCount)

    /** New arrays, so snapshots built before keep the old ones untouched. */
    private fun grow() {
        val capacity = maxOf(16, latitudes.size * 2)
        latitudes = latitudes.copyOf(capacity)
        longitudes = longitudes.copyOf(capacity)
        elevations = elevations.copyOf(capacity)
        times = times.copyOf(capacity)
        accuracies = accuracies.copyOf(capacity)
    }
}
