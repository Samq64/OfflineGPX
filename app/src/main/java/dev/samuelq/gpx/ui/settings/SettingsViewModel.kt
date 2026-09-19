package dev.samuelq.gpx.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.settings.Settings
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<Settings> = repository.settings

    fun setUnits(units: UnitSystem) = repository.setUnits(units)
    fun setMaxAccuracy(meters: Double) = repository.setMaxAccuracyMeters(meters)
    fun setMinDisplacement(meters: Double) = repository.setMinDisplacementMeters(meters)
    fun resetToDefaults() = repository.resetToDefaults()

    companion object {
        val Factory = viewModelFactory {
            initializer { SettingsViewModel(appContainer.settingsRepository) }
        }
    }
}
