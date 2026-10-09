package dev.samuelq.gpx.ui.format

import androidx.compose.runtime.staticCompositionLocalOf

/** Static: units change so rarely that observing them isn't worth paying for. */
internal val LocalFormatters = staticCompositionLocalOf { Formatters.Metric }
