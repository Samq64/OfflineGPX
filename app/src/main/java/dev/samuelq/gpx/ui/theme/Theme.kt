package dev.samuelq.gpx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

internal val LocalChartColors = staticCompositionLocalOf<ChartColors> { error("Outside GpxTheme") }

@Composable
fun GpxTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    CompositionLocalProvider(
        LocalChartColors provides ChartColors.of(colorScheme, darkTheme),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
