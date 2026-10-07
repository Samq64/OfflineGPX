package dev.samuelq.gpx.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.track.RouteColors

private val Blue450 = Color(0xFF2A78D6)
private val Blue400 = Color(0xFF3987E5)

/** 3:1 on any wallpaper's sheet surface, as a chart mark needs, and apart from the blue under CVD. */
private val Green = Color(0xFF25852A)
private val GreenDark = Color(0xFF52B848)

private val RoutePaletteLight = RouteColors.LIGHT.map { Color(it) }

private val RoutePaletteDark = RouteColors.DARK.map { Color(it) }

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
            elevation = if (dark) GreenDark else Green,
            grid = scheme.outlineVariant,
            axis = scheme.outline,
            label = scheme.onSurfaceVariant,
        )
    }
}
