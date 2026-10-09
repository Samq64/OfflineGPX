package dev.samuelq.gpx.data.gpx

/**
 * Track colour extensions. Garmin's `DisplayColor` names one of a hue's two shades, which is
 * all a route slot needs, so it's the one read and written. gpx_style's and OsmAnd's, from other
 * apps' files, are only recognised, so a new colour replaces them rather than being contradicted.
 */
internal object GpxColors {
    const val GARMIN_NAMESPACE = "http://www.garmin.com/xmlschemas/GpxExtensions/v3"
    const val GARMIN_TRACK = "TrackExtension"
    const val GARMIN_COLOR = "DisplayColor"

    /** A `<trk><extensions>` child that sets the line colour, which a new colour replaces whole. */
    fun isColorElement(namespace: String?, name: String): Boolean =
        (namespace?.startsWith(STYLE_BASE) == true && name == "line") ||
            (namespace == GARMIN_NAMESPACE && name == GARMIN_TRACK) ||
            // OsmAnd has used both schemes.
            (namespace?.removeSuffix("/")?.endsWith("://osmand.net") == true && name == "color")

    private const val STYLE_BASE = "http://www.topografix.com/GPX/gpx_style/"
}
