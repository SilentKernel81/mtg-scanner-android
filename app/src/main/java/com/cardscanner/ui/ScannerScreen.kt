package com.cardscanner.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.magnifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardscanner.ScannerViewModel
import com.cardscanner.detection.CardTracker
import android.widget.Toast

/**
 * The main screen: the camera with the detected outline (or the fixed area) and a status chip,
 * the capture controls and the latest result. Inventory opens scrollable live scan progress
 * over the scanner; standalone mode also offers the full local collection.
 */
@Composable
fun ScannerScreen(viewModel: ScannerViewModel, onSettings: () -> Unit, onInventory: () -> Unit, onReview: () -> Unit) {
    val detection by viewModel.detection.collectAsStateWithLifecycle()
    val latestScan by viewModel.latestScan.collectAsStateWithLifecycle()
    val auto by viewModel.autoCapture.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var torch by remember { mutableStateOf(false) }
    var drawingArea by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val areaOn = settings.activeArea != null

    val entries by viewModel.inventoryEntries.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val waiting by viewModel.waiting.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.clearMessage() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
    // Use almost the full screen width, reserving space for the header, controls and results
    // on shorter displays. Keep the sensor aspect ratio so overlays stay aligned.
    val sideways = settings.rotation % 180 != 0
    val aspect = if (sideways) 4f / 3f else 3f / 4f
    val cameraHeight = (maxHeight - 340.dp).coerceAtLeast(100.dp)
    val cameraWidth = minOf(maxWidth - 16.dp, cameraHeight * aspect)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Card Scanner", if (settings.serverMode) "Server mode · captures saved before sending" else "Scan. Collect. Repeat.") {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        // Camera view with the detected outline: 3:4 like the (portrait) camera frames, so frame
        // coordinates map linearly onto it
        Box(Modifier.width(cameraWidth).aspectRatio(aspect).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            CameraPreview(viewModel, torch, settings.rotation)
            if (drawingArea) {
                AreaDrawer(
                    initial = settings.fixedArea,
                    onDrawn = { viewModel.setFixedArea(it); drawingArea = false },
                    onUseDetected = {
                        if (viewModel.useDetectedCard()) drawingArea = false
                        else Toast.makeText(context, "No card detected", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { drawingArea = false },
                )
            } else {
                OutlineOverlay(detection)
                StatusChip(detection, auto, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = auto, onCheckedChange = viewModel::setAutoCapture)
                Text("Auto scan", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
            Button(onClick = viewModel::manualCapture, modifier = Modifier.weight(1f)) { Text("Capture card") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (settings.fixedArea == null) drawingArea = true else viewModel.setFixedAreaEnabled(!settings.fixedAreaEnabled)
            }) {
                Icon(Icons.Default.Crop, "Fixed area", tint = if (areaOn) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            IconButton(onClick = viewModel::rotate) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate image") }
            IconButton(onClick = { torch = !torch }) {
                Icon(if (torch) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, "Light")
            }
            if (areaOn) TextButton(onClick = { drawingArea = true }) { Text("Redraw area") }
            Spacer(Modifier.weight(1f))
            if (latestScan != null) {
                IconButton(onClick = { latestScan?.let { viewModel.dismissScan(it.id) } }) {
                    Icon(Icons.Default.DeleteSweep, "Remove latest scan")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(8.dp))
            if (!settings.serverMode && review.isNotEmpty()) {
                FilledTonalButton(onClick = onReview) {
                    Text("Review ${review.size}", color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
                }
            }
            FilledTonalButton(onClick = onInventory, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Default.Inventory2, null, Modifier.size(18.dp))
                Text(if (settings.serverMode) "Inventory · scan progress" else "Inventory · ${entries.sumOf { it.quantity }}",
                    Modifier.padding(start = 4.dp), maxLines = 1)
            }
        }
        if (waiting > 1) {
            // More than the card in hand: no network, or a queue (captures are kept until they are settled)
            Text("$waiting captures waiting " + (if (settings.serverMode) "for the server" else "to be identified") +
                    " - they are kept, also if the app is closed",
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        // Newest card first: show it when it arrives (a LazyColumn otherwise keeps the item that
        // was on top in view, and new cards pile up hidden above it)
        val listState = rememberLazyListState()
        LaunchedEffect(latestScan?.id) { if (latestScan != null) listState.animateScrollToItem(0) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(listOfNotNull(latestScan), key = { it.id }) {
                ScanCard(viewModel, it, compact = true)
            }
        }
    }
    }
}

@Composable
private fun CameraPreview(viewModel: ScannerViewModel, torch: Boolean, rotation: Int) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            // TextureView: a SurfaceView ignores the rotation applied to the view
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            // Everything in 4:3 (the sensor's own shape): preview, analysis and stills show the
            // same view, so outlines found in analysis frames also fit the still
            val fourByThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourByThree).build())
                .build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(fourByThree)
                    .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(viewModel.analyzerExecutor, viewModel.analyzer) }
            val still = ImageCapture.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(fourByThree)
                    .setResolutionStrategy(ResolutionStrategy(android.util.Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            viewModel.imageCapture = still
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, still)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            viewModel.imageCapture = null
            if (providerFuture.isDone) providerFuture.get().unbindAll()
        }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    // The preview is upright for the phone (3:4); turned like the analysis frames, so the outline
    // (in frame coordinates) lies on the card
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideways = rotation % 180 != 0
        AndroidView({ previewView }, Modifier.align(Alignment.Center)
            .requiredSize(if (sideways) maxHeight else maxWidth, if (sideways) maxWidth else maxHeight)
            .rotate(rotation.toFloat()))
    }
}

private fun statusColor(status: CardTracker.Status?) = when (status) {
    CardTracker.Status.READY -> Color(0xFF00E676)
    CardTracker.Status.STABILIZING -> Color(0xFFFFA500)
    CardTracker.Status.CAPTURED -> Color(0xFF64C8FF)
    else -> Color.Gray
}

@Composable
private fun OutlineOverlay(state: CardTracker.State?) {
    Canvas(Modifier.fillMaxSize()) {
        if (state == null) return@Canvas
        val sx = size.width / state.frameWidth
        val sy = size.height / state.frameHeight
        // Fixed area: the area is the photo; the outline (only for the foil check) isn't drawn
        state.area?.let { (x1, y1, x2, y2) ->
            drawRect(statusColor(state.status), Offset(x1 * sx, y1 * sy), Size((x2 - x1) * sx, (y2 - y1) * sy),
                style = Stroke(width = 3.dp.toPx()))
            return@Canvas
        }
        val corners = state.corners ?: return@Canvas
        val path = Path().apply {
            moveTo(corners[0].x.toFloat() * sx, corners[0].y.toFloat() * sy)
            corners.drop(1).forEach { lineTo(it.x.toFloat() * sx, it.y.toFloat() * sy) }
            close()
        }
        drawPath(path, statusColor(state.status), style = Stroke(width = 3.dp.toPx()))
        drawCircle(statusColor(state.status), 4.dp.toPx(), Offset(corners[0].x.toFloat() * sx, corners[0].y.toFloat() * sy))
    }
}

@Composable
private fun StatusChip(state: CardTracker.State?, auto: Boolean, modifier: Modifier) {
    val text = (if (state?.area != null) "Area · " else "") + when (state?.status) {
        null, CardTracker.Status.NO_CARD -> "No card"
        CardTracker.Status.STABILIZING -> "Stabilizing ${state.stableFrames}/${state.requiredFrames}" +
            if (!state.inFocus) " · blurry" else ""
        CardTracker.Status.READY -> if (auto) "Ready" else "Ready - tap Capture"
        CardTracker.Status.CAPTURED -> "Captured - drop the next card"
    }
    Text(
        text,
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = if (state == null || state.status == CardTracker.Status.NO_CARD) Color.White else statusColor(state.status),
        style = MaterialTheme.typography.labelLarge,
    )
}

/**
 * Drag a rectangle around where the cards land, then drag its corners to adjust them; a loupe
 * above the finger shows the corner being placed. Starts from `initial` (the current area, as
 * fractions of the camera view) and reports the area as fractions on Done.
 */
@Composable
private fun AreaDrawer(initial: List<Double>?, onDrawn: (List<Double>) -> Unit, onUseDetected: () -> Unit, onCancel: () -> Unit) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // Two opposite corners of the rectangle, in px; `moving` is the one under the finger
    var anchor by remember { mutableStateOf<Offset?>(null) }
    var moving by remember { mutableStateOf<Offset?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val loupeLift = with(density) { 90.dp.toPx() }
    val grab = with(density) { 48.dp.toPx() }

    LaunchedEffect(viewSize) {
        if (viewSize != IntSize.Zero && anchor == null && initial != null) {
            anchor = Offset((initial[0] * viewSize.width).toFloat(), (initial[1] * viewSize.height).toFloat())
            moving = Offset((initial[2] * viewSize.width).toFloat(), (initial[3] * viewSize.height).toFloat())
        }
    }
    fun corners(): List<Offset>? {
        val a = anchor ?: return null; val b = moving ?: return null
        val left = minOf(a.x, b.x); val right = maxOf(a.x, b.x); val top = minOf(a.y, b.y); val bottom = maxOf(a.y, b.y)
        return listOf(Offset(left, top), Offset(right, top), Offset(right, bottom), Offset(left, bottom))
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        Canvas(Modifier.fillMaxSize()
            .magnifier(
                sourceCenter = { moving?.takeIf { dragging } ?: Offset.Unspecified },
                magnifierCenter = { moving?.takeIf { dragging }?.let { it - Offset(0f, loupeLift) } ?: Offset.Unspecified },
                zoom = 3f,
                size = DpSize(120.dp, 120.dp),
                cornerRadius = 60.dp,
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { touch ->
                        val current = corners()
                        val index = current?.indices?.minByOrNull { (current[it] - touch).getDistance() }
                        if (current != null && index != null && (current[index] - touch).getDistance() < grab) {
                            // Move that corner; the opposite one stays
                            anchor = current[(index + 2) % 4]
                            moving = current[index]
                        } else {
                            anchor = touch
                            moving = touch
                        }
                        dragging = true
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        moving = moving?.let {
                            Offset((it.x + amount.x).coerceIn(0f, size.width.toFloat()), (it.y + amount.y).coerceIn(0f, size.height.toFloat()))
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            }) {
            drawRect(Color.Black.copy(alpha = 0.25f))
            val current = corners() ?: return@Canvas
            val topLeft = current[0]
            val rectSize = Size(current[2].x - current[0].x, current[2].y - current[0].y)
            drawRect(Color.White.copy(alpha = 0.12f), topLeft, rectSize)
            drawRect(Color(0xFF00E676), topLeft, rectSize, style = Stroke(width = 2.dp.toPx()))
            // Corner handles to grab
            current.forEach { drawCircle(Color(0xFF00E676), 7.dp.toPx(), it, style = Stroke(width = 2.dp.toPx())) }
            // Crosshair on the corner under the finger (seen in the loupe)
            moving?.takeIf { dragging }?.let { b ->
                val arm = 10.dp.toPx(); val stroke = 1.dp.toPx()
                drawLine(Color.Red, b - Offset(arm, 0f), b + Offset(arm, 0f), stroke)
                drawLine(Color.Red, b - Offset(0f, arm), b + Offset(0f, arm), stroke)
            }
        }
        // Hint at the bottom, buttons at the top: cards land low in the box, and the buttons
        // must not cover the area's bottom corners
        Text(
            if (anchor == null) "Drag around the spot where the cards land" else "Drag the corners to adjust",
            Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        // Compact buttons: the camera view is only ~300 dp wide
        val compact = PaddingValues(horizontal = 12.dp)
        val dark = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))
        Row(Modifier.align(Alignment.TopCenter).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onUseDetected, colors = dark, contentPadding = compact) { Text("Detected", color = Color.White, maxLines = 1) }
            OutlinedButton(onClick = onCancel, colors = dark, contentPadding = compact) { Text("Cancel", color = Color.White, maxLines = 1) }
            val current = corners()
            val big = current != null && current[2].x - current[0].x > 20 && current[2].y - current[0].y > 20
            Button(onClick = {
                val c = corners()!!
                val w = viewSize.width.toDouble(); val h = viewSize.height.toDouble()
                onDrawn(listOf(c[0].x / w, c[0].y / h, c[2].x / w, c[2].y / h))
            }, enabled = big, contentPadding = compact) { Text("Done", maxLines = 1) }
        }
    }
}
