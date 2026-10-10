package dev.samuelq.gpx.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.RouteColor
import dev.samuelq.gpx.data.track.RouteColors

private val Blue450 = Color(0xFF2A78D6)
private val Blue400 = Color(0xFF3987E5)

/** 3:1 on any wallpaper's sheet surface, as a chart mark needs, and apart from the blue under CVD. */
private val Green = Color(0xFF25852A)
private val GreenDark = Color(0xFF6DB365)

private val RoutePaletteLight = RouteColors.LIGHT.map { Color(it) }

private val RoutePaletteDark = RouteColors.DARK.map { Color(it) }

/** Names for the slots, alike in both themes: Garmin's, which files carry. */
val RouteColorNames = listOf(
    R.string.color_red,
    R.string.color_yellow,
    R.string.color_green,
    R.string.color_cyan,
    R.string.color_blue,
    R.string.color_magenta,
).also { check(it.size == RouteColor.entries.size) }

fun List<Color>.slot(index: Int): Color = this[index.mod(size)]

/** Follows dark mode, not the wallpaper. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun routePalette(): List<Color> =
    if (androidx.compose.foundation.isSystemInDarkTheme()) RoutePaletteDark else RoutePaletteLight

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
