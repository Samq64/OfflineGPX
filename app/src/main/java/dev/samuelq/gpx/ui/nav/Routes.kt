package dev.samuelq.gpx.ui.nav

import dev.samuelq.gpx.core.model.GeoBounds
import kotlinx.serialization.Serializable

@Serializable
data object MapRoute

/** The area the map showed when the list was opened, which it can filter to; null without one. */
@Serializable
data class LibraryRoute(
    val south: Double? = null,
    val west: Double? = null,
    val north: Double? = null,
    val east: Double? = null,
) {
    constructor(area: GeoBounds?) : this(area?.southLatitude, area?.westLongitude, area?.northLatitude, area?.eastLongitude)

    val area: GeoBounds?
        get() = if (south != null && west != null && north != null && east != null) GeoBounds(south, west, north, east) else null
}

/** [importMap] opens the map file picker on arrival, for the map's first-run "Import map". */
@Serializable
data class SettingsRoute(val importMap: Boolean = false)
