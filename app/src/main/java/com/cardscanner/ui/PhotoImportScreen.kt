package com.cardscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardscanner.PhotoImportState
import com.cardscanner.ScannerViewModel
import com.cardscanner.detection.Corners
import com.cardscanner.detection.MultiCard
import com.cardscanner.detection.Pt
import kotlin.math.min

/**
 * A photo with several cards: the outlines found are shown on it to be checked before anything is
 * queued - tap a card to leave it out; or lay a grid (a binder page) over the photo, with the
 * page's corners dragged to the page. Each card then goes through the same capture path as a
 * scanned one.
 */
@Composable
fun PhotoImportScreen(viewModel: ScannerViewModel, onClose: () -> Unit) {
    val state by viewModel.photoImport.collectAsStateWithLifecycle()
    val busy by viewModel.photoBusy.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    BackHandler { viewModel.cancelPhotoImport(); if (viewModel.photoImport.value == null) onClose() }
    val photoState = state
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Import photo", "Cards are found, checked by you, then read one at a time",
            onBack = { viewModel.cancelPhotoImport(); if (viewModel.photoImport.value == null) onClose() })
        if (photoState == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(busy ?: "Opening the photo…")
                }
            }
        } else {
            // (a new state - after a partly failed import, only the cards left - starts the preview afresh)
            key(photoState) {
                PhotoPreview(photoState, busy, serverMode = settings.serverMode, onImport = viewModel::importPhotoCards,
                    onCancel = { viewModel.cancelPhotoImport(); onClose() })
            }
        }
    }
}

private val Found = Color(0xFF3DDC84)
private val LeftOut = Color(0x99FF5252)
private val Handle = Color(0xFFFFC107)

@Composable
private fun ColumnScope.PhotoPreview(state: PhotoImportState, busy: String?, serverMode: Boolean,
                         onImport: (List<Corners>) -> Unit, onCancel: () -> Unit) {
    val width = state.photo.width.toDouble(); val height = state.photo.height.toDouble()
    var picking by remember { mutableStateOf(!state.retry) }
    var gridMode by remember { mutableStateOf(state.detected.isEmpty()) }
    var columns by remember { mutableIntStateOf(3) }
    var rows by remember { mutableIntStateOf(3) }
    var page by remember {
        mutableStateOf(listOf(Pt(width * 0.05, height * 0.05), Pt(width * 0.95, height * 0.05),
            Pt(width * 0.95, height * 0.95), Pt(width * 0.05, height * 0.95)))
    }
    var leftOut by remember { mutableStateOf(setOf<Int>()) }
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableIntStateOf(-1) }
    val measurer = rememberTextMeasurer()
    val image = remember(state.photo) { state.photo.asImageBitmap() }

    val pageNow by rememberUpdatedState(page)
    val outlines = if (gridMode) MultiCard.gridCells(page, columns, rows) else state.detected
    val chosen = outlines.filterIndexed { i, _ -> i !in leftOut }

    if (picking) {
        LayoutChoice(state, onFound = { gridMode = false; picking = false },
            onGrid = { c, r -> columns = c; rows = r; gridMode = true; leftOut = emptySet(); picking = false },
            onCancel = onCancel)
        return
    }

    // photo pixels <-> canvas pixels (the photo fitted in the canvas)
    val fit = if (canvas.width == 0) 1.0 else min(canvas.width / width, canvas.height / height)
    val originX = (canvas.width - width * fit) / 2
    val originY = (canvas.height - height * fit) / 2
    fun toCanvas(p: Pt) = Offset((p.x * fit + originX).toFloat(), (p.y * fit + originY).toFloat())
    fun toPhoto(o: Offset) = Pt((o.x - originX) / fit, (o.y - originY) / fit)

    Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).onSizeChanged { canvas = it }
        .pointerInput(gridMode, outlines, canvas) {
            detectTapGestures { tap ->
                val p = toPhoto(tap)
                outlines.indexOfFirst { MultiCard.contains(it, p) }.takeIf { it >= 0 }?.let { hit ->
                    leftOut = if (hit in leftOut) leftOut - hit else leftOut + hit
                }
            }
        }
        .pointerInput(gridMode, canvas) {
            if (!gridMode) return@pointerInput
            detectDragGestures(
                onDragStart = { start ->
                    val p = toPhoto(start)
                    val reach = 56.dp.toPx() / fit  // in photo pixels
                    val nearest = pageNow.indices.minBy { (pageNow[it] - p).norm() }
                    dragging = if ((pageNow[nearest] - p).norm() <= reach) nearest else -1
                },
                onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 },
            ) { change, drag ->
                if (dragging >= 0) {
                    change.consume()
                    val moved = pageNow[dragging] + Pt(drag.x / fit, drag.y / fit)
                    page = pageNow.mapIndexed { i, pt -> if (i == dragging) Pt(moved.x.coerceIn(0.0, width), moved.y.coerceIn(0.0, height)) else pt }
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            drawImage(image, dstOffset = IntOffset(originX.toInt(), originY.toInt()),
                dstSize = IntSize((width * fit).toInt(), (height * fit).toInt()))
            outlines.forEachIndexed { i, outline ->
                val colour = if (i in leftOut) LeftOut else Found
                val path = Path().apply {
                    moveTo(toCanvas(outline[0]).x, toCanvas(outline[0]).y)
                    for (k in 1..3) toCanvas(outline[k]).let { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, colour.copy(alpha = if (i in leftOut) 0.15f else 0.22f))
                drawPath(path, colour, style = Stroke(width = 3.dp.toPx()))
                drawText(measurer, (i + 1).toString(), toCanvas(outline[0]) + Offset(6.dp.toPx(), 4.dp.toPx()),
                    style = TextStyle(color = Color.White, fontSize = 16.sp))
            }
            if (gridMode) {
                val path = Path().apply {
                    moveTo(toCanvas(page[0]).x, toCanvas(page[0]).y)
                    for (k in 1..3) toCanvas(page[k]).let { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, Handle, style = Stroke(width = 2.dp.toPx()))
                page.forEach { drawCircle(Handle, radius = 12.dp.toPx(), center = toCanvas(it)) }
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !gridMode, onClick = { gridMode = false; leftOut = emptySet() },
                label = { Text("Found (${state.detected.size})") }, enabled = busy == null)
            // (a retry holds just the cards not saved: a grid would bring back the saved ones)
            if (!state.retry) FilterChip(selected = gridMode, onClick = { gridMode = true; leftOut = emptySet() },
                label = { Text("Grid") }, enabled = busy == null)
            if (gridMode) {
                Stepper("Columns", columns, busy == null) { columns = it.coerceIn(1, 8); leftOut = emptySet() }
                Stepper("Rows", rows, busy == null) { rows = it.coerceIn(1, 8); leftOut = emptySet() }
            }
        }
        Text(
            if (gridMode) "Drag the yellow corners to the page's corners; tap a card to leave it out."
            else if (state.retry) "These cards could not be saved - Import tries just them again."
            else if (state.detected.isEmpty()) "No cards found - use Grid and drag its corners to the page."
            else "Tap a card to leave it out. A card missed or cut wrong? Use Grid.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (serverMode) "The server reads each card: confirmed ones are added, the others wait in its review queue."
            else "Every card waits in the review queue for you to check.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onCancel, enabled = busy == null) { Text("Cancel") }
            Button(onClick = { onImport(chosen) }, enabled = busy == null && chosen.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text(busy ?: "Import ${chosen.size} card${if (chosen.size == 1) "" else "s"}")
            }
        }
    }
}

/** What the photo shows: the layout of a binder page (9 or 12 pockets, either way up) or loose cards */
@Composable
private fun ColumnScope.LayoutChoice(state: PhotoImportState, onFound: () -> Unit,
                                     onGrid: (columns: Int, rows: Int) -> Unit, onCancel: () -> Unit) {
    val image = remember(state.photo) { state.photo.asImageBitmap() }
    Canvas(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
        val fit = min(size.width / image.width, size.height / image.height)
        val w = (image.width * fit).toInt(); val h = (image.height * fit).toInt()
        drawImage(image, dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
            dstSize = IntSize(w, h))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What is in the photo?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ onGrid(3, 3) }, Modifier.weight(1f)) { Text("9 pockets\n3 × 3") }
            Button({ onGrid(4, 3) }, Modifier.weight(1f)) { Text("12 pockets\n4 × 3") }
            Button({ onGrid(3, 4) }, Modifier.weight(1f)) { Text("12 pockets\n3 × 4") }
        }
        OutlinedButton(onFound, Modifier.fillMaxWidth()) {
            Text(if (state.detected.isEmpty()) "Loose cards (none found yet)" else "Loose cards (${state.detected.size} found)")
        }
        Text("A binder page is then laid over the photo: drag its corners to the page. Another size? Change it in the next step.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onCancel, Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun Stepper(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.TextButton(onClick = { onChange(value - 1) }, enabled = enabled) { Text("−") }
        Text("$label $value", style = MaterialTheme.typography.labelMedium)
        androidx.compose.material3.TextButton(onClick = { onChange(value + 1) }, enabled = enabled) { Text("+") }
    }
}
