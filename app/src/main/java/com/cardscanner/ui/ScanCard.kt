package com.cardscanner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cardscanner.ScanResult
import com.cardscanner.ScannerViewModel
import com.cardscanner.server.ServerOutcome

/** One image and one result per row, shared by the camera's latest capture and scan history. */
@Composable
internal fun ScanCard(viewModel: ScannerViewModel, scan: ScanResult, compact: Boolean = false) {
    val context = LocalContext.current
    val printing = scan.printing
    val server = scan.server
    val title = server?.card?.name ?: printing?.name ?: scan.reading?.name?.takeIf { it.isNotBlank() }
        ?: server?.readName?.takeIf { it.isNotBlank() } ?: "Capture #${scan.id}"
    FeedCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val image = Modifier.width(if (compact) 40.dp else 50.dp).height(if (compact) 56.dp else 70.dp).clip(RoundedCornerShape(8.dp))
            if (printing?.imageUrl != null) AsyncImage(printing.imageUrl, title, image)
            else Image(scan.thumbnail.asImageBitmap(), "Captured card", image)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = printing != null) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(printing!!.scryfallUrl)))
                    })
                val details = printing?.let { "${it.setCode} #${it.collectorNumber} · ${scan.finish.label}" }
                    ?: server?.card?.let { "${it.set} #${it.number} · ${it.finish}" }
                details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val status = when {
                    scan.deleting -> "Removing from server…"
                    scan.status == ScanResult.Status.IDENTIFYING -> scan.note ?: "Identifying…"
                    scan.undone -> "Taken back"
                    // Who read it and, for a review, why: how the server's reading (OCR, picture + collector line, AI) is told apart
                    server?.status == ServerOutcome.Status.ADDED || server?.status == ServerOutcome.Status.REVIEW ||
                        server?.status == ServerOutcome.Status.CARD_BACK -> server.statusLine ?: "Sent to the server"
                    // Standalone: who read it - the picture alone (no AI asked) or the AI - in client mode's words, so the two can be told apart in a test
                    scan.inventoryId != null -> "Added to inventory" + readBy(scan.reader)
                    scan.pendingAdd -> "Waiting for Add" + readBy(scan.reader)
                    scan.reviewId != null -> "Sent to review" + readBy(scan.reader)
                    scan.status == ScanResult.Status.FAILED -> scan.error ?: "Could not identify"
                    else -> scan.error ?: "Check this printing"
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (scan.status == ScanResult.Status.IDENTIFYING) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = if (scan.status == ScanResult.Status.FAILED && scan.reviewId == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!compact) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        server != null ->
                            // "from phone": the server cannot follow this capture, its card stays there
                            TextButton(onClick = { viewModel.dismissScan(scan.id) }, enabled = !scan.deleting) {
                                Text(if (scan.serverCannotFollow) "Remove from phone" else "Remove")
                            }
                        scan.inventoryId != null -> TextButton(onClick = { viewModel.undoScan(scan.id) }) { Text("Undo") }
                        printing != null && scan.status == ScanResult.Status.DONE && (scan.reviewId == null || scan.pendingAdd) ->
                            FilledTonalButton(onClick = { viewModel.addScan(scan.id) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                Text("Add as ${scan.finish.label.lowercase()}")
                            }
                    }
                }
            }
        }
    }
}

/** " · read by the picture" / " · read by AI (model)" for a standalone row, or nothing (not read, or read before the reader was kept) */
private fun readBy(reader: String?): String = when (reader) {
    null, "" -> ""
    com.cardscanner.CaptureProcessor.PICTURE_READER -> " · read by the picture"
    com.cardscanner.CaptureProcessor.STRIP_READER -> " · read by the picture + collector line"
    else -> " · read by AI ($reader)"
}
