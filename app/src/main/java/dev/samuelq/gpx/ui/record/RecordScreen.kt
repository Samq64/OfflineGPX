package dev.samuelq.gpx.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.RecordingEvent
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.tabularFigures

/**
 * The live recording screen.
 *
 * Deliberately not the track screen with a flag: reviewing means scrubbing the past and
 * recording means watching the present, so the two want opposite layouts. Everything here
 * is large, because it is read at arm's length on a handlebar or through a jacket pocket.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: RecordViewModel = viewModel(factory = RecordViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showApproximateRefusal by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val saveFailed = stringResource(R.string.record_save_failed)
    val discarded = stringResource(R.string.record_discarded)

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        when {
            // Precise only. Approximate is wifi- and cell-derived and accurate to hundreds
            // of metres at best; a route from it is noise and a speed from it is a wrong
            // number presented as a real one. Refusing beats recording garbage.
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true -> viewModel.start()
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ->
                showApproximateRefusal = true

            else -> Unit
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordingEvent.Saved -> onSaved(event.id)
                RecordingEvent.Discarded -> snackbarHostState.showSnackbar(discarded)
                is RecordingEvent.Failed -> snackbarHostState.showSnackbar(saveFailed)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.record_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            when (val current = state) {
                RecordingState.Idle -> IdleState(
                    gpsEnabled = viewModel.isGpsEnabled,
                    onStart = { permissions.launchIfNeeded(context, viewModel::start) },
                )

                is RecordingState.Active -> ActiveState(
                    state = current,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onStop = viewModel::stop,
                    onDiscard = { confirmDiscard = true },
                )
            }
        }
    }

    if (showApproximateRefusal) {
        AlertDialog(
            onDismissRequest = { showApproximateRefusal = false },
            title = { Text(stringResource(R.string.record_precise_required_title)) },
            text = { Text(stringResource(R.string.record_precise_required_body)) },
            confirmButton = {
                TextButton(onClick = { showApproximateRefusal = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.record_discard_title)) },
            text = { Text(stringResource(R.string.record_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discard()
                }) { Text(stringResource(R.string.record_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

}

@Composable
private fun IdleState(gpsEnabled: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.record_ready_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.record_ready_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Stated, not enforced: location can be switched on from the quick settings while
        // this screen is open, so the button stays live rather than being greyed out.
        if (!gpsEnabled) {
            Text(
                text = stringResource(R.string.record_no_gps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onStart) { Text(stringResource(R.string.record_start)) }
    }
}

@Composable
private fun ActiveState(
    state: RecordingState.Active,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = Formatters.distance(state.distanceMeters),
            style = MaterialTheme.typography.displayLarge,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            LiveStat(
                label = stringResource(R.string.stat_moving),
                value = Formatters.duration(state.movingSeconds),
            )
            LiveStat(
                label = stringResource(R.string.record_speed),
                value = state.currentSpeedMps?.let(Formatters::speed) ?: Formatters.EMPTY,
            )
            LiveStat(
                label = stringResource(R.string.stat_points),
                value = Formatters.count(state.pointCount),
            )
        }

        if (state.lastPoint == null) {
            Text(
                text = stringResource(R.string.record_waiting_for_fix),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.paused) {
                Button(onClick = onResume) { Text(stringResource(R.string.record_resume)) }
            } else {
                OutlinedButton(onClick = onPause) { Text(stringResource(R.string.record_pause)) }
            }
            Button(onClick = onStop) { Text(stringResource(R.string.record_stop)) }
        }

        TextButton(onClick = onDiscard) { Text(stringResource(R.string.record_discard)) }
    }
}

@Composable
private fun LiveStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall.tabularFigures())
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Asks only for what is missing, and only on the tap that starts a recording.
 *
 * Android 12+ ignores a fine-location request that does not also name coarse, so both are
 * listed - the pairing is the platform's, not a second capability this app wants.
 * Notifications are requested alongside because a foreground service the user cannot see
 * is worse than one they can, but recording works without that grant.
 */
private fun androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>.launchIfNeeded(
    context: android.content.Context,
    onAlreadyGranted: () -> Unit,
) {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    if (fine == PackageManager.PERMISSION_GRANTED) {
        onAlreadyGranted()
        return
    }

    val wanted = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    launch(wanted.toTypedArray())
}
