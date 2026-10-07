package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.ui.theme.RouteColorNames
import dev.samuelq.gpx.ui.theme.RoutePickerOrder
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.theme.slot

/** The map line's hue; a tap picks another from the palette. */
@Composable
fun ColorDot(colorIndex: Int, onColor: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(
        R.string.library_color,
        stringResource(RouteColorNames[colorIndex.mod(RouteColorNames.size)]),
    )
    Box {
        Box(
            Modifier
                .size(ColorDotSize)
                .clip(CircleShape)
                // Its colour even when hidden: it's the picker, and the switch says hidden.
                .background(routePalette().slot(colorIndex))
                .clickable(onClickLabel = stringResource(R.string.library_color_change)) { open = true }
                .semantics { contentDescription = label }
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            ColorSwatches(colorIndex) {
                open = false
                onColor(it)
            }
        }
    }
}

val ColorDotSize = 20.dp

/** The route palette as radio buttons, for a menu. [onPick] only for a different colour. */
@Composable
fun ColorSwatches(colorIndex: Int, onPick: (Int) -> Unit) {
    val palette = routePalette()
    // Seven 48dp targets need about 380dp; narrower windows get two rows.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    FlowRow(
        modifier = Modifier.padding(horizontal = 8.dp),
        maxItemsInEachRow = if (windowWidth >= 380.dp) RoutePickerOrder.size else 4,
    ) {
        RoutePickerOrder.forEach { index ->
            val color = palette[index]
            val isSelected = index == colorIndex.mod(palette.size)
            val label = stringResource(RouteColorNames[index])
            Box(
                Modifier
                    .size(48.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        if (!isSelected) onPick(index)
                    }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    // Pale swatches need a dark tick.
                    val tick = if (color.luminance() > 0.4f) Color.Black else Color.White
                    if (isSelected) Icon(painterResource(R.drawable.ic_check), null, tint = tick)
                }
            }
        }
    }
}
