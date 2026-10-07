package dev.samuelq.gpx.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.db.TrackEntity

private val Blue450 = Color(0xFF2A78D6)
private val Blue400 = Color(0xFF3987E5)

/** 3:1 on the light surfaces wallpapers give, as a chart mark needs. */
private val Orange = Color(0xFFC9531F)
private val OrangeDark = Color(0xFFD95926)

/**
 * Picked by search for the hue each is named: at least 3:1 against map land (2:1 against water
 * and vegetation), and apart from each other and [recordingColor] by 25 CIEDE2000 (12 under
 * simulated CVD) in dark mode. Light mode gets 18 (7): a yellow dark enough for white land
 * leaves orange little room beside the red. Slots keep the hue of the colour stored there
 * before: blue became cyan, plum purple, violet blue, grey orange. Lower slots are assigned first.
 */
private val RoutePaletteLight = listOf(
    Color(0xFF2A95B9),
    Color(0xFF9765E9),
    Color(0xFF098745),
    Color(0xFFAA861B),
    Color(0xFFE45191),
    Color(0xFF3851A3),
    Color(0xFFB15D08),
).also { check(it.size == TrackEntity.PALETTE_SIZE) }

private val RoutePaletteDark = listOf(
    Color(0xFF20EDFF),
    Color(0xFF9321D4),
    Color(0xFF28C67A),
    Color(0xFFFCED54),
    Color(0xFFFE499B),
    Color(0xFF627FFE),
    Color(0xFFEC9424),
).also { check(it.size == TrackEntity.PALETTE_SIZE) }

/** Names for the slots, alike in both themes. */
val RouteColorNames = listOf(
    R.string.color_cyan,
    R.string.color_purple,
    R.string.color_green,
    R.string.color_yellow,
    R.string.color_pink,
    R.string.color_blue,
    R.string.color_orange,
).also { check(it.size == TrackEntity.PALETTE_SIZE) }

/** Slots round the colour wheel; the slots' own order is for assigning. */
val RoutePickerOrder = listOf(4, 6, 3, 2, 0, 5, 1)
    .also { check(it.sorted() == (0 until TrackEntity.PALETTE_SIZE).toList()) }

private val RecordingLight = Color(0xFFBA0D01)
private val RecordingDark = Color(0xFFDF2414)

fun List<Color>.slot(index: Int): Color = this[index.mod(size)]

/** Follows dark mode, not the wallpaper. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun routePalette(): List<Color> =
    if (androidx.compose.foundation.isSystemInDarkTheme()) RoutePaletteDark else RoutePaletteLight

/** Not the scheme's error colour, which is wallpaper-derived and pale pink in dark mode. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun recordingColor(): Color = if (androidx.compose.foundation.isSystemInDarkTheme()) RecordingDark else RecordingLight

/**
 * The series are fixed, not wallpaper-derived: their hues were checked for contrast and CVD
 * separation. Grid, axis and labels are chrome, so they follow the scheme like the text around them.
 */
data class ChartColors(val speed: Color, val elevation: Color, val grid: Color, val axis: Color, val label: Color) {
    companion object {
        fun of(scheme: ColorScheme, dark: Boolean) = ChartColors(
            speed = if (dark) Blue400 else Blue450,
            elevation = if (dark) OrangeDark else Orange,
            grid = scheme.outlineVariant,
            axis = scheme.outline,
            label = scheme.onSurfaceVariant,
        )
    }
}
