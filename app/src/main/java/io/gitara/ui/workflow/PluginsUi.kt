package io.gitara.ui.workflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.gitara.plugins.PluginCommand
import io.gitara.plugins.PluginCommandRef
import io.gitara.plugins.PluginInfo
import io.gitara.plugins.PluginManifest
import io.gitara.plugins.PluginStore
import io.gitara.workflow.WorkflowDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class PendingInstall(val label: String, val work: () -> PluginManifest)

@Composable
internal fun PluginsCard(count: Int, onManage: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onManage,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outline)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Plugins",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (count == 0) "None installed. Plugins add actions, templates and commands." else "$count installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onManage) { Text("Manage") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginsSheet(
    store: PluginStore,
    repoDir: File,
    onRunCommand: (WorkflowDefinition) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tick by remember { mutableIntStateOf(0) }
    val plugins by produceState(emptyList<PluginInfo>(), tick) {
        value = withContext(Dispatchers.IO) { store.list() }
    }
    val repoIsPlugin = remember(repoDir) { store.hasManifest(repoDir) }

    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingInstall?>(null) }
    var uninstallId by remember { mutableStateOf<String?>(null) }

    fun runInstall(install: PendingInstall) {
        scope.launch {
            busy = "Installing…"
            val result = withContext(Dispatchers.IO) { runCatching { install.work() } }
            busy = null
            result.onSuccess {
                tick++
                url = ""
                token = ""
                snackbar.showSnackbar("Installed ${it.name} ${it.version}")
            }.onFailure {
                snackbar.showSnackbar(it.message ?: "The installation failed")
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Plugins", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "A plugin can run any command in the Gitara Box with access to the open repository. " +
                    "Secrets are never exposed to plugins unless you pass them in with:. Install only plugins you trust.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (plugins.isEmpty()) {
                Text("No plugins installed.", style = MaterialTheme.typography.bodyMedium)
            } else {
                plugins.forEach { info ->
                    PluginRow(
                        info = info,
                        onToggle = { enabled ->
                            scope.launch {
                                withContext(Dispatchers.IO) { store.setEnabled(info.id, enabled) }
                                tick++
                            }
                        },
                        onUninstall = { uninstallId = info.id },
                        onRun = { command ->
                            val manifest = info.manifest
                            if (manifest != null) {
                                onRunCommand(store.commandDefinition(PluginCommandRef(info.id, manifest.name, command)))
                            }
                        }
                    )
                }
            }

            HorizontalDivider()
            Text("Install from a Git URL", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Repository URL") },
                placeholder = { Text("https://github.com/owner/plugin.git") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username (private repositories)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Access token (private repositories)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val gitUrl = url
                    val gitUser = user
                    val gitToken = token
                    pending = PendingInstall(gitUrl.trim()) {
                        store.installFromGit(gitUrl, gitUser, gitToken) { stage -> busy = stage }
                    }
                },
                enabled = url.isNotBlank() && busy == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Install") }

            if (repoIsPlugin) {
                OutlinedButton(
                    onClick = {
                        pending = PendingInstall("This repository (${repoDir.name})") {
                            store.installFrom(repoDir, "local:" + repoDir.name)
                        }
                    },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Install this repository as a plugin") }
            }

            busy?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SnackbarHost(hostState = snackbar)
        }
    }

    pending?.let { install ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Install plugin?") },
            text = {
                Text(
                    install.label + "\n\nThis plugin will be able to run commands in the Gitara Box. " +
                        "Install it only if you trust its author."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    runInstall(install)
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        )
    }

    uninstallId?.let { id ->
        AlertDialog(
            onDismissRequest = { uninstallId = null },
            title = { Text("Uninstall plugin?") },
            text = { Text("$id and everything it installed in Gitara will be removed. Workflows that use it will fail.") },
            confirmButton = {
                TextButton(onClick = {
                    uninstallId = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.uninstall(id) }
                        tick++
                        snackbar.showSnackbar("Uninstalled $id")
                    }
                }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { uninstallId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PluginRow(
    info: PluginInfo,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onRun: (PluginCommand) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val manifest = info.manifest
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (info.error != null) scheme.error.copy(alpha = 0.6f) else scheme.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (manifest?.name ?: info.id) + if (manifest != null) "  " + manifest.version else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "plugin:${info.id}/",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant
                    )
                }
                if (manifest != null) Switch(checked = info.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall ${info.id}", tint = scheme.onSurfaceVariant)
                }
            }
            if (manifest != null) {
                if (manifest.author.isNotBlank()) {
                    Text("by ${manifest.author}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (manifest.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(manifest.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (info.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(info.error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            if (info.source.isNotBlank()) {
                Text(info.source, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (manifest != null && manifest.commands.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                manifest.commands.forEach { command ->
                    OutlinedButton(
                        onClick = { onRun(command) },
                        enabled = info.enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                            Text("Run: ${command.name}")
                            if (command.description.isNotBlank()) {
                                Text(
                                    command.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}