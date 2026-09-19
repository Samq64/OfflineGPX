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
 * Everything the user can change, with the defaults the app shipped with.
 *
 * The three recording numbers are not cosmetic: they are the thresholds that decide what
 * counts as a position at all, and the defaults are a judgement about a phone in a jersey
 * pocket rather than a measurement. Someone with a better antenna, or riding somewhere
 * with no sky, needs different ones - which is the whole argument for exposing them.
 */
data class Settings(
    val units: UnitSystem = UnitSystem.METRIC,

    /** Fixes less certain than this are not positions. See [FixFilter]. */
    val maxAccuracyMeters: Double = FixFilter.MAX_ACCURACY_METERS,

    /** The floor under the accuracy rule, for the rare very confident fix. */
    val minDisplacementMeters: Double = FixFilter.MIN_DISPLACEMENT_METERS,

) {
    companion object {
        val Defaults = Settings()

        /** What the sliders may offer. Wider than useful in both directions, on purpose. */
        val ACCURACY_RANGE = 5.0..100.0
        val DISPLACEMENT_RANGE = 0.0..25.0
    }
}

/**
 * Settings, on disk.
 *
 * `SharedPreferences` rather than DataStore: this is three scalars read once at startup and
 * written when a slider stops moving, and DataStore would be a new dependency, a new
 * coroutine scope and a serializer to carry them. The first read is on the calling thread,
 * which is the documented cost and is a few hundred microseconds for a file this size.
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


    fun resetToDefaults() = update { clear() }

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
        )
    }

    private companion object {
        const val FILE = "settings"

        const val KEY_UNITS = "units"
        const val KEY_ACCURACY = "max_accuracy_meters"
        const val KEY_DISPLACEMENT = "min_displacement_meters"
    }
}
