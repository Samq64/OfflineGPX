package dev.samuelq.gpx.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.samuelq.gpx.R
import dev.samuelq.gpx.ui.BackButton
import dev.samuelq.gpx.ui.BarInsets
import dev.samuelq.gpx.ui.EdgePadding
import dev.samuelq.gpx.ui.readFirst
import dev.samuelq.gpx.ui.readableWidth
import dev.samuelq.gpx.ui.screenSnackbars
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val snackbars = screenSnackbars()
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbars.host, Modifier.readFirst()) },
        topBar = {
            TopAppBar(
                windowInsets = BarInsets,
                title = { Text(stringResource(R.string.settings_about_libraries), Modifier.semantics { heading() }) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
                .padding(vertical = 8.dp),
        ) {
            LIBRARIES.forEach { library ->
                LibraryRow(library) {
                    if (!context.openUrl(library.url)) {
                        scope.launch { snackbars.say(resources.getString(R.string.settings_maps_no_browser)) }
                    }
                }
            }
        }
    }
}

/** The browser fetches it, so no INTERNET permission is needed. False when nothing can. */
internal fun Context.openUrl(url: String): Boolean =
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }.isSuccess

private class Library(val name: String, val licence: String, val url: String)

private val LIBRARIES = listOf(
    Library("AndroidX", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    Library("JTS", "EDL-1.0", "https://github.com/locationtech/jts"),
    Library("Kotlin", "Apache-2.0", "https://kotlinlang.org"),
    // Copied in as vector drawables.
    Library("Material Symbols", "Apache-2.0", "https://github.com/google/material-design-icons"),
    // Bundled inside core-location-altitude.
    Library("Protocol Buffers", "BSD-3-Clause", "https://github.com/protocolbuffers/protobuf"),
    Library("VTM", "LGPL-3.0", "https://github.com/mapsforge/vtm"),
)

/** The whole row opens the project; a plain Row, since ListItem pads far more. */
@Composable
private fun LibraryRow(library: Library, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.settings_about_open_site), onClick = onClick)
            .padding(horizontal = EdgePadding),
    ) {
        Text(library.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            library.licence,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
