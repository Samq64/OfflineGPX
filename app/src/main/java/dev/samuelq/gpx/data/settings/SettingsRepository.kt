package dev.samuelq.gpx.data.settings

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.track.RouteColor
import dev.samuelq.gpx.data.track.RouteColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val units: UnitSystem = UnitSystem.METRIC,
    val theme: AppTheme = AppTheme.SYSTEM,
    /** ARGB by [RouteColor.ordinal], for the light and dark themes. */
    val routeColorsLight: List<Int> = RouteColors.LIGHT,
    val routeColorsDark: List<Int> = RouteColors.DARK,
    /** While following, the direction of travel is up. */
    val turnToHeading: Boolean = true,
)

/** Light or dark, or the system's. Stored by name. */
enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}

/** The library's order, which the map stacks by too, the list's top drawn on top. */
enum class TrackSort(
    /** The way it runs when picked: newest and longest first, names A to Z. */
    val naturallyDescending: Boolean,
) {
    RECENT(true),
    DATE(true),
    DISTANCE(true),
    DURATION(true),
    NAME(false),
}

/** A sort and which way it runs. */
data class TrackOrder(val sort: TrackSort, val descending: Boolean = sort.naturallyDescending)

/** `SharedPreferences` rather than DataStore, to avoid a dependency for a handful of keys. */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val uiModeManager = context.getSystemService(UiModeManager::class.java)

    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Apart from [settings] so a re-sort doesn't recompose everything that reads them. */
    private val _trackOrder = MutableStateFlow(readTrackOrder())
    val trackOrder: StateFlow<TrackOrder> = _trackOrder.asStateFlow()

    /** Runs its natural way; [setTrackSortDescending] turns it round. */
    fun setTrackSort(sort: TrackSort) = setTrackOrder(TrackOrder(sort))

    fun setTrackSortDescending(descending: Boolean) = setTrackOrder(_trackOrder.value.copy(descending = descending))

    private fun setTrackOrder(order: TrackOrder) {
        prefs.edit {
            putString(KEY_TRACK_SORT, order.sort.name)
            putBoolean(KEY_TRACK_SORT_DESCENDING, order.descending)
        }
        _trackOrder.value = order
    }

    private fun readTrackOrder(): TrackOrder {
        val sort = prefs.getString(KEY_TRACK_SORT, null)
            ?.let { name -> TrackSort.entries.firstOrNull { it.name == name } }
            ?: TrackSort.DATE
        return TrackOrder(sort, prefs.getBoolean(KEY_TRACK_SORT_DESCENDING, sort.naturallyDescending))
    }

    fun setUnits(units: UnitSystem) = update { putString(KEY_UNITS, units.name) }

    fun setTheme(theme: AppTheme) {
        update { putString(KEY_THEME, theme.name) }
        applyTheme()
    }

    /**
     * The platform's per-app night mode rather than Compose alone, so system bars, platform
     * dialogs and `values-night` follow too. The system keeps it, but not through a restore, so
     * it's reapplied at launch.
     */
    fun applyTheme() {
        uiModeManager.setApplicationNightMode(
            when (_settings.value.theme) {
                AppTheme.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                AppTheme.LIGHT -> UiModeManager.MODE_NIGHT_NO
                AppTheme.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    fun setTurnToHeading(turn: Boolean) = update { putBoolean(KEY_TURN_TO_HEADING, turn) }

    /** Null restores the default. Keyed by the slot's name, which is never renamed. */
    fun setRouteColor(dark: Boolean, slot: RouteColor, argb: Int?) = update {
        val key = routeColorKey(dark, slot)
        if (argb == null) remove(key) else putInt(key, argb)
    }

    fun resetRouteColors(dark: Boolean) = update {
        RouteColor.entries.forEach { remove(routeColorKey(dark, it)) }
    }

    /** The category the last saved recording had, which the next one is offered. */
    private val _lastRecordingCategory = MutableStateFlow(prefs.getString(KEY_LAST_RECORDING_CATEGORY, null))
    val lastRecordingCategory: StateFlow<String?> = _lastRecordingCategory.asStateFlow()

    fun setLastRecordingCategory(category: String?) {
        prefs.edit { putString(KEY_LAST_RECORDING_CATEGORY, category) }
        _lastRecordingCategory.value = category
    }

    private inline fun update(crossinline edits: SharedPreferences.Editor.() -> Unit) {
        prefs.edit { edits() }
        _settings.value = read()
    }

    private fun read(): Settings = Settings(
        units = prefs.getString(KEY_UNITS, null)
            ?.let { name -> UnitSystem.entries.firstOrNull { it.name == name } }
            ?: Settings().units,
        theme = prefs.getString(KEY_THEME, null)
            ?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
            ?: AppTheme.SYSTEM,
        routeColorsLight = readRouteColors(dark = false),
        routeColorsDark = readRouteColors(dark = true),
        turnToHeading = prefs.getBoolean(KEY_TURN_TO_HEADING, Settings().turnToHeading),
    )

    private fun readRouteColors(dark: Boolean): List<Int> {
        val defaults = if (dark) RouteColors.DARK else RouteColors.LIGHT
        return RouteColor.entries.map { prefs.getInt(routeColorKey(dark, it), defaults[it.ordinal]) }
    }

    private companion object {
        const val FILE = "settings"

        const val KEY_UNITS = "units"
        const val KEY_TRACK_SORT = "track_sort"
        const val KEY_TRACK_SORT_DESCENDING = "track_sort_descending"
        const val KEY_LAST_RECORDING_CATEGORY = "last_recording_category"
        const val KEY_THEME = "theme"
        const val KEY_TURN_TO_HEADING = "turn_to_heading"

        fun routeColorKey(dark: Boolean, slot: RouteColor) = "route_color_${if (dark) "dark" else "light"}_${slot.name}"
    }
}
