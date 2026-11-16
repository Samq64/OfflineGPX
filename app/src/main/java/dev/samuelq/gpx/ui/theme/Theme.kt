package dev.samuelq.gpx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.samuelq.gpx.data.settings.Settings

internal val LocalChartColors = staticCompositionLocalOf<ChartColors> { error("Outside GpxTheme") }

internal val LocalRoutePalette = staticCompositionLocalOf<List<Color>> { error("Outside GpxTheme") }

/** Light or dark as the configuration says, which the theme setting overrides per app. */
@Composable
fun GpxTheme(settings: Settings, content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = dynamicScheme(darkTheme)
    val routeArgb = if (darkTheme) settings.routeColorsDark else settings.routeColorsLight
    val routePalette = remember(routeArgb) { routeArgb.map(::Color) }

    CompositionLocalProvider(
        LocalChartColors provides ChartColors.of(colorScheme, darkTheme),
        LocalRoutePalette provides routePalette,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

/** The wallpaper's scheme in either theme. Keyed on the configuration, as a new wallpaper arrives as one. */
@Composable
fun dynamicScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration, dark) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
}
