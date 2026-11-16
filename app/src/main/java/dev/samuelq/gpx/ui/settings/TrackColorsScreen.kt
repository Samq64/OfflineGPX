package dev.samuelq.gpx.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.RouteColor
import dev.samuelq.gpx.data.track.RouteColors
import dev.samuelq.gpx.ui.BackButton
import dev.samuelq.gpx.ui.BarInsets
import dev.samuelq.gpx.ui.DialogTitle
import dev.samuelq.gpx.ui.EdgePadding
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
                    onReset = { viewModel.resetRouteColors(dark) },
                )
            }
        }
    }

    editing?.let { (dark, slot) ->
        val colors = if (dark) settings.routeColorsDark else settings.routeColorsLight
        val defaults = if (dark) RouteColors.DARK else RouteColors.LIGHT
        ColorDialog(
            title = stringResource(RouteColorNames[slot]),
            initial = colors[slot],
            default = defaults[slot],
            land = dynamicScheme(dark).surfaceContainerLowest,
            onDismiss = { editing = null },
            onConfirm = { argb ->
                editing = null
                viewModel.setRouteColor(dark, RouteColor.entries[slot], argb.takeIf { it != defaults[slot] })
            },
        )
    }
}

@Composable
private fun Palette(dark: Boolean, colors: List<Int>, onEdit: (Int) -> Unit, onReset: () -> Unit) {
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
            val name = stringResource(RouteColorNames[slot])
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
                Text(name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            }
        }
    }
    Row(Modifier.padding(horizontal = EdgePadding - 12.dp)) {
        TextButton(onClick = onReset, enabled = colors != defaults) {
            Text(stringResource(R.string.track_colors_reset))
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

/** HSL rather than RGB sliders: hue is what names a slot, so it should move alone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorDialog(
    title: String,
    initial: Int,
    default: Int,
    land: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val start = remember(initial) { FloatArray(3).also { ColorUtils.colorToHSL(initial, it) } }
    var hue by rememberSaveable { mutableFloatStateOf(start[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(start[1]) }
    var lightness by rememberSaveable { mutableFloatStateOf(start[2]) }
    var hex by rememberSaveable { mutableStateOf(initial.hex()) }

    val argb = ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
    fun slid() {
        hex = ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)).hex()
    }
    fun load(color: Int) {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
        hue = hsl[0]
        saturation = hsl[1]
        lightness = hsl[2]
        hex = color.hex()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(land).padding(8.dp),
                ) {
                    RouteSample(Color(argb), Modifier.fillMaxWidth())
                }
                GradientSlider(
                    label = stringResource(R.string.track_colors_hue),
                    value = hue / 360f,
                    onValueChange = {
                        hue = it * 360f
                        slid()
                    },
                    colors = (0..6).map { Color.hsl(it * 60f % 360f, saturation, lightness) },
                )
                GradientSlider(
                    label = stringResource(R.string.track_colors_saturation),
                    value = saturation,
                    onValueChange = {
                        saturation = it
                        slid()
                    },
                    colors = listOf(Color.hsl(hue, 0f, lightness), Color.hsl(hue, 1f, lightness)),
                )
                GradientSlider(
                    label = stringResource(R.string.track_colors_lightness),
                    value = lightness,
                    onValueChange = {
                        lightness = it
                        slid()
                    },
                    colors = listOf(Color.Black, Color.hsl(hue, saturation, 0.5f), Color.White),
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text.take(7)
                        parseHex(text)?.let { color ->
                            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
                            hue = hsl[0]
                            saturation = hsl[1]
                            lightness = hsl[2]
                        }
                    },
                    label = { Text(stringResource(R.string.track_colors_hex)) },
                    singleLine = true,
                    isError = parseHex(hex) == null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(argb) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { load(default) }, enabled = argb != default) {
                    Text(stringResource(R.string.track_colors_default))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** A slider over its own range of colours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(label: String, value: Float, onValueChange: (Float) -> Unit, colors: List<Color>) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.semantics { contentDescription = label },
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(colors)),
                )
            },
        )
    }
}

/** Opaque, from `#RRGGBB` or `RRGGBB`. */
internal fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) return null
    return digits.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}

private fun Int.hex() = String.format(Locale.ROOT, "#%06X", this and 0xFFFFFF)
