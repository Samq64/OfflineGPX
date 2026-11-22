package dev.samuelq.gpx.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.RouteColor
import dev.samuelq.gpx.data.track.RouteColors
import dev.samuelq.gpx.ui.BackButton
import dev.samuelq.gpx.ui.BarInsets
import dev.samuelq.gpx.ui.DialogTitle
import dev.samuelq.gpx.ui.EdgePadding
import dev.samuelq.gpx.ui.map.MapRenderTheme
import dev.samuelq.gpx.ui.readableWidth
import dev.samuelq.gpx.ui.theme.RouteColorNames
import dev.samuelq.gpx.ui.theme.dynamicScheme
import java.util.Locale

/** The route palettes of both themes, each shown on its own map land. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackColorsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Slot and theme, saved so the dialog survives a rotation.
    var editing by rememberSaveable { mutableStateOf<Pair<Boolean, Int>?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                windowInsets = BarInsets,
                title = { Text(stringResource(R.string.settings_track_colors), Modifier.semantics { heading() }) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.track_colors_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = EdgePadding),
            )
            for (dark in listOf(false, true)) {
                Palette(
                    dark = dark,
                    colors = if (dark) settings.routeColorsDark else settings.routeColorsLight,
                    onEdit = { slot -> editing = dark to slot },
                    onReset = { slot -> viewModel.setRouteColor(dark, RouteColor.entries[slot], null) },
                )
            }
        }
    }

    editing?.let { (dark, slot) ->
        val colors = if (dark) settings.routeColorsDark else settings.routeColorsLight
        val defaults = if (dark) RouteColors.DARK else RouteColors.LIGHT
        ColorDialog(
            slot = slot,
            palette = colors,
            dark = dark,
            onDismiss = { editing = null },
            onConfirm = { argb ->
                editing = null
                viewModel.setRouteColor(dark, RouteColor.entries[slot], argb.takeIf { it != defaults[slot] })
            },
        )
    }
}

@Composable
private fun Palette(dark: Boolean, colors: List<Int>, onEdit: (Int) -> Unit, onReset: (Int) -> Unit) {
    val scheme = dynamicScheme(dark)
    val defaults = if (dark) RouteColors.DARK else RouteColors.LIGHT
    SectionHeading(stringResource(if (dark) R.string.track_colors_dark else R.string.track_colors_light))
    Column(
        Modifier
            .padding(horizontal = EdgePadding)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLowest),
    ) {
        colors.forEachIndexed { slot, argb ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onEdit(slot) }
                    .padding(horizontal = 16.dp),
            ) {
                RouteSample(Color(argb), Modifier.width(64.dp))
                Text(
                    stringResource(RouteColorNames[slot]),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (argb != defaults[slot]) {
                    TextButton(
                        onClick = { onReset(slot) },
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.primary),
                    ) {
                        Text(stringResource(R.string.track_colors_reset))
                    }
                }
            }
        }
    }
}

/** A stroke as wide as the map draws one, so contrast is judged as it will be seen. */
@Composable
private fun RouteSample(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(24.dp)) {
        val y = size.height / 2
        drawLine(color, Offset(4.dp.toPx(), y), Offset(size.width - 4.dp.toPx(), y), 5.dp.toPx(), StrokeCap.Round)
    }
}

/** Hex only: the screen is for those who know what they want. Warnings advise, never block. */
@Composable
private fun ColorDialog(
    slot: Int,
    palette: List<Int>,
    dark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val land = dynamicScheme(dark).surfaceContainerLowest
    val ground = remember(land, dark) {
        MapGround(
            land = land.toArgb(),
            water = MapRenderTheme.waterBlue(dark).toColorInt(),
            vegetation = MapRenderTheme.greenVegetation(dark).toColorInt(),
        )
    }
    var hex by rememberSaveable { mutableStateOf(palette[slot].hex()) }
    val argb = parseHex(hex)
    val warning = remember(argb, palette, ground) {
        argb?.let { colorWarning(it, slot, palette, dark, ground) }
    }
    val name = stringResource(RouteColorNames[slot])

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GroundSample(argb?.let(::Color), ground)
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text -> hex = text.filter { it == '#' || it.isLetterOrDigit() }.take(7) },
                    label = { Text(stringResource(R.string.track_colors_hex)) },
                    singleLine = true,
                    isError = argb == null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                    // Always present, if empty, so the dialog doesn't jump as a warning comes and goes.
                    supportingText = {
                        Text(
                            text = when (warning) {
                                null -> ""
                                ColorWarning.LowContrast -> stringResource(R.string.track_colors_low_contrast)
                                // Mid-sentence, where English doesn't capitalise a colour.
                                is ColorWarning.CloseTo -> stringResource(
                                    R.string.track_colors_close_to,
                                    stringResource(RouteColorNames[warning.slot]).lowercase(Locale.getDefault()),
                                )
                            },
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { argb?.let(onConfirm) }, enabled = argb != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The route across land, vegetation and water, the grounds its contrast is checked on. */
@Composable
private fun GroundSample(color: Color?, ground: MapGround) {
    Canvas(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))) {
        val third = size.width / 3
        drawRect(Color(ground.land))
        drawRect(Color(ground.vegetation), Offset(third, 0f), Size(third, size.height))
        drawRect(Color(ground.water), Offset(2 * third, 0f), Size(third, size.height))
        if (color != null) {
            val y = size.height / 2
            drawLine(color, Offset(12.dp.toPx(), y), Offset(size.width - 12.dp.toPx(), y), 5.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Opaque, from `#RRGGBB` or `RRGGBB`. */
internal fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) return null
    return digits.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}

private fun Int.hex() = String.format(Locale.ROOT, "#%06X", this and 0xFFFFFF)
