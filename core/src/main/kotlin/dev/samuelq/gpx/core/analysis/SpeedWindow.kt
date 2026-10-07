package dev.samuelq.gpx.core.analysis

/**
 * Live speed over the same window as [TrackAnalyzer]. Fed every reading, including
 * filtered ones, so it decays to zero on a stop.
 */
public class SpeedWindow {

    private val times = ArrayDeque<Double>()
    private val distances = ArrayDeque<Double>()

    /** @param cumulativeMeters distance so far. */
    public fun add(seconds: Double, cumulativeMeters: Double) {
        // Providers sometimes replay a buffered fix.
        if (times.isNotEmpty() && seconds < times.last()) return

        times.addLast(seconds)
        distances.addLast(cumulativeMeters)

        // Keep the oldest sample that still spans the window.
        while (times.size > 2 && seconds - times[1] >= TrackAnalyzer.SPEED_WINDOW_SECONDS) {
            times.removeFirst()
            distances.removeFirst()
        }
    }

    /** Null, not zero, while the window is too short to measure. */
    public val speedMps: Double?
        get() {
            if (times.size < 2) return null
            val span = times.last() - times.first()
            if (span < MIN_SPAN_SECONDS) return null
            return ((distances.last() - distances.first()) / span).coerceAtLeast(0.0)
        }

    public fun reset() {
        times.clear()
        distances.clear()
    }

    private companion object {
        /** Shorter spans are dominated by the last hop's noise. */
        const val MIN_SPAN_SECONDS = 3.0
    }
}
