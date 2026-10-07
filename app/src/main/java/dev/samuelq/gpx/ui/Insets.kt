package dev.samuelq.gpx.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** The bars' own insets, plus the cutout, which they leave out and landscape puts beside them. */
@OptIn(ExperimentalMaterial3Api::class)
internal val BarInsets: WindowInsets
    @Composable get() = TopAppBarDefaults.windowInsets.union(
        WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
    )

/** Content's inset from the edge of a screen or sheet. */
internal val EdgePadding = 20.dp
