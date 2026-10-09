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
    constructor(
        area: GeoBounds?,
    ) : this(area?.southLatitude, area?.westLongitude, area?.northLatitude, area?.eastLongitude)

    val area: GeoBounds?
        get() = GeoBounds(south ?: return null, west ?: return null, north ?: return null, east ?: return null)
}

/** [importMap] opens the map file picker on arrival, for the map's first-run "Import map". */
@Serializable
data class SettingsRoute(val importMap: Boolean = false)

@Serializable
data object LibrariesRoute
