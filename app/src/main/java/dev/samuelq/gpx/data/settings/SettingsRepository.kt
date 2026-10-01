package dev.samuelq.gpx.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.samuelq.gpx.core.analysis.FixFilter
import dev.samuelq.gpx.core.model.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val units: UnitSystem = UnitSystem.METRIC,

    /** See [FixFilter]. */
    val maxAccuracyMeters: Double = FixFilter.MAX_ACCURACY_METERS,

    /** Off by default: they're for those who can't pinch, and in everyone else's way. */
    val showZoomButtons: Boolean = false,
) {
    companion object {
        val Defaults = Settings()

        /** Deliberately wider than useful. */
        val ACCURACY_RANGE = 5.0..100.0
    }
}

/** The library's order; [DATE] keeps hidden and covered tracks easy to find. The map always stacks by [RECENT]. */
enum class TrackSort { RECENT, DATE, LENGTH, NAME }

/** `SharedPreferences` rather than DataStore, to avoid a dependency for a handful of keys. */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Apart from [settings] so a re-sort doesn't recompose everything that reads them. */
    private val _trackSort = MutableStateFlow(readTrackSort())
    val trackSort: StateFlow<TrackSort> = _trackSort.asStateFlow()

    fun setTrackSort(sort: TrackSort) {
        prefs.edit { putString(KEY_TRACK_SORT, sort.name) }
        _trackSort.value = sort
    }

    private fun readTrackSort(): TrackSort = prefs.getString(KEY_TRACK_SORT, null)
        ?.let { name -> TrackSort.entries.firstOrNull { it.name == name } }
        ?: TrackSort.DATE

    fun setUnits(units: UnitSystem) = update { putString(KEY_UNITS, units.name) }

    fun setMaxAccuracyMeters(meters: Double) =
        update { putFloat(KEY_ACCURACY, meters.toFloat()) }

    fun setShowZoomButtons(show: Boolean) = update { putBoolean(KEY_ZOOM_BUTTONS, show) }

    private inline fun update(crossinline edits: SharedPreferences.Editor.() -> Unit) {
        prefs.edit { edits() }
        _settings.value = read()
    }

    private fun read(): Settings {
        val defaults = Settings.Defaults
        return Settings(
            units = prefs.getString(KEY_UNITS, null)
                ?.let { name -> UnitSystem.entries.firstOrNull { it.name == name } }
                ?: defaults.units,
            maxAccuracyMeters = prefs
                .getFloat(KEY_ACCURACY, defaults.maxAccuracyMeters.toFloat()).toDouble(),
            showZoomButtons = prefs.getBoolean(KEY_ZOOM_BUTTONS, defaults.showZoomButtons),
        )
    }

    private companion object {
        const val FILE = "settings"

        const val KEY_UNITS = "units"
        const val KEY_ZOOM_BUTTONS = "show_zoom_buttons"
        const val KEY_ACCURACY = "max_accuracy_meters"
        const val KEY_TRACK_SORT = "track_sort"
    }
}
