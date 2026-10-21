package com.cardscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardscanner.ScannerViewModel
import com.cardscanner.ai.CardIdentifier
import com.cardscanner.ai.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, saved as they are changed. The scanner server section comes first: with client mode
 * on, the sections that only matter when the phone reads the cards itself (vision AI, offline
 * card data, foil check, automatic adds) are hidden - the server decides those.
 */
@Composable
fun SettingsScreen(viewModel: ScannerViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsError by viewModel.settingsError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var localModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var localStatus by remember { mutableStateOf<String?>(null) }
    val provider = settings.provider

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Settings", "Make the scanner yours", onBack)

        settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        FeedCard { ServerSection(viewModel) }

        if (!settings.serverMode) {
        FeedCard { ReadingSection(viewModel) }
        if (settings.aiEnabled) FeedCard {
        Text("Vision AI", style = MaterialTheme.typography.titleMedium)
        Dropdown("Provider", provider.label, Provider.entries.map { it.label }) {
            viewModel.updateSettings(settings.copy(provider = Provider.entries[it]))
        }

        var listedModels by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
        val modelChoices = when {
            provider == Provider.LOCAL && localModels.isNotEmpty() -> localModels
            listedModels.isNotEmpty() -> listedModels
            else -> provider.models
        }
        Dropdown("Model", settings.model(), modelChoices) {
            viewModel.updateSettings(settings.copy(models = settings.models + (provider to modelChoices[it])))
        }
        OutlinedTextField(
            value = settings.models[provider].orEmpty(),
            onValueChange = { viewModel.updateSettings(settings.copy(models = settings.models + (provider to it.trim()))) },
            label = { Text("Other model (optional)") },
            placeholder = { Text(provider.models.first()) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (provider.needsKey) {
            OutlinedTextField(
                value = settings.apiKey(),
                onValueChange = { viewModel.updateSettings(settings.copy(apiKeys = settings.apiKeys + (provider to it.trim()))) },
                label = { Text("${provider.label} API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (provider == Provider.OPENROUTER) {
                var listStatus by remember { mutableStateOf<String?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        listStatus = "Loading…"
                        scope.launch {
                            listStatus = try {
                                listedModels = withContext(Dispatchers.IO) { CardIdentifier.openRouterModels(CardIdentifier.httpClient()) }
                                "${listedModels.size} models read images (free ones first)"
                            } catch (e: Exception) {
                                "Could not load the list: ${e.message}"
                            }
                        }
                    }) { Text("List models") }
                    listStatus?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
                }
                Text("Models ending in :free cost nothing: 20 requests a minute, 50 a day (1,000 a day once you have bought \$10 of credits). A card takes 2 requests with the foil check.",
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            OutlinedTextField(
                value = settings.localUrl,
                onValueChange = { viewModel.updateSettings(settings.copy(localUrl = it.trim())) },
                label = { Text("Server address") },
                placeholder = { Text("http://192.168.1.10:11434") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    localStatus = "Connecting…"
                    scope.launch {
                        localStatus = try {
                            localModels = withContext(Dispatchers.IO) { CardIdentifier.localModels(settings.localUrl, CardIdentifier.httpClient()) }
                            "Connected: ${localModels.size} models"
                        } catch (e: Exception) {
                            "Not reachable: ${e.message}"
                        }
                    }
                }) { Text("Test / list models") }
                localStatus?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }

        }
        FeedCard { CardDataSection(viewModel) }
        }  // standalone only: in client mode the server reads the cards and looks them up
        FeedCard {
        Text("Scanning", style = MaterialTheme.typography.titleMedium)
        if (!settings.serverMode) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Foil check (★/• marker)")
                Text(if (settings.aiEnabled) "A second, small AI request per card (only for a printing that exists in both finishes)"
                    else "Needs the vision AI, which is off: a card printed in both finishes is added non-foil unless you change it",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.detectFoil, { viewModel.updateSettings(settings.copy(detectFoil = it)) }, enabled = settings.aiEnabled)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Add confirmed cards automatically")
                Text("Exact matches go straight to inventory, with Undo available. Other cards wait for review. Turn this off to confirm every Add.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.autoAdd, { viewModel.updateSettings(settings.copy(autoAdd = it)) })
        }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Find cards with the corner network")
                Text("Asked before the outline detector: finds cards the outline detector loses on a tall pile of sleeved cards. Turn this off to use the outline detector alone.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.cornerNet, { viewModel.updateSettings(settings.copy(cornerNet = it)) })
        }
        NumberField("Minimum sharpness", settings.minSharpness,
            "Cards below it are not auto-captured. Lower it if cards never become Ready.") {
            viewModel.updateSettings(settings.copy(minSharpness = it))
        }
        NumberField("Still frames before capture", settings.stableFrames, "Consecutive still, sharp frames.") {
            viewModel.updateSettings(settings.copy(stableFrames = it.coerceIn(1, 60)))
        }
        }
        FeedCard {
            Text("Sounds", style = MaterialTheme.typography.titleMedium)
            SoundToggle("Capture", "A short shutter click", settings.captureSound) {
                viewModel.updateSettings(settings.copy(captureSound = it))
            }
            SoundToggle("Added to inventory", "A two-note chime", settings.addedSound) {
                viewModel.updateSettings(settings.copy(addedSound = it))
            }
            SoundToggle("Sent to review", "Three short beeps", settings.reviewSound) {
                viewModel.updateSettings(settings.copy(reviewSound = it))
            }
        }
        // Every change is saved as it is made; Done just closes the page
        Button(onClick = onBack, Modifier.fillMaxWidth()) { Text("Done") }
    }
}

/**
 * Standalone, or client of a scanner server: the phone finds and captures the cards either way;
 * in client mode the server reads them, finds the printing and keeps the collection
 */
@Composable
private fun ServerSection(viewModel: ScannerViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    Text("Scanner server", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Send cards to a scanner server")
            Text("Let a server identify cards and manage your collection. Captures wait safely on this phone when offline. Your local inventory stays separate.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(settings.serverMode, { viewModel.updateSettings(settings.copy(serverMode = it)) })
    }
    if (!settings.serverMode) return
    OutlinedTextField(
        value = settings.serverUrl,
        onValueChange = { viewModel.updateSettings(settings.copy(serverUrl = it.trim())) },
        label = { Text("Server address") },
        placeholder = { Text("http://192.168.1.20:5000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = settings.stationName,
        onValueChange = { viewModel.updateSettings(settings.copy(stationName = it.take(60))) },
        label = { Text("This phone's name on the server") },
        supportingText = { Text("Station id: ${settings.stationId}") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = settings.stationToken,
        onValueChange = { viewModel.updateSettings(settings.copy(stationToken = it.trim())) },
        label = { Text("Station token (if the server has one)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Send camera frames")
            Text("Also send the whole camera picture and the outline found in it with each capture - training data for a better detector. Uploads are larger.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(settings.sendFrames, { viewModel.updateSettings(settings.copy(sendFrames = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            status = "Connecting…"
            scope.launch { status = viewModel.testServer() }
        }) { Text("Test connection") }
        status?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(index); expanded = false })
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, help: String, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new.filter { it.isDigit() }.take(5); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        supportingText = { Text(help) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Offline card data: download / update / delete Scryfall's card list */
/**
 * Standalone: how a card is read - by its picture first (the picture index, downloaded from a scanner server), and whether the vision AI is asked for what
 * the picture does not settle (off: those cards go to review). The server's own reading with OCR off (docs/plans/ANDROID_PICTURE_STANDALONE_PLAN.md there)
 */
@Composable
private fun ReadingSection(viewModel: ScannerViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val index by viewModel.pictureIndexState.collectAsStateWithLifecycle()
    val cardData by viewModel.cardDataState.collectAsStateWithLifecycle()
    // the card data changed (downloaded, updated, deleted): the index's coverage of it is counted again
    androidx.compose.runtime.LaunchedEffect(cardData.updatedAt, cardData.printings) { viewModel.pictureIndexCardDataChanged() }
    Text("Reading cards", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Read by the picture first")
            Text("The card's picture is matched against every printing; a clear match is added without the AI, in a few hundredths of a second, offline. Needs the picture index and the card data below.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(settings.pictureFirst, { viewModel.updateSettings(settings.copy(pictureFirst = it)) })
    }
    if (settings.pictureFirst) Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Read the collector line (OCR)")
            Text("Reads the set, number and ★/• at the bottom left, on the phone: confirms the printing and gives the foil. About 0.3 s a card.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(settings.readCollectorLine, { viewModel.updateSettings(settings.copy(readCollectorLine = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ask the vision AI")
            Text(if (settings.aiEnabled) "For what the picture does not settle (and the foil check)."
                else "Off: what the picture does not settle waits for review - no AI requests, no key needed.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(settings.aiEnabled, { viewModel.updateSettings(settings.copy(aiEnabled = it)) })
    }
    if (!settings.pictureFirst) return
    Text("Picture index", style = MaterialTheme.typography.titleSmall)
    Text(
        when {
            index.count != null -> "%,d printings, built %s on the server".format(index.count, index.built?.take(10).orEmpty()) + when {
                index.missing == null -> " - download the card data to use it"
                index.missing == 0 -> " - covers the card data"
                else -> " - lacks %,d printings of the card data: the picture adds nothing until both are of the same day (download the index again after the server's, or the card data again)".format(index.missing)
            }
            else -> "Not downloaded (${index.why ?: "no index"}). It comes from a scanner server (Settings > Card reading > Picture there), about 22 MB."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = settings.serverUrl,
        onValueChange = { viewModel.updateSettings(settings.copy(serverUrl = it.trim())) },
        label = { Text("Scanner server to download it from") },
        placeholder = { Text("http://192.168.1.20:5000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    index.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = viewModel::downloadPictureIndex, enabled = !index.busy && settings.serverUrl.isNotBlank()) {
            Text(if (index.count == null) "Download" else "Update")
        }
        if (index.count != null) OutlinedButton(onClick = viewModel::deletePictureIndex, enabled = !index.busy) { Text("Delete") }
        if (index.busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun CardDataSection(viewModel: ScannerViewModel) {
    val state by viewModel.cardDataState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.printings) { if (state.printings != null) viewModel.checkCardDataUpdate() }
    Text("Card data (offline)", style = MaterialTheme.typography.titleMedium)
    Text(
        if (state.printings != null) "%,d printings from Scryfall, %s".format(state.printings, state.updatedAt?.take(10).orEmpty()) +
            (if (state.updateAvailable == true) " - newer data available" else "")
        else "Not downloaded: cards are looked up on Scryfall's website. With the data (~80 MB download) lookups work offline and faster; card images and new cards still need the internet.",
        style = MaterialTheme.typography.bodySmall,
    )
    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = viewModel::downloadCardData, enabled = !state.busy) {
            Text(if (state.printings == null) "Download" else "Update")
        }
        if (state.printings != null) OutlinedButton(onClick = viewModel::deleteCardData, enabled = !state.busy) { Text("Delete") }
        if (state.busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    if (state.busy) Text("Keep the app open until it is done.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SoundToggle(title: String, description: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(enabled, onCheckedChange = null)
    }
}
