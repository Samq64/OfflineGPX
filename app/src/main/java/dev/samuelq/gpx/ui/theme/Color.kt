package dev.samuelq.gpx.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.db.TrackEntity

// Static scheme for Android 10/11, which lack wallpaper colours: Material's 2021 tonal spot from
// Android 10's own accent, #1A73E8, so it looks like what 12+ makes from a wallpaper.

private val Blue450 = Color(0xFF2A78D6)
private val Blue400 = Color(0xFF3987E5)

/** 3:1 on the light surfaces wallpapers give, as a chart mark needs. */
private val Orange = Color(0xFFC9531F)
private val OrangeDark = Color(0xFFD95926)

internal val StaticLightColors = lightColorScheme(
    primary = Color(0xFF435E91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF2A4678),
    inversePrimary = Color(0xFFADC7FF),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF3F4759),
    tertiary = Color(0xFF715574),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBD7FC),
    onTertiaryContainer = Color(0xFF583E5B),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = Color(0xFF435E91),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

internal val StaticDarkColors = darkColorScheme(
    primary = Color(0xFFADC7FF),
    onPrimary = Color(0xFF0F2F60),
    primaryContainer = Color(0xFF2A4678),
    onPrimaryContainer = Color(0xFFD8E2FF),
    inversePrimary = Color(0xFF435E91),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDEBCDF),
    onTertiary = Color(0xFF402843),
    tertiaryContainer = Color(0xFF583E5B),
    onTertiaryContainer = Color(0xFFFBD7FC),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFADC7FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainerLowest = Color(0xFF0C0E13),
)

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
fun recordingColor(): Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) RecordingDark else RecordingLight

/**
 * The series are fixed, not wallpaper-derived: their hues were checked for contrast and CVD
 * separation. Grid, axis and labels are chrome, so they follow the scheme like the text around them.
 */
data class ChartColors(
    val speed: Color,
    val elevation: Color,
    val grid: Color,
    val axis: Color,
    val label: Color,
) {
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
