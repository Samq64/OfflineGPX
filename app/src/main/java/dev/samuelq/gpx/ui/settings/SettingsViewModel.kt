package dev.samuelq.gpx.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.map.MapImportError
import dev.samuelq.gpx.data.map.MapImportResult
import dev.samuelq.gpx.data.map.MapStore
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.settings.Settings
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Something the settings screen should say once. The words are the screen's business. */
enum class SettingsMessage {
    MapImported,
    MapDeleted,
    MapUnreadable,
    MapWrongFormat,
    MapNoSpace,
    NoBrowser,
}

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val mapStore: MapStore,
) : ViewModel() {

    val settings: StateFlow<Settings> = repository.settings

    val maps: StateFlow<List<OfflineMap>> = mapStore.maps

    /**
     * True while a map is being copied in.
     *
     * Worth showing: these files run to tens of megabytes, and a copy through SAF from a
     * downloads folder is seconds of nothing happening otherwise.
     */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    fun setUnits(units: UnitSystem) = repository.setUnits(units)
    fun setMaxAccuracy(meters: Double) = repository.setMaxAccuracyMeters(meters)
    fun setMinDisplacement(meters: Double) = repository.setMinDisplacementMeters(meters)
    fun resetToDefaults() = repository.resetToDefaults()

    fun importMap(uri: Uri?, suggestedName: String?) {
        if (uri == null) return
        viewModelScope.launch {
            _importing.value = true
            val result = mapStore.import(uri, suggestedName)
            _importing.value = false
            _messages.send(
                when (result) {
                    is MapImportResult.Imported -> SettingsMessage.MapImported
                    is MapImportResult.Failed -> when (result.error) {
                        MapImportError.UNREADABLE -> SettingsMessage.MapUnreadable
                        MapImportError.NOT_A_MAP_FILE -> SettingsMessage.MapWrongFormat
                        MapImportError.NO_SPACE -> SettingsMessage.MapNoSpace
                    }
                }
            )
        }
    }

    fun deleteMap(map: OfflineMap) {
        viewModelScope.launch {
            mapStore.delete(map)
            _messages.send(SettingsMessage.MapDeleted)
        }
    }

    /** Reported when nothing on the device can open the "where to get maps" link. */
    fun reportNoBrowser() {
        viewModelScope.launch { _messages.send(SettingsMessage.NoBrowser) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    repository = appContainer.settingsRepository,
                    mapStore = appContainer.mapStore,
                )
            }
        }
    }
}
