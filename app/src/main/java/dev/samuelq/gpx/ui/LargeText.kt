package dev.samuelq.gpx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity

/** Font scale past which layouts give text room rather than keep their normal shape. */
private const val LARGE_TEXT_SCALE = 1.3f

@Composable
@ReadOnlyComposable
internal fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
