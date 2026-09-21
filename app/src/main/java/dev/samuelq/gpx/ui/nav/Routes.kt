package dev.samuelq.gpx.ui.nav

import kotlinx.serialization.Serializable

/**
 * Where the app can be. Type-safe routes rather than a path string, and a track is
 * deliberately not among them - it's a selection on the map now, not a place you travel to.
 */

/** Home. Every track the user has chosen to show, overlaid, plus whichever one is open. */
@Serializable
data object MapRoute

/** The management list: import, export, rename, show, hide, delete. */
@Serializable
data object LibraryRoute

/** Units, and the three thresholds that decide what the recorder believes. */
@Serializable
data object SettingsRoute
