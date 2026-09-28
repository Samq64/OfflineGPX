package dev.samuelq.gpx.core.analysis

/**
 * Live speed, over the same window [TrackAnalyzer] differentiates the saved track with, so
 * the two never disagree about the same ride.
 *
 * Fed on *every* reading, including ones the filter threw away - that's what lets it decay
 * to zero when you stop, rather than freezing at the last committed point's speed.
 */
class SpeedWindow {

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
        while (times.size > 2 && seconds - times[1] >= TrackAnalyzer.SPEED_WINDOW_SECONDS) {
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
