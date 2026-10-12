package com.cardscanner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardscanner.ScannerViewModel
import com.cardscanner.server.ServerOutcome

/** A window over the scanner: browsing never disposes its camera or pauses auto capture. */
@Composable
fun InventoryBrowser(viewModel: ScannerViewModel, onClose: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs: @Composable () -> Unit = {
        if (!settings.serverMode) TabRow(selectedTabIndex = selected) {
            Tab(selected == 0, onClick = { selected = 0 }, text = { Text("Scan progress") })
            Tab(selected == 1, onClick = { selected = 1 }, text = { Text("Collection") })
        }
    }
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = isSystemInDarkTheme()
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (selected == 1 && !settings.serverMode) InventoryScreen(viewModel, onClose, tabs)
            else ScanProgressScreen(viewModel, onClose, tabs)
        }
    }
}

@Composable
private fun ScanProgressScreen(viewModel: ScannerViewModel, onClose: () -> Unit, tabs: @Composable () -> Unit) {
    val scans by viewModel.scans.collectAsStateWithLifecycle()
    val waiting by viewModel.waiting.collectAsStateWithLifecycle()
    val auto by viewModel.autoCapture.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val added = scans.count { it.inventoryId != null || (it.server?.status == ServerOutcome.Status.ADDED && !it.undone) }
    val review = scans.count { it.reviewId != null || it.server?.status == ServerOutcome.Status.REVIEW }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Inventory", "$added added · $review to review · $waiting processing", onClose) {
            IconButton(onClick = viewModel::clearScans, enabled = scans.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, "Remove all scans")
            }
        }
        tabs()
        Text(if (auto) "Auto scan is running while you browse" else "Auto scan is off",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (settings.serverMode) item {
                TextButton(onClick = {
                    viewModel.serverPageUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                }, enabled = viewModel.serverPageUrl != null) { Text("Open collection and review on server") }
            }
            item { Text("Recent scans · latest 100", style = MaterialTheme.typography.labelLarge) }
            if (scans.isEmpty()) item { EmptyFeed("No scans yet", "Captured cards appear here as they are identified.") }
            items(scans, key = { it.id }) { ScanCard(viewModel, it) }
        }
    }
}
