package dev.samuelq.gpx.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Static fallback scheme, used on Android 10/11 (no wallpaper extraction) and whenever
// the user turns dynamic colour off. Built from the same validated ramp as the charts so
// the two never look like they came from different apps.

private val Blue450 = Color(0xFF2A78D6)
private val Blue400 = Color(0xFF3987E5)
private val Blue100 = Color(0xFFCDE2FB)
private val Blue550 = Color(0xFF1C5CAB)
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
 * Colours the charts draw with, held apart from the Material scheme deliberately.
 *
 * These two hues were checked for lightness band, chroma floor, CVD separation and
 * contrast against both surfaces; a wallpaper-derived palette guarantees none of that, and
 * could land on two hues a colourblind reader cannot tell apart. Chrome follows the
 * wallpaper; data does not.
 */
data class ChartColors(
    val speed: Color,
    val elevation: Color,
    val grid: Color,
    val axis: Color,
    val label: Color,
    val surface: Color,
) {
    /** The 10% wash used under a line. Never a saturated block. */
    fun fill(series: Color): Color = series.copy(alpha = 0.10f)

    companion object {
        val Light = ChartColors(
            speed = Blue450,
            elevation = Orange,
            grid = Hairline,
            axis = Baseline,
            label = Muted,
            surface = SurfaceLight,
        )

        val Dark = ChartColors(
            speed = Blue400,
            elevation = OrangeDark,
            grid = HairlineDark,
            axis = BaselineDark,
            label = Muted,
            surface = SurfaceDark,
        )
    }
}
