package dev.samuelq.gpx.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Chart colours for the current theme. See [ChartColors] for why these aren't Material's. */
val LocalChartColors = staticCompositionLocalOf { ChartColors.Light }

@Composable
fun GpxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You. Available from Android 12; older devices fall back to the static scheme. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Gated on the *device's* version, not minSdk or targetSdk: wallpaper palette
        // extraction simply does not exist before Android 12.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> StaticDarkColors
        else -> StaticLightColors
    }

    CompositionLocalProvider(
        LocalChartColors provides if (darkTheme) ChartColors.Dark else ChartColors.Light,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
