package dev.samuelq.gpx.core.model

import java.time.Instant

/** One fix or GPX point; [elevation] and [time] are often absent in real files. */
public data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    /** Metres above sea level. */
    val elevation: Double? = null,
    val time: Instant? = null,
    /** Horizontal accuracy in metres, while recording only: `<hdop>` is unitless, not this. */
    val accuracyMeters: Double? = null,
)

public fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0

/** A GPX `<wpt>`: a note at the last known position, not a fix of its own. */
public data class Waypoint(
    val point: TrackPoint,
    /** The `<name>`, else the `<desc>` or `<cmt>`. */
    val name: String? = null,
)

/** Raw geometry only; derived values live in [dev.samuelq.gpx.core.analysis.TrackProfile]. */
public data class Track(
    val name: String?,
    val points: TrackPoints,
    /** The `<trk><desc>`. */
    val description: String? = null,
    /** The `<trk><type>`, which the app keeps as the category. */
    val type: String? = null,
    /** Unordered. */
    val waypoints: List<Waypoint> = emptyList(),
    /** A `<trk>` line colour extension's, as 0xRRGGBB. */
    val lineColor: Int? = null,
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
public class TrackPoints internal constructor(
    public val size: Int,
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    private val elevations: FloatArray,
    private val times: LongArray,
    private val accuracies: FloatArray,
    private val starts: IntArray,
    public val segmentCount: Int,
) {
    public val indices: IntRange get() = 0 until size

    public fun latitude(i: Int): Double = latitudes[check(i)]
    public fun longitude(i: Int): Double = longitudes[check(i)]

    /** NaN where absent. */
    public fun elevation(i: Int): Float = elevations[check(i)]

    /** Epoch millis, or [NO_TIME]. */
    public fun timeMillis(i: Int): Long = times[check(i)]
    public fun hasTime(i: Int): Boolean = times[check(i)] != NO_TIME

    /** NaN where absent. */
    public fun accuracy(i: Int): Float = accuracies[check(i)]

    /** Index into the points at which segment [segment] begins. */
    public fun segmentStart(segment: Int): Int = starts[segment]

    /** Exclusive. */
    public fun segmentEnd(segment: Int): Int = if (segment + 1 < segmentCount) starts[segment + 1] else size

    /** Index at which each segment begins; a copy. */
    public fun segmentStarts(): IntArray = starts.copyOf(segmentCount)

    /** Allocates; for single points, not loops over the track. */
    public operator fun get(i: Int): TrackPoint = TrackPoint(
        latitude = latitude(i),
        longitude = longitude(i),
        elevation = elevation(i).takeUnless(Float::isNaN)?.toDouble(),
        time = timeMillis(i).takeIf { it != NO_TIME }?.let(Instant::ofEpochMilli),
        accuracyMeters = accuracy(i).takeUnless(Float::isNaN)?.toDouble(),
    )

    public fun lastOrNull(): TrackPoint? = if (size > 0) get(size - 1) else null
    public fun getOrNull(i: Int): TrackPoint? = if (i in 0 until size) get(i) else null

    /** Points [range] alone, copied, still cut where segments begin within it. */
    public fun slice(range: IntRange): TrackPoints {
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
    internal fun withSegmentStarts(starts: IntArray): TrackPoints =
        TrackPoints(size, latitudes, longitudes, elevations, times, accuracies, starts, starts.size)

    /** Bounds-checked against [size], not the possibly longer shared array. */
    private fun check(i: Int): Int {
        if (i !in 0 until size) throw IndexOutOfBoundsException("Index $i, size $size")
        return i
    }

    public companion object {
        public const val NO_TIME: Long = Long.MIN_VALUE

        public val EMPTY: TrackPoints = TrackPointsBuilder().build()

        /** One list per segment; empty lists are dropped. */
        public fun of(vararg segments: List<TrackPoint>): TrackPoints = TrackPointsBuilder().apply {
            for (segment in segments) {
                startSegment()
                segment.forEach(::add)
            }
        }.build()
    }
}

/** Appends points, growing its arrays. Not thread-safe. */
public class TrackPointsBuilder(capacity: Int = 256) {
    private var latitudes = DoubleArray(capacity)
    private var longitudes = DoubleArray(capacity)
    private var elevations = FloatArray(capacity)
    private var times = LongArray(capacity)
    private var accuracies = FloatArray(capacity)
    private var starts = IntArray(8)
    private var segmentCount = 0
    private var startPending = true

    public var size: Int = 0
        private set

    /** The next point begins a segment. Repeats and trailing calls add nothing. */
    public fun startSegment() {
        startPending = true
    }

    public fun add(point: TrackPoint): Unit = add(
        latitude = point.latitude,
        longitude = point.longitude,
        elevation = point.elevation?.toFloat() ?: Float.NaN,
        timeMillis = point.time?.toEpochMilli() ?: TrackPoints.NO_TIME,
        accuracy = point.accuracyMeters?.toFloat() ?: Float.NaN,
    )

    public fun add(
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
    public fun build(): TrackPoints {
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
    public fun snapshot(): TrackPoints =
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
