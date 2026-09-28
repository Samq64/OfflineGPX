package dev.samuelq.gpx.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.data.db.TrackEntity

// Static scheme for Android 10/11, which lack wallpaper colours; shares the charts' ramp.

private val Blue450 = Color(0xFF2A78D6)
private val Blue400 = Color(0xFF3987E5)
private val Blue100 = Color(0xFFCDE2FB)
private val Blue600 = Color(0xFF184F95)
private val Blue700 = Color(0xFF0D366B)

private val Orange = Color(0xFFEB6834)
private val OrangeDark = Color(0xFFD95926)

private val Ink = Color(0xFF0B0B0B)
private val InkSecondary = Color(0xFF52514E)
private val Muted = Color(0xFF898781)
private val Hairline = Color(0xFFE1E0D9)
private val HairlineDark = Color(0xFF2C2C2A)
private val Baseline = Color(0xFFC3C2B7)
private val BaselineDark = Color(0xFF383835)

private val SurfaceLight = Color(0xFFFCFCFB)
private val SurfaceDark = Color(0xFF1A1A19)
private val SurfaceContainerLight = Color(0xFFF4F3F0)
private val SurfaceContainerDark = Color(0xFF232322)
private val SurfaceVariantLight = Color(0xFFF0EFEC)

private val Critical = Color(0xFFD03B3B)
private val CriticalDark = Color(0xFFE66767)

internal val StaticLightColors = lightColorScheme(
    primary = Blue450,
    onPrimary = Color.White,
    primaryContainer = Blue100,
    onPrimaryContainer = Blue700,
    secondary = InkSecondary,
    onSecondary = Color.White,
    secondaryContainer = Hairline,
    onSecondaryContainer = Ink,
    tertiary = Orange,
    onTertiary = Color.White,
    background = SurfaceLight,
    onBackground = Ink,
    surface = SurfaceLight,
    onSurface = Ink,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = InkSecondary,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = Color(0xFFEFEEEA),
    outline = Muted,
    outlineVariant = Hairline,
    error = Critical,
    onError = Color.White,
)

internal val StaticDarkColors = darkColorScheme(
    primary = Blue400,
    onPrimary = Color(0xFF06203F),
    primaryContainer = Blue600,
    onPrimaryContainer = Blue100,
    secondary = Baseline,
    onSecondary = Ink,
    secondaryContainer = BaselineDark,
    onSecondaryContainer = Color.White,
    tertiary = OrangeDark,
    onTertiary = Color.White,
    background = SurfaceDark,
    onBackground = Color.White,
    surface = SurfaceDark,
    onSurface = Color.White,
    surfaceVariant = BaselineDark,
    onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = Color(0xFF2B2B29),
    outline = Muted,
    outlineVariant = HairlineDark,
    error = CriticalDark,
    onError = Ink,
)

/**
 * Picked by search: at least 3:1 against map land (2:1 against water and vegetation), and
 * 25 CIEDE2000 apart from each other and [recordingColor] (12 under simulated CVD). Ordered
 * so the lowest slots are furthest apart.
 */
private val RoutePaletteLight = listOf(
    Color(0xFF1292C0),
    Color(0xFF722756),
    Color(0xFF187C49),
    Color(0xFF9D8519),
    Color(0xFFD65C88),
    Color(0xFF6B3CFB),
).also { check(it.size == TrackEntity.PALETTE_SIZE) }

private val RoutePaletteDark = listOf(
    Color(0xFF30C0F8),
    Color(0xFFB82989),
    Color(0xFF269E5F),
    Color(0xFFD6BD5C),
    Color(0xFFEF90AE),
    Color(0xFF8472FE),
).also { check(it.size == TrackEntity.PALETTE_SIZE) }

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

/** Fixed, not wallpaper-derived, since the hues were checked for contrast and CVD separation. */
data class ChartColors(
    val speed: Color,
    val elevation: Color,
    val grid: Color,
    val axis: Color,
    val label: Color,
) {
    companion object {
        val Light = ChartColors(
            speed = Blue450,
            elevation = Orange,
            grid = Hairline,
            axis = Baseline,
            label = Muted,
        )

        val Dark = ChartColors(
            speed = Blue400,
            elevation = OrangeDark,
            grid = HairlineDark,
            axis = BaselineDark,
            label = Muted,
        )
    }
}
