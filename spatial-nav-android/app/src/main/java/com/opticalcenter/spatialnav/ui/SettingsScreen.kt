package com.opticalcenter.spatialnav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opticalcenter.spatialnav.settings.ApiService
import com.opticalcenter.spatialnav.settings.KeySource
import com.opticalcenter.spatialnav.settings.KeyStatus
import com.opticalcenter.spatialnav.settings.Verification
import com.opticalcenter.spatialnav.spatial.SpatialEngine
import kotlin.math.roundToInt

private val Good = Palette.Good
private val Bad = Palette.Bad
private val Warn = Palette.Warn

data class SettingsActions(
    val onBack: () -> Unit,
    val onSave: (ApiService, String) -> Unit,
    val onTest: (ApiService) -> Unit,
    val onRemove: (ApiService) -> Unit,
    val onSimulateHeading: (Boolean) -> Unit,
    val onManualHeading: (Double) -> Unit,
)

@Composable
fun SettingsScreen(
    keys: Map<ApiService, KeyStatus>,
    simulateHeading: Boolean,
    heading: Double,
    assistant: AssistantSettingsState,
    assistantActions: AssistantSettingsActions,
    actions: SettingsActions,
) {
    // Surface consumes touches, so nothing reaches the map screen underneath.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to map")
                }
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
            Text("Assistant", style = MaterialTheme.typography.titleMedium, color = Palette.Blue)
            AssistantSection(assistant, assistantActions)
            Text("API keys", style = MaterialTheme.typography.titleMedium, color = Palette.Blue)
            Text(
                "Keys are stored only on this device, encrypted with a key held in the Android Keystore, and are " +
                    "sent only to the service they belong to. A key saved here replaces one built in from local.properties.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (service in ApiService.entries) {
                val status = keys[service] ?: KeyStatus(service, KeySource.NONE, null, Verification.Unknown)
                KeyCard(status, actions)
            }
            Text("Developer", style = MaterialTheme.typography.titleMedium, color = Palette.Blue)
            ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = Palette.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Simulate heading", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Override the compass, e.g. on an emulator or to test left/right answers.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = simulateHeading, onCheckedChange = actions.onSimulateHeading)
                    }
                    if (simulateHeading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = heading.toFloat(),
                                onValueChange = { actions.onManualHeading(it.toDouble()) },
                                valueRange = 0f..359f,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${heading.roundToInt() % 360}° ${SpatialEngine.compassName(heading).uppercase()}",
                                Modifier.widthIn(min = 64.dp),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
            Text(
                "These keys are for your own testing. Anyone who controls an unlocked, rooted device could extract " +
                    "them, so for an app you distribute, route requests through your own server instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun KeyCard(status: KeyStatus, actions: SettingsActions) {
    val service = status.service
    val uriHandler = LocalUriHandler.current
    var entry by rememberSaveable(service) { mutableStateOf("") }
    var reveal by rememberSaveable(service) { mutableStateOf(false) }
    var submitted by rememberSaveable(service) { mutableStateOf(false) }
    var confirmRemove by rememberSaveable(service) { mutableStateOf(false) }
    val checking = status.verification == Verification.Checking

    // Clear the field once a submitted key has been accepted (saved).
    LaunchedEffect(status.verification) {
        if (submitted && (status.verification == Verification.Valid || status.verification is Verification.Unreachable)) {
            entry = ""
            reveal = false
            submitted = false
        } else if (status.verification is Verification.Invalid) {
            submitted = false
        }
    }

    ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = Palette.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(service.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(service.purpose, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(status)
            }

            if (status.preview != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status.preview, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        when (status.source) {
                            KeySource.APP -> "saved in app"
                            KeySource.BUILD -> "from local.properties"
                            KeySource.NONE -> ""
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (status.preview == null) "Paste your ${service.title} key" else "Replace key") },
                singleLine = true,
                enabled = !checking,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (entry.isNotBlank()) {
                        submitted = true
                        actions.onSave(service, entry)
                    }
                }),
                trailingIcon = {
                    if (entry.isNotEmpty()) {
                        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                    }
                },
            )

            when (val v = status.verification) {
                is Verification.Invalid -> Text(v.message, color = Bad, fontSize = 13.sp)
                is Verification.Unreachable -> Text(v.message, color = Warn, fontSize = 13.sp)
                else -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        submitted = true
                        actions.onSave(service, entry)
                    },
                    enabled = entry.isNotBlank() && !checking,
                ) { Text("Save & test") }
                if (status.preview != null) {
                    OutlinedButton(onClick = { actions.onTest(service) }, enabled = !checking) { Text("Test") }
                }
                if (status.source == KeySource.APP) {
                    TextButton(onClick = { confirmRemove = true }, enabled = !checking) { Text("Remove", color = Bad) }
                }
            }

            TextButton(onClick = { uriHandler.openUri(service.signupUrl) }) {
                Text("Get a ${service.title} key at ${service.signupUrl.removePrefix("https://")}")
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${service.title} key?") },
            text = {
                Text(
                    if (status.source == KeySource.APP) {
                        "The key will be deleted from this device. " +
                            "The app falls back to the key from local.properties if there is one, otherwise to demo mode."
                    } else {
                        "The key will be deleted from this device."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    actions.onRemove(service)
                }) { Text("Remove", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusChip(status: KeyStatus) {
    val (label, color) = when {
        status.verification == Verification.Checking -> "Checking…" to Warn
        status.preview == null -> "Not set" to Palette.OnSurfaceMuted
        status.verification == Verification.Valid -> "Working" to Good
        status.verification is Verification.Invalid -> "Rejected" to Bad
        status.verification is Verification.Unreachable -> "Not verified" to Warn
        else -> "Saved" to Palette.OnSurfaceMuted
    }
    Row(
        Modifier
            .widthIn(min = 72.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "Status: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.verification == Verification.Checking) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = color)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
