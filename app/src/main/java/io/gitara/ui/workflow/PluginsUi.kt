package io.gitara.ui.workflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.gitara.plugins.CatalogPlugin
import io.gitara.plugins.PluginCommandRef
import io.gitara.plugins.PluginFormats
import io.gitara.plugins.PluginInfo
import io.gitara.plugins.PluginMeta
import io.gitara.plugins.PluginRepos
import io.gitara.plugins.PluginRepository
import io.gitara.plugins.PluginStore
import io.gitara.workflow.WorkflowDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class PendingAction(
    val title: String,
    val message: String,
    val confirm: String,
    val destructive: Boolean,
    val run: () -> Unit
)

private class DetailTarget(
    val meta: PluginMeta,
    val plugin: CatalogPlugin?,
    val repoUrl: String,
    val official: Boolean
)

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
                    if (count == 0) "None installed. Browse the Gitara Community Repository." else "$count installed",
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
    val context = LocalContext.current
    val repos = remember { PluginRepos(context.applicationContext) }
    val appVersion = remember { store.gitaraVersion() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var detail by remember { mutableStateOf<DetailTarget?>(null) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newBranch by remember { mutableStateOf("") }

    val repoState by produceState(PluginRepos.State(emptyList(), ""), tick) {
        value = withContext(Dispatchers.IO) { repos.load() }
    }
    val official by produceState(emptySet<String>()) {
        value = withContext(Dispatchers.IO) { repos.officialSet() }
        withContext(Dispatchers.IO) { repos.refreshOfficial() }
        value = withContext(Dispatchers.IO) { repos.officialSet() }
    }
    val installed by produceState(emptyList<PluginInfo>(), tick) {
        value = withContext(Dispatchers.IO) { store.list() }
    }
    val commands by produceState(emptyList<PluginCommandRef>(), tick) {
        value = withContext(Dispatchers.IO) { store.commands() }
    }
    val locals by produceState(emptyList<File>()) {
        value = withContext(Dispatchers.IO) { store.localCandidates(repoDir) }
    }
    val selected = repoState.repositories.firstOrNull { PluginRepos.same(it.url, repoState.selected) }
    val synced by produceState<Boolean?>(null, selected?.url, tick) {
        val repo = selected
        value = if (repo == null) null else withContext(Dispatchers.IO) { repos.isSynced(repo) }
    }
    val catalog by produceState(emptyList<CatalogPlugin>(), selected?.url, tick) {
        val repo = selected
        value = if (repo == null) emptyList() else withContext(Dispatchers.IO) { repos.catalog(repo) }
    }

    fun sync(repo: PluginRepository) {
        scope.launch {
            busy = "Fetching ${repo.name}…"
            val result = withContext(Dispatchers.IO) { runCatching { repos.sync(repo) { stage -> busy = stage } } }
            busy = null
            tick++
            result.onSuccess { snackbar.showSnackbar("${repo.name} is up to date") }
                .onFailure { snackbar.showSnackbar(it.message ?: "Could not fetch the repository") }
        }
    }

    fun install(plugin: CatalogPlugin, repoUrl: String) {
        scope.launch {
            busy = "Installing ${plugin.id}…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.install(plugin, repoUrl, { dep, from -> repos.resolve(dep, from) }) { stage -> busy = stage }
                }
            }
            busy = null
            tick++
            result.onSuccess { ids -> snackbar.showSnackbar("Installed " + ids.joinToString(", ")) }
                .onFailure { snackbar.showSnackbar(it.message ?: "The installation failed") }
        }
    }

    fun installLocal(dir: File) {
        scope.launch {
            busy = "Installing ${dir.name}…"
            val result = withContext(Dispatchers.IO) { runCatching { store.installLocal(dir) { stage -> busy = stage } } }
            busy = null
            tick++
            result.onSuccess { ids -> snackbar.showSnackbar("Installed " + ids.joinToString(", ")) }
                .onFailure { snackbar.showSnackbar(it.message ?: "The installation failed") }
        }
    }

    fun confirmInstall(plugin: CatalogPlugin, repo: PluginRepository, isUpdate: Boolean) {
        val meta = plugin.meta ?: return
        val trust = if (PluginRepos.isOfficial(repo.url, official)) {
            "This repository is official."
        } else {
            "This repository is NOT on the official list."
        }
        pending = PendingAction(
            title = if (isUpdate) "Update plugin?" else "Install plugin?",
            message = "${meta.title} ${meta.version} from ${repo.name}.\n$trust\n\n" +
                "A plugin can run commands in the Gitara Box. Secrets stay hidden from plugins unless you pass them in with:.",
            confirm = if (isUpdate) "Update" else "Install",
            destructive = false
        ) { install(plugin, repo.url) }
    }

    fun addRepository() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repos.add(newName, newUrl, newBranch) } }
            result.onSuccess {
                newName = ""
                newUrl = ""
                newBranch = ""
                tick++
                snackbar.showSnackbar("Repository added")
            }.onFailure { snackbar.showSnackbar(it.message ?: "Could not add the repository") }
        }
    }

    LaunchedEffect(selected?.url, synced) {
        val repo = selected
        if (repo != null && synced == false && busy == null) sync(repo)
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
            val target = detail
            if (target != null) {
                PluginDetail(
                    target = target,
                    installedVersion = store.installedVersion(target.meta.id),
                    compatible = PluginFormats.isSupported(appVersion, target.meta.supported),
                    busy = busy != null,
                    onBack = { detail = null },
                    onInstall = target.plugin?.let { plugin ->
                        {
                            val repo = repoState.repositories.firstOrNull { PluginRepos.same(it.url, target.repoUrl) }
                            if (repo != null) {
                                val have = store.installedVersion(target.meta.id)
                                confirmInstall(
                                    plugin,
                                    repo,
                                    have != null && PluginFormats.compareVersions(target.meta.version, have) > 0
                                )
                            }
                        }
                    }
                )
            } else {
                Text("Plugins", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "A plugin can run any command in the Gitara Box with access to the open repository. " +
                        "Secrets are never exposed to plugins unless you pass them in with:. Install only plugins you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Browse") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Repositories") })
                }

                when (tab) {
                    0 -> {
                        if (installed.isEmpty()) {
                            Text("No plugins installed. Open Browse to find some.", style = MaterialTheme.typography.bodyMedium)
                        }
                        installed.forEach { info ->
                            InstalledRow(
                                info = info,
                                commands = commands.filter { it.pluginId == info.id },
                                onToggle = { enabled ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) { store.setEnabled(info.id, enabled) }
                                        tick++
                                    }
                                },
                                onUninstall = {
                                    pending = PendingAction(
                                        title = "Uninstall plugin?",
                                        message = "${info.id} will be removed. Workflows that use it will fail.",
                                        confirm = "Uninstall",
                                        destructive = true
                                    ) {
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { runCatching { store.uninstall(info.id) } }
                                            tick++
                                            result.onSuccess { snackbar.showSnackbar("Uninstalled ${info.id}") }
                                                .onFailure { snackbar.showSnackbar(it.message ?: "Could not uninstall") }
                                        }
                                    }
                                },
                                onReadme = {
                                    info.meta?.let { detail = DetailTarget(it, null, info.source, false) }
                                },
                                onRun = { ref -> onRunCommand(store.commandDefinition(ref)) }
                            )
                        }
                        if (locals.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Test a plugin from this repository", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            locals.forEach { dir ->
                                OutlinedButton(
                                    onClick = {
                                        pending = PendingAction(
                                            title = "Install for testing?",
                                            message = "${dir.name} will be installed from this repository on this device only.",
                                            confirm = "Install",
                                            destructive = false
                                        ) { installLocal(dir) }
                                    },
                                    enabled = busy == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Install ${dir.name} (local test)") }
                            }
                        }
                    }
                    1 -> {
                        if (selected == null) {
                            Text("No repository selected.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(selected.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (PluginRepos.isOfficial(selected.url, official)) "Official repository" else "Not on the official list",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (PluginRepos.isOfficial(selected.url, official)) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                                TextButton(onClick = { sync(selected) }, enabled = busy == null) { Text("Sync") }
                            }
                            if (synced == true && catalog.isEmpty()) {
                                Text("No plugins found in this repository.", style = MaterialTheme.typography.bodyMedium)
                            }
                            catalog.forEach { plugin ->
                                val meta = plugin.meta
                                CatalogRow(
                                    plugin = plugin,
                                    installedVersion = store.installedVersion(plugin.id),
                                    compatible = meta != null && PluginFormats.isSupported(appVersion, meta.supported),
                                    busy = busy != null,
                                    onDetails = {
                                        if (meta != null) {
                                            detail = DetailTarget(meta, plugin, selected.url, PluginRepos.isOfficial(selected.url, official))
                                        }
                                    },
                                    onInstall = {
                                        val have = store.installedVersion(plugin.id)
                                        confirmInstall(
                                            plugin,
                                            selected,
                                            have != null && meta != null && PluginFormats.compareVersions(meta.version, have) > 0
                                        )
                                    }
                                )
                            }
                        }
                    }
                    else -> {
                        repoState.repositories.forEach { repo ->
                            RepoRow(
                                repo = repo,
                                isDefault = PluginRepos.same(repo.url, repoState.selected),
                                official = PluginRepos.isOfficial(repo.url, official),
                                busy = busy != null,
                                onUse = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { repos.select(repo.url) }
                                        tick++
                                    }
                                },
                                onSync = { sync(repo) },
                                onRemove = {
                                    pending = PendingAction(
                                        title = "Remove repository?",
                                        message = "${repo.name} will be removed from the list. Plugins already installed stay installed.",
                                        confirm = "Remove",
                                        destructive = true
                                    ) {
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { runCatching { repos.remove(repo.url) } }
                                            tick++
                                            result.onFailure { snackbar.showSnackbar(it.message ?: "Could not remove the repository") }
                                        }
                                    }
                                }
                            )
                        }
                        HorizontalDivider()
                        Text("Add a repository", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = newUrl,
                            onValueChange = { newUrl = it },
                            label = { Text("Repository URL") },
                            placeholder = { Text("https://github.com/owner/repository") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newBranch,
                            onValueChange = { newBranch = it },
                            label = { Text("Branch (optional)") },
                            supportingText = { Text("Empty tries dist first, then the default branch.") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (PluginRepos.isOfficial(newUrl, official)) {
                                    addRepository()
                                } else {
                                    pending = PendingAction(
                                        title = "Add repository?",
                                        message = "This repository is not on the official list in Gitara's README. " +
                                            "Anyone can publish plugins there, so review a plugin before you install it.",
                                        confirm = "Add",
                                        destructive = false
                                    ) { addRepository() }
                                }
                            },
                            enabled = newUrl.isNotBlank() && busy == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add repository") }
                    }
                }
            }

            busy?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            SnackbarHost(hostState = snackbar)
        }
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    action.run()
                }) {
                    Text(
                        action.confirm,
                        color = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
private fun InstalledRow(
    info: PluginInfo,
    commands: List<PluginCommandRef>,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onReadme: () -> Unit,
    onRun: (PluginCommandRef) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val meta = info.meta
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (info.error != null) scheme.error.copy(alpha = 0.6f) else scheme.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(meta?.title ?: info.id, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "plugin:${info.id}/",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant
                    )
                }
                if (meta != null) Switch(checked = info.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall ${info.id}", tint = scheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (meta != null) Tag("v" + meta.version, scheme.primary)
                if (meta != null && !info.compatible) Tag("Needs a different Gitara version", scheme.error)
            }
            if (info.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(info.error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            if (meta != null) {
                TextButton(onClick = onReadme) { Text("README") }
            }
            if (info.active) {
                commands.forEach { ref ->
                    OutlinedButton(
                        onClick = { onRun(ref) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                            Text("Run: ${ref.command.name}")
                            if (ref.command.description.isNotBlank()) {
                                Text(
                                    ref.command.description,
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

@Composable
private fun CatalogRow(
    plugin: CatalogPlugin,
    installedVersion: String?,
    compatible: Boolean,
    busy: Boolean,
    onDetails: () -> Unit,
    onInstall: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val meta = plugin.meta
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (meta == null) scheme.error.copy(alpha = 0.6f) else scheme.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(meta?.title ?: plugin.id, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                plugin.id,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant
            )
            if (meta == null) {
                Spacer(Modifier.height(4.dp))
                Text(plugin.error ?: "Invalid plugin", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                return@Column
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("v" + meta.version, scheme.primary)
                if (installedVersion != null) Tag("Installed v$installedVersion", scheme.onSurfaceVariant)
                if (!compatible) Tag("Not for this Gitara", scheme.error)
            }
            Text(
                "Supports Gitara " + meta.supported.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDetails) { Text("Details") }
                Spacer(Modifier.weight(1f))
                val label = when {
                    installedVersion == null -> "Install"
                    PluginFormats.compareVersions(meta.version, installedVersion) > 0 -> "Update"
                    else -> "Reinstall"
                }
                Button(onClick = onInstall, enabled = compatible && !busy) { Text(label) }
            }
        }
    }
}

@Composable
private fun RepoRow(
    repo: PluginRepository,
    isDefault: Boolean,
    official: Boolean,
    busy: Boolean,
    onUse: () -> Unit,
    onSync: () -> Unit,
    onRemove: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (isDefault) scheme.primary else scheme.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(repo.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                repo.url + if (repo.branch.isNotEmpty()) "  [${repo.branch}]" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isDefault) Tag("Default", scheme.primary)
                if (official) Tag("Official", scheme.primary) else Tag("Not on the official list", scheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isDefault) TextButton(onClick = onUse) { Text("Use as default") }
                TextButton(onClick = onSync, enabled = !busy) { Text("Sync") }
                Spacer(Modifier.weight(1f))
                if (!repo.builtin) TextButton(onClick = onRemove) { Text("Remove", color = scheme.error) }
            }
        }
    }
}

@Composable
private fun PluginDetail(
    target: DetailTarget,
    installedVersion: String?,
    compatible: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onInstall: (() -> Unit)?
) {
    val scheme = MaterialTheme.colorScheme
    val meta = target.meta
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(meta.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "plugin:${meta.id}/",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant
            )
        }
    }
    val lines = buildList {
        add("Version: v${meta.version}" + if (installedVersion != null) "  (installed: v$installedVersion)" else "")
        add("Supports Gitara: " + meta.supported.joinToString(", "))
        if (meta.dependencies.isNotEmpty()) {
            add("Needs: " + meta.dependencies.joinToString(", ") { it.id + if (it.minVersion.isNotEmpty()) " ${it.minVersion}+" else "" })
        }
        add("Provides: ${meta.actions} action(s), ${meta.templates} template(s), ${meta.commands} command(s)")
        if (target.repoUrl.isNotEmpty()) {
            add("Source: ${target.repoUrl}" + if (target.plugin != null) (if (target.official) "  (official)" else "  (not on the official list)") else "")
        }
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (!compatible) {
        Text(
            "This plugin does not list the installed Gitara version, so it cannot be installed or used.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.error
        )
    }
    if (onInstall != null) {
        val label = when {
            installedVersion == null -> "Install"
            PluginFormats.compareVersions(meta.version, installedVersion) > 0 -> "Update"
            else -> "Reinstall"
        }
        Button(onClick = onInstall, enabled = compatible && !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
    HorizontalDivider()
    MarkdownText(meta.readme)
}