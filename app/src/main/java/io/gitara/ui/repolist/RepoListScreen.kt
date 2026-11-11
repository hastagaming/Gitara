package io.gitara.ui.repolist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.gitara.hosting.GitHost
import io.gitara.ui.theme.GitaraTheme
import io.gitara.ui.theme.accentFor
import io.gitara.plugins.PluginCommandRef
import io.gitara.plugins.PluginStore
import io.gitara.ui.workflow.PluginsSheet
import io.gitara.workflow.StartResult
import io.gitara.workflow.WorkflowEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOCAL_KEY = "LOCAL"

object RepoListComposeEntry {
    @JvmStatic
    fun create(context: Context, controller: RepoListController, host: RepoListHost): ComposeView =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RepoListRoot(controller, host) }
        }
}

@Composable
fun RepoListRoot(controller: RepoListController, host: RepoListHost) {
    GitaraTheme(host = GitHost.GENERIC) {
        RepoListScreen(controller, host)
    }
}

@Composable
internal fun hostColor(host: GitHost): Color {
    val accent = accentFor(host)
    return if (isSystemInDarkTheme()) accent.dark else accent.light
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoListScreen(controller: RepoListController, host: RepoListHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pluginStore = remember { PluginStore(context.applicationContext) }
    var showPlugins by remember { mutableStateOf(false) }
    val cardCommands by produceState(emptyList<PluginCommandRef>(), showPlugins) {
        value = withContext(Dispatchers.IO) { pluginStore.commandsFor("repo-card") }
    }

    var localTick by remember { mutableIntStateOf(0) }
    val loaded by produceState<List<RepoItem>?>(initialValue = null, controller.refreshTick, localTick) {
        value = withContext(Dispatchers.IO) { RepoLoader.load(context.applicationContext) }
    }
    val all = loaded ?: emptyList()
    val anyBusy = all.any { it.busy }
    LaunchedEffect(anyBusy) {
        while (anyBusy) {
            delay(1500)
            localTick++
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var hostFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMode by rememberSaveable { mutableStateOf(SortMode.RECENT) }
    var showCloneSheet by rememberSaveable { mutableStateOf(false) }
    var clonePrefillUrl by rememberSaveable { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<RepoItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RepoItem?>(null) }

    val prefill = controller.clonePrefill
    LaunchedEffect(prefill) {
        if (prefill != null) {
            clonePrefillUrl = prefill
            showCloneSheet = true
            controller.consumeClonePrefill()
        }
    }

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    val hostCounts = remember(all) {
        val counts = linkedMapOf<GitHost, Int>()
        all.filter { it.remoteUrl.isNotBlank() }.forEach { counts[it.host] = (counts[it.host] ?: 0) + 1 }
        counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    val localCount = remember(all) { all.count { it.remoteUrl.isBlank() } }

    LaunchedEffect(hostCounts, localCount) {
        val current = hostFilter
        if (current != null) {
            val stillPresent = if (current == LOCAL_KEY) localCount > 0 else hostCounts.any { it.first.name == current }
            if (!stillPresent) hostFilter = null
        }
    }

    val visible = remember(all, query, hostFilter, sortMode) {
        val q = query.trim().lowercase()
        all.filter { item ->
            val hostOk = when (hostFilter) {
                null -> true
                LOCAL_KEY -> item.remoteUrl.isBlank()
                else -> item.remoteUrl.isNotBlank() && item.host.name == hostFilter
            }
            val textOk = q.isEmpty() ||
                item.name.lowercase().contains(q) ||
                item.remoteUrl.lowercase().contains(q) ||
                item.lastMessage.lowercase().contains(q) ||
                item.lastCommitter.lowercase().contains(q)
            hostOk && textOk
        }.sortedWith(sortMode.comparator())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RepoTopBar(
                searching = searching,
                query = query,
                onQueryChange = { query = it },
                onSearchToggle = {
                    searching = it
                    if (!it) query = ""
                },
                onImport = { host.onImportRepo() },
                onSettings = { host.onOpenSettings() }
                onPlugins = { showPlugins = true },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    clonePrefillUrl = ""
                    showCloneSheet = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Clone") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (loaded != null && all.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FilterRow(
                    total = all.size,
                    hostCounts = hostCounts,
                    localCount = localCount,
                    selected = hostFilter,
                    onSelect = { hostFilter = it }
                )
                SummaryRow(
                    count = visible.size,
                    sortMode = sortMode,
                    onSortChange = { sortMode = it }
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    all.isEmpty() -> EmptyState(
                        onClone = {
                            clonePrefillUrl = ""
                            showCloneSheet = true
                        },
                        onImport = { host.onImportRepo() }
                    )
                    visible.isEmpty() -> NoResults(query)
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(visible, key = { it.id }) { item ->
                            RepoCard(
                                item = item,
                                onClick = { host.onOpenRepo(item.repo) },
                                onActions = { actionTarget = item }
                            )
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                ActionRow("Open") {
                    actionTarget = null
                    host.onOpenRepo(item.repo)
                }
                cardCommands.forEach { ref ->
                    ActionRow(ref.command.name) {
                        actionTarget = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                WorkflowEngine.start(context.applicationContext, item.repo, pluginStore.commandDefinition(ref), emptyMap())
                            }
                            snackbar.showSnackbar(
                                when (result) {
                                    is StartResult.Started -> "Running ${ref.command.name}"
                                    StartResult.AlreadyRunning -> "A workflow is already running in this repository"
                                    is StartResult.Failed -> result.message
                                }
                            )
                        }
                    }
                }
                if (item.remoteUrl.isNotBlank()) {
                    ActionRow("Copy remote URL") {
                        copyText(context, "Remote URL", item.remoteUrl)
                        actionTarget = null
                        scope.launch { snackbar.showSnackbar("Remote URL copied") }
                    }
                }
                if (item.busy) {
                    ActionRow("Cancel running operation") {
                        item.repo.cancelTask()
                        actionTarget = null
                        localTick++
                    }
                }
                ActionRow("Delete", destructive = true) {
                    actionTarget = null
                    deleteTarget = item
                }
            }
        }
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete repository?") },
            text = {
                Text(
                    if (item.external) {
                        "\"${item.name}\" will be removed from Gitara. The files in its folder stay untouched."
                    } else {
                        "\"${item.name}\" and its local files will be removed from this device. The remote repository is not affected."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) { item.repo.deleteRepoSync() }
                            controller.refresh()
                            snackbar.showSnackbar("Deleted ${item.name}")
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showCloneSheet) {
        CloneSheet(
            initialUrl = clonePrefillUrl,
            onDismiss = { showCloneSheet = false },
            onSubmit = { request ->
                val error = host.onCloneRequested(request)
                if (error == null) {
                    controller.refresh()
                    scope.launch { snackbar.showSnackbar("Cloning ${request.folderName}…") }
                }
                error
            }
        )
    }
    if (showPlugins) {
        PluginsSheet(
            store = pluginStore,
            repoDir = null,
            onRunCommand = null,
            onDismiss = { showPlugins = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoTopBar(
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onPlugins: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        navigationIcon = {
            if (searching) {
                IconButton(onClick = { onSearchToggle(false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                }
            }
        },
        title = {
            if (searching) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search repositories") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                Text("Gitara", fontWeight = FontWeight.SemiBold)
            }
        },
        actions = {
            if (searching) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            } else {
                IconButton(onClick = { onSearchToggle(true) }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Import repository") },
                            onClick = {
                                menuOpen = false
                                onImport()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                menuOpen = false
                                onSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Plugins") },
                            onClick = {
                                menuOpen = false
                                onPlugins()
                            }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun FilterRow(
    total: Int,
    hostCounts: List<Pair<GitHost, Int>>,
    localCount: Int,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All $total") }
            )
        }
        items(hostCounts) { (host, count) ->
            val isSelected = selected == host.name
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else host.name) },
                label = { Text("${host.displayName} $count") },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(hostColor(host), CircleShape)
                    )
                }
            )
        }
        if (localCount > 0) {
            item {
                val isSelected = selected == LOCAL_KEY
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(if (isSelected) null else LOCAL_KEY) },
                    label = { Text("Local $localCount") }
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(count: Int, sortMode: SortMode, onSortChange: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            if (count == 1) "1 repository" else "$count repositories",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            TextButton(onClick = { open = true }) { Text("Sort: ${sortMode.label}") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        onClick = {
                            open = false
                            onSortChange(mode)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoCard(item: RepoItem, onClick: () -> Unit, onActions: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = hostColor(item.host)

    val branch by produceState("", item.id, item.status, item.lastCommitTime) {
        value = if (item.busy) {
            ""
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val name = item.repo.getCurrentDisplayName().orEmpty()
                    if (name.length == 40) name.take(7) else name
                } catch (e: Exception) {
                    ""
                }
            }
        }
    }

    val subtitle = when {
        item.remoteUrl.isNotBlank() -> prettyRemote(item.remoteUrl)
        item.external -> "External folder"
        else -> "Local repository"
    }
    val meta = listOfNotNull(
        item.lastCommitter.takeIf { it.isNotBlank() },
        item.lastCommitTime?.let { relativeTime(it) }
    ).joinToString(" · ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outline)
    ) {
        Row(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onActions)
                .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            HostAvatar(letter = item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", color = accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.lastMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.lastMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.status.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Pill(
                        text = item.status,
                        color = if (item.failed) scheme.error else scheme.primary
                    )
                }
                if (item.busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, end = 10.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.remoteUrl.isNotBlank()) Pill(item.host.displayName, accent)
                    if (branch.isNotEmpty()) Pill(branch, scheme.onSurfaceVariant)
                    if (item.external) Pill("external", scheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onActions) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Repository actions")
            }
        }
    }
}

@Composable
private fun HostAvatar(letter: String, color: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                label,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun EmptyState(onClone: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HostAvatar(letter = "G", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            "No repositories yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Clone a repository from GitHub, GitLab, Gitea, Codeberg or any Git server, or import a folder from your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClone) { Text("Clone a repository") }
        OutlinedButton(onClick = onImport, modifier = Modifier.padding(top = 8.dp)) {
            Text("Import from device")
        }
    }
}

@Composable
private fun NoResults(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (query.isBlank()) "No repositories match this filter" else "No results for \"${query.trim()}\"",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneSheet(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSubmit: (CloneRequest) -> String?
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var folder by rememberSaveable { mutableStateOf(suggestFolderName(initialUrl)) }
    var folderEdited by rememberSaveable { mutableStateOf(false) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var recursive by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val detected = remember(url) { if (url.isBlank()) null else GitHost.fromUrl(url) }

    val submit: () -> Unit = {
        val result = onSubmit(CloneRequest(url.trim(), folder.trim(), user.trim(), pass, recursive))
        if (result == null) {
            onDismiss()
        } else {
            error = result
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Clone repository",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                    if (!folderEdited) folder = suggestFolderName(it)
                },
                label = { Text("Remote URL") },
                placeholder = { Text("https://github.com/owner/repo.git") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            if (detected != null) {
                Pill(
                    text = if (detected == GitHost.GENERIC) "Generic Git remote" else "Detected: ${detected.displayName}",
                    color = hostColor(detected)
                )
            }
            OutlinedTextField(
                value = folder,
                onValueChange = {
                    folder = it
                    folderEdited = true
                    error = null
                },
                label = { Text("Local folder name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password or access token (optional)") },
                singleLine = true,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Hide" else "Show") }
                },
                supportingText = { Text("GitHub, GitLab and Gitea require a personal access token here.") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Clone submodules", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Fetch submodules recursively",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = recursive, onCheckedChange = { recursive = it })
            }
            if (error != null) {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = submit,
                enabled = url.isNotBlank() && folder.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clone repository")
            }
        }
    }
}

internal fun suggestFolderName(url: String): String {
    val trimmed = url.trim().trimEnd('/').removeSuffix(".git")
    if (trimmed.isEmpty()) return ""
    return trimmed.substringAfterLast('/').substringAfterLast(':')
}

private fun prettyRemote(url: String): String {
    val cleaned = url.trim().trimEnd('/').removeSuffix(".git")
    val withoutScheme = if (cleaned.contains("://")) cleaned.substringAfter("://") else cleaned
    val withoutAuth = withoutScheme.substringAfter('@')
    return withoutAuth.replaceFirst(Regex(":(?![0-9]+/)"), "/")
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

private fun copyText(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}