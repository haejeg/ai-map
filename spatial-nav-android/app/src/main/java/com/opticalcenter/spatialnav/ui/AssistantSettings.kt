package com.opticalcenter.spatialnav.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opticalcenter.spatialnav.local.LocalModelSpec
import com.opticalcenter.spatialnav.local.ModelStatus
import com.opticalcenter.spatialnav.settings.AssistantMode
import java.util.Locale

/** What the assistant section needs to draw. */
data class AssistantSettingsState(
    val mode: AssistantMode,
    val selectedModelId: String,
    val modelStatus: Map<String, ModelStatus>,
    val activeLabel: String,
    val loading: Boolean,
    val hasClaudeKey: Boolean,
)

data class AssistantSettingsActions(
    val onSelectMode: (AssistantMode) -> Unit,
    val onSelectModel: (LocalModelSpec) -> Unit,
    val onDownload: (LocalModelSpec) -> Unit,
    val onPause: () -> Unit,
    val onDelete: (LocalModelSpec) -> Unit,
    val onImport: (LocalModelSpec, Uri) -> Unit,
)

private fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1e9)

@Composable
fun AssistantSection(state: AssistantSettingsState, actions: AssistantSettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Palette.Surface) {
            Column(Modifier.padding(vertical = 6.dp)) {
                for (mode in AssistantMode.entries) {
                    val unavailable = mode == AssistantMode.CLAUDE && !state.hasClaudeKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = state.mode == mode, role = Role.RadioButton) { actions.onSelectMode(mode) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.mode == mode, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mode.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (unavailable) "${mode.description} Add a Claude key below to use it." else mode.description,
                                fontSize = 13.sp,
                                color = Palette.OnSurfaceMuted,
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.Blue)
                Spacer(Modifier.width(8.dp))
                Text("Loading the model into memory…", fontSize = 13.sp, color = Palette.OnSurfaceMuted)
            } else {
                Text("Answering now: ${state.activeLabel}", fontSize = 13.sp, color = Palette.OnSurfaceMuted)
            }
        }

        if (state.mode == AssistantMode.ON_DEVICE) {
            for (spec in LocalModelSpec.CATALOG) {
                ModelCard(
                    spec = spec,
                    status = state.modelStatus[spec.id] ?: ModelStatus.NotDownloaded,
                    selected = spec.id == state.selectedModelId,
                    anotherBusy = state.modelStatus.any { (id, s) -> id != spec.id && s is ModelStatus.Downloading },
                    actions = actions,
                )
            }
            Text(
                "Models come from Google's LiteRT Community on Hugging Face (Apache-2.0). Download over Wi-Fi; " +
                    "everything runs on the phone afterwards, so questions work offline and never leave the device.",
                fontSize = 12.sp,
                color = Palette.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun ModelCard(
    spec: LocalModelSpec,
    status: ModelStatus,
    selected: Boolean,
    anotherBusy: Boolean,
    actions: AssistantSettingsActions,
) {
    var confirmDelete by rememberSaveable(spec.id) { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { actions.onImport(spec, it) }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Palette.Surface,
        border = if (selected) BorderStroke(1.5.dp, Palette.Blue) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(spec.summary, fontSize = 13.sp, color = Palette.OnSurfaceMuted)
                }
                if (selected && status is ModelStatus.Ready) {
                    Text("In use", color = Palette.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            when (status) {
                ModelStatus.NotDownloaded -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.onDownload(spec) }, enabled = !anotherBusy) {
                        Text("Download ${gb(spec.approxBytes)}")
                    }
                    TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !anotherBusy) {
                        Text("Import file")
                    }
                }
                is ModelStatus.Downloading -> {
                    val fraction = status.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth(), color = Palette.Blue)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Blue)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${gb(status.bytes)} of ${gb(status.totalBytes ?: spec.approxBytes)}",
                            fontSize = 13.sp,
                            color = Palette.OnSurfaceMuted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = actions.onPause) { Text("Pause") }
                    }
                }
                is ModelStatus.Paused -> {
                    LinearProgressIndicator(
                        progress = { (status.bytes.toFloat() / spec.approxBytes).coerceIn(0f, 1f) },
                        Modifier.fillMaxWidth(),
                        color = Palette.OnSurfaceMuted,
                    )
                    status.reason?.let { Text(it, fontSize = 13.sp, color = Palette.Warn) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.onDownload(spec) }, enabled = !anotherBusy) {
                            Text("Resume (${gb(status.bytes)} done)")
                        }
                        TextButton(onClick = { confirmDelete = true }) { Text("Discard", color = Palette.Bad) }
                    }
                }
                is ModelStatus.Ready -> {
                    Text("Downloaded · ${gb(status.bytes)}", fontSize = 13.sp, color = Palette.Good)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!selected) {
                            Button(onClick = { actions.onSelectModel(spec) }) { Text("Use this model") }
                        }
                        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete", color = Palette.Bad) }
                    }
                }
            }
            if (!selected && status !is ModelStatus.Ready && status !is ModelStatus.Downloading) {
                TextButton(onClick = { actions.onSelectModel(spec) }) { Text("Make this the model to use") }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${spec.name}?") },
            text = { Text("This frees up about ${gb(spec.approxBytes)}. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onDelete(spec)
                }) { Text("Delete", color = Palette.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
