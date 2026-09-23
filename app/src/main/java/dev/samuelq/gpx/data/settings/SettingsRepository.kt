package dev.samuelq.gpx.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.samuelq.gpx.core.analysis.FixFilter
import dev.samuelq.gpx.core.model.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the user can change, with the defaults the app shipped with. The recording
 * thresholds are judgements about a phone in a jersey pocket, not measurements - which is
 * why they're exposed rather than fixed.
 */
data class Settings(
    val units: UnitSystem = UnitSystem.METRIC,

    /** Fixes less certain than this are not positions. See [FixFilter]. */
    val maxAccuracyMeters: Double = FixFilter.MAX_ACCURACY_METERS,

    /** The floor under the accuracy rule, for the rare very confident fix. */
    val minDisplacementMeters: Double = FixFilter.MIN_DISPLACEMENT_METERS,

    /**
     * Filenames (not paths - the directory is the app's own) of the offline basemaps to
     * draw. A set because adjacent areas are the normal case; only ever non-overlapping,
     * which is what keeps this from needing a stacking order.
     */
    val activeMapFiles: Set<String> = emptySet(),

) {
    companion object {
        val Defaults = Settings()

        /** What the sliders may offer. Wider than useful in both directions, on purpose. */
        val ACCURACY_RANGE = 5.0..100.0
        val DISPLACEMENT_RANGE = 0.0..25.0
    }
}

/**
 * Settings, on disk. `SharedPreferences` rather than DataStore: this is three scalars read
 * once at startup, and DataStore would be a new dependency for no benefit at this size.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())

    /** Always current. The recorder reads it when a recording starts; the UI observes it. */
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun setUnits(units: UnitSystem) = update { putString(KEY_UNITS, units.name) }

    fun setMaxAccuracyMeters(meters: Double) =
        update { putFloat(KEY_ACCURACY, meters.toFloat()) }

    fun setMinDisplacementMeters(meters: Double) =
        update { putFloat(KEY_DISPLACEMENT, meters.toFloat()) }

    fun setActiveMapFiles(names: Set<String>) = update { putStringSet(KEY_ACTIVE_MAPS, names) }

    /**
     * Back to shipped defaults - deliberately leaves imported maps alone. Dropping a large
     * file the user went and obtained would be a deletion wearing a reset's clothes.
     */
    fun resetToDefaults() = update {
        val activeMaps = read().activeMapFiles
        clear()
        if (activeMaps.isNotEmpty()) putStringSet(KEY_ACTIVE_MAPS, activeMaps)
    }

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
            minDisplacementMeters = prefs
                .getFloat(KEY_DISPLACEMENT, defaults.minDisplacementMeters.toFloat()).toDouble(),
            activeMapFiles = prefs.getStringSet(KEY_ACTIVE_MAPS, null) ?: defaults.activeMapFiles,
        )
    }

    private companion object {
        const val FILE = "settings"

        const val KEY_UNITS = "units"
        const val KEY_ACCURACY = "max_accuracy_meters"
        const val KEY_DISPLACEMENT = "min_displacement_meters"
        const val KEY_ACTIVE_MAPS = "active_map_files"
    }
}
