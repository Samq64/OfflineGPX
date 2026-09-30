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

enum class SettingsMessage {
    MapImported,
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

    /** Maps run to tens of megabytes, so the copy takes visible seconds. */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** An import waiting on the user to confirm merging with the maps it duplicates. */
    private val _overlapping = MutableStateFlow<MapImportResult.Overlaps?>(null)
    val overlapping: StateFlow<MapImportResult.Overlaps?> = _overlapping.asStateFlow()

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    fun setUnits(units: UnitSystem) = repository.setUnits(units)
    fun setMaxAccuracy(meters: Double) = repository.setMaxAccuracyMeters(meters)

    fun importMap(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _importing.value = true
            val result = mapStore.import(uri)
            _importing.value = false
            report(result)
        }
    }

    fun mergeOverlapping() {
        val overlaps = _overlapping.value ?: return
        _overlapping.value = null
        viewModelScope.launch { report(mapStore.confirmImport(overlaps)) }
    }

    fun cancelImport() {
        val overlaps = _overlapping.value ?: return
        _overlapping.value = null
        mapStore.cancelImport(overlaps)
    }

    private suspend fun report(result: MapImportResult) {
        _messages.send(
            when (result) {
                is MapImportResult.Imported -> SettingsMessage.MapImported
                is MapImportResult.Overlaps -> {
                    _overlapping.value = result
                    return
                }
                is MapImportResult.Failed -> when (result.error) {
                    MapImportError.UNREADABLE -> SettingsMessage.MapUnreadable
                    MapImportError.NOT_A_MAP_FILE -> SettingsMessage.MapWrongFormat
                    MapImportError.NO_SPACE -> SettingsMessage.MapNoSpace
                }
            }
        )
    }

    override fun onCleared() = cancelImport()

    /** Undoable until [commitDeleteMap]. */
    fun deleteMap(map: OfflineMap) = mapStore.deleteLater(map)

    fun undoDeleteMap(map: OfflineMap) = mapStore.undoDelete(map)

    fun commitDeleteMap(map: OfflineMap) = mapStore.commitDelete(map)

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
