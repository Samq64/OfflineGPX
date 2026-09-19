package dev.samuelq.gpx.ui.nav

import kotlinx.serialization.Serializable

/**
 * Where the app can be.
 *
 * Type-safe routes: navigation-compose serialises these into the back stack entry's
 * arguments, so nothing here is ever concatenated into a path string. That is what the
 * hand-rolled back stack got wrong - it encoded destinations space-separated, which a URI
 * containing a literal space silently decoded as the wrong screen after process death.
 *
 * A track is not among them. It used to be, which made looking at one a place you travelled
 * to and came back from; it is a selection on the map now, and selections do not belong on
 * a back stack.
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
