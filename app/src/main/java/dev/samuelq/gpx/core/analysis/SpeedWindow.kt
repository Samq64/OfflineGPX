package dev.samuelq.gpx.core.analysis

/**
 * Live speed, over the same window the analyzer differentiates the saved track with.
 *
 * The recorder used to divide the last hop by the last interval, which is the naive
 * derivative [TrackAnalyzer] exists specifically not to use: at 1 Hz it swings
 * by several km/h between adjacent points, so the number on screen disagreed with the
 * chart drawn from the very same ride a minute later.
 *
 * Fed on *every* reading rather than every recorded point, including the ones the filter
 * threw away. That is what lets it fall back to zero when you stop: distance stops growing
 * while time does not, so the quotient decays instead of freezing at whatever you were
 * doing when the last point was committed.
 */
class SpeedWindow(private val windowSeconds: Double = TrackAnalyzer.SPEED_WINDOW_SECONDS) {

    private val times = ArrayDeque<Double>()
    private val distances = ArrayDeque<Double>()

    /** @param seconds any consistent clock; @param cumulativeMeters distance so far. */
    fun add(seconds: Double, cumulativeMeters: Double) {
        // Out-of-order readings happen when a provider replays a buffered fix. Dropping
        // one is better than letting it invert the window's span.
        if (times.isNotEmpty() && seconds < times.last()) return

        times.addLast(seconds)
        distances.addLast(cumulativeMeters)

        // Keep the oldest sample that still spans the window, so the span never shrinks
        // below it while samples are arriving.
        while (times.size > 2 && seconds - times[1] >= windowSeconds) {
            times.removeFirst()
            distances.removeFirst()
        }
    }

    /**
     * Metres per second, or null while the window is too short to mean anything.
     *
     * Null rather than zero: before a couple of seconds have passed there is no
     * measurement, and a confident `0.0 km/h` would be a claim the recorder cannot make.
     */
    val speedMps: Double?
        get() {
            if (times.size < 2) return null
            val span = times.last() - times.first()
            if (span < MIN_SPAN_SECONDS) return null
            return ((distances.last() - distances.first()) / span).coerceAtLeast(0.0)
        }

    fun reset() {
        times.clear()
        distances.clear()
    }

    private companion object {
        /** Under this the quotient is dominated by whatever the last hop happened to be. */
        const val MIN_SPAN_SECONDS = 3.0
    }
}
