package dev.samuelq.gpx.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Material's widest bottom sheet, and wide enough for any row in the app. */
private val ReadableWidth = 640.dp

/** Centred, and no wider than a row reads well; a phone is narrower anyway. */
internal fun Modifier.readableWidth(): Modifier = fillMaxWidth().wrapContentWidth().widthIn(max = ReadableWidth)
