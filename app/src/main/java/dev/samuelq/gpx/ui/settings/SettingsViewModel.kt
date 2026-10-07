package dev.samuelq.gpx.ui.settings

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.R
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

enum class SettingsMessage(@param:StringRes val text: Int) {
    MapImported(R.string.settings_maps_imported),
    MapUnreadable(R.string.settings_maps_failed_unreadable),
    MapWrongFormat(R.string.settings_maps_failed_format),
    MapNoSpace(R.string.settings_maps_failed_space),
    MapAlreadyImported(R.string.settings_maps_failed_duplicate),
    MapExported(R.string.settings_maps_exported),
    MapExportFailed(R.string.settings_maps_export_failed),
    NoBrowser(R.string.settings_maps_no_browser),
}

class SettingsViewModel(private val repository: SettingsRepository, private val mapStore: MapStore) : ViewModel() {

    val settings: StateFlow<Settings> = repository.settings

    val maps: StateFlow<List<OfflineMap>> = mapStore.maps

    /** Maps run to tens of megabytes, so the copy takes visible seconds. */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    fun setUnits(units: UnitSystem) = repository.setUnits(units)

    fun importMap(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _importing.value = true
            val result = mapStore.import(uri)
            _importing.value = false
            report(result)
        }
    }

    private suspend fun report(result: MapImportResult) {
        _messages.send(
            when (result) {
                is MapImportResult.Imported -> SettingsMessage.MapImported
                is MapImportResult.Failed -> when (result.error) {
                    MapImportError.UNREADABLE -> SettingsMessage.MapUnreadable
                    MapImportError.NOT_A_MAP_FILE -> SettingsMessage.MapWrongFormat
                    MapImportError.NO_SPACE -> SettingsMessage.MapNoSpace
                    MapImportError.ALREADY_IMPORTED -> SettingsMessage.MapAlreadyImported
                }
            },
        )
    }

    /** Undoable until [commitDeleteMap]. */
    fun deleteMap(map: OfflineMap) = mapStore.deleteLater(map)

    fun exportMap(map: OfflineMap, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _messages.send(
                if (mapStore.export(map, uri)) SettingsMessage.MapExported else SettingsMessage.MapExportFailed,
            )
        }
    }

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
