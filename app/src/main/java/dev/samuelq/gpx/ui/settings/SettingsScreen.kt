package dev.samuelq.gpx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.settings.Settings
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.tabularFigures
import kotlin.math.roundToInt

private val ScreenPadding = 20.dp

/**
 * The three things worth changing, each said in full.
 *
 * Two of these are signal-processing thresholds, which is not a thing a settings screen can
 * assume anyone knows. Both carry a sentence about what moving them actually costs, because
 * a number you can change without knowing what it does is a number you will change once and
 * never understand again.
 *
 * What is deliberately *not* here: the sampling rate, whose two ends are "worse route" and
 * "worse battery"; and the pause-detection threshold, which is about the file rather than
 * this device and would have to re-summarise the whole library every time it moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val formatters = LocalFormatters.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SectionHeading(stringResource(R.string.settings_section_display))

            Setting(
                title = stringResource(R.string.settings_units),
                explanation = stringResource(R.string.settings_units_explanation),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    UnitSystem.entries.forEachIndexed { index, system ->
                        SegmentedButton(
                            selected = settings.units == system,
                            onClick = { viewModel.setUnits(system) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = UnitSystem.entries.size,
                            ),
                        ) {
                            Text(
                                stringResource(
                                    when (system) {
                                        UnitSystem.METRIC -> R.string.settings_units_metric
                                        UnitSystem.IMPERIAL -> R.string.settings_units_imperial
                                    }
                                )
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading(stringResource(R.string.settings_section_recording))

            // Committed when the thumb is let go, not while it moves. A write per drag
            // frame would be sixty disk writes a second, and every one of them republishes
            // the settings the whole tree is reading.
            var accuracy by remember(settings.maxAccuracyMeters) {
                mutableFloatStateOf(settings.maxAccuracyMeters.toFloat())
            }
            Setting(
                title = stringResource(R.string.settings_accuracy),
                explanation = stringResource(R.string.settings_accuracy_explanation),
                value = formatters.meters(accuracy.toDouble()),
            ) {
                Slider(
                    value = accuracy,
                    onValueChange = { accuracy = it },
                    onValueChangeFinished = {
                        viewModel.setMaxAccuracy(accuracy.roundToInt().toDouble())
                    },
                    valueRange = Settings.ACCURACY_RANGE.toFloatRange(),
                )
            }

            var displacement by remember(settings.minDisplacementMeters) {
                mutableFloatStateOf(settings.minDisplacementMeters.toFloat())
            }
            Setting(
                title = stringResource(R.string.settings_displacement),
                explanation = stringResource(R.string.settings_displacement_explanation),
                value = if (displacement < 0.5f) {
                    stringResource(R.string.settings_displacement_off)
                } else {
                    formatters.meters(displacement.toDouble())
                },
            ) {
                Slider(
                    value = displacement,
                    onValueChange = { displacement = it },
                    onValueChangeFinished = {
                        viewModel.setMinDisplacement(displacement.roundToInt().toDouble())
                    },
                    valueRange = Settings.DISPLACEMENT_RANGE.toFloatRange(),
                )
            }

            // Said once, here, rather than on each: they are read when a recording
            // starts, so changing one mid-ride would otherwise look broken.
            Text(
                text = stringResource(R.string.settings_recording_applies_next),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )

            Spacer(Modifier.height(16.dp))

            TextButton(
                onClick = viewModel::resetToDefaults,
                modifier = Modifier.padding(horizontal = ScreenPadding - 12.dp),
            ) {
                Text(stringResource(R.string.settings_reset))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// No `steps`: a discrete slider draws a tick per step, and a metre-per-step range of
// ninety-five of them is a dotted line, not a scale. The track is continuous and the
// value is rounded to a whole metre when the thumb is let go - which is the only place
// the roundness was ever visible.

private fun ClosedFloatingPointRange<Double>.toFloatRange(): ClosedFloatingPointRange<Float> =
    start.toFloat()..endInclusive.toFloat()

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
    )
}

/**
 * A setting: what it is, what it costs, and the control. The current value sits on the
 * title's line rather than under the control, so it can be read without following a thumb.
 */
@Composable
private fun Setting(
    title: String,
    explanation: String,
    value: String? = null,
    control: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        control()
    }
}
