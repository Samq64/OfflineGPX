package dev.samuelq.gpx.ui.nav

import kotlinx.serialization.Serializable

/**
 * Where the app can be.
 *
 * Type-safe routes: navigation-compose serialises these into the back stack entry's
 * arguments, so nothing here is ever concatenated into a path string. That is what the
 * hand-rolled back stack got wrong - it encoded destinations space-separated, which a URI
 * containing a literal space silently decoded as the wrong screen after process death.
 */
@Serializable
data object LibraryRoute

/** An indexed track, addressed by its `tracks` row - never by URI. */
@Serializable
data class TrackRoute(val id: Long)

/**
 * A track handed over by a VIEW or SEND intent, which is not in the library.
 *
 * The URI rides in the route because there is no row to point at yet. It is safe here in
 * a way it was not before: the value is carried as a typed argument, not spliced into a
 * route string.
 */
@Serializable
data class TransientTrackRoute(val uri: String)
