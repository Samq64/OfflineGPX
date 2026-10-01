package dev.samuelq.gpx.ui.nav

import kotlinx.serialization.Serializable

@Serializable
data object MapRoute

@Serializable
data object LibraryRoute

/** [importMap] opens the map file picker on arrival, for the map's first-run "Import map". */
@Serializable
data class SettingsRoute(val importMap: Boolean = false)
