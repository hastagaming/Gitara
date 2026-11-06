package io.gitara.ui.repodetail

import android.content.Context
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.gitara.hosting.GitHost
import io.gitara.ui.theme.GitaraTheme
import io.gitara.ui.theme.accentFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.sheimi.sgit.R
import me.sheimi.sgit.database.models.Repo

private const val COMMITS_TAB = 1

private val TAB_LABELS = listOf(
    R.string.tab_files_label,
    R.string.tab_commits_label,
    R.string.tab_status_label
)

private data class RepoInfo(
    val branch: String,
    val kind: String,
    val message: String,
    val committer: String,
    val time: Long?
)

object RepoDetailComposeEntry {
    @JvmStatic
    fun create(
        context: Context,
        controller: RepoDetailController,
        host: RepoDetailHost,
        repo: Repo
    ): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { RepoDetailRoot(controller, host, repo) }
    }
}

@Composable
private fun RepoDetailRoot(controller: RepoDetailController, host: RepoDetailHost, repo: Repo) {
    val remote = remember(repo) { repo.getRemoteURL().orEmpty() }
    val gitHost = remember(remote) { GitHost.fromUrl(remote) }
    GitaraTheme(host = gitHost, darkTheme = false) {
        RepoDetailScreen(controller, host, repo, gitHost, remote)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoDetailScreen(
    controller: RepoDetailController,
    host: RepoDetailHost,
    repo: Repo,
    gitHost: GitHost,
    remote: String
) {
    val scheme = MaterialTheme.colorScheme
    val accent = accentFor(gitHost).light
    val name = remember(repo) { repo.getDiaplayName().orEmpty().removeSuffix(" (external)") }

    val info by produceState<RepoInfo?>(null, controller.refreshTick) {
        value = withContext(Dispatchers.IO) { loadInfo(repo) }
    }

    LaunchedEffect(controller.selectedTab) {
        host.onTabSelected(controller.selectedTab)
        if (controller.selectedTab != COMMITS_TAB) controller.closeSearch()
    }
    LaunchedEffect(controller.searching, controller.query) {
        val q = controller.query
        host.onCommitSearch(if (controller.searching && q.isNotEmpty()) q else null)
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            val focus = remember { FocusRequester() }
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.surfaceContainer,
                    titleContentColor = scheme.onSurface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (controller.searching) controller.closeSearch() else host.onNavigateUp()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    if (controller.searching) {
                        TextField(
                            value = controller.query,
                            onValueChange = { controller.updateQuery(it) },
                            placeholder = { Text("Search commits") },
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
                        Text(
                            name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (controller.searching) {
                        if (controller.query.isNotEmpty()) {
                            IconButton(onClick = { controller.updateQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    } else {
                        if (controller.selectedTab == COMMITS_TAB) {
                            IconButton(onClick = { controller.openSearch() }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search commits")
                            }
                        }
                        IconButton(onClick = { controller.openOperations() }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Repository actions")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            RepoHeader(
                name = name,
                remote = remote,
                gitHost = gitHost,
                accent = accent,
                info = info,
                onBranch = { host.onChooseBranch() }
            )
            if (controller.progressVisible) {
                ProgressStrip(controller)
            }
            TabRow(
                selectedTabIndex = controller.selectedTab,
                containerColor = scheme.surfaceContainerLow,
                contentColor = scheme.primary
            ) {
                TAB_LABELS.forEachIndexed { index, label ->
                    Tab(
                        selected = controller.selectedTab == index,
                        onClick = { controller.selectTab(index) },
                        text = { Text(stringResource(label), maxLines = 1) }
                    )
                }
            }
            HorizontalDivider(color = scheme.outlineVariant)
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        id = R.id.repo_fragment_container
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        post { host.onFragmentContainerReady() }
                    }
                }
            )
        }
    }

    if (controller.operationsOpen) {
        val surfaceArgb = scheme.surface.toArgb()
        ModalBottomSheet(
            onDismissRequest = { controller.closeOperations() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = scheme.surface
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Text(
                    "Repository actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 520.dp),
                    factory = { ctx ->
                        host.createOperationsList(ctx).apply { setBackgroundColor(surfaceArgb) }
                    }
                )
            }
        }
    }
}

@Composable
private fun RepoHeader(
    name: String,
    remote: String,
    gitHost: GitHost,
    accent: Color,
    info: RepoInfo?,
    onBranch: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val subtitle = if (remote.isNotBlank()) prettyRemote(remote) else "Local repository"
    val meta = listOfNotNull(
        info?.committer?.takeIf { it.isNotBlank() },
        info?.time?.let { relativeCommitTime(it) }
    ).joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarBadge(letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", color = accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
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
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BranchChip(
                label = info?.branch?.ifBlank { "unknown" } ?: "…",
                kind = info?.kind ?: "branch",
                onClick = onBranch
            )
            if (remote.isNotBlank()) {
                InfoPill(gitHost.displayName, accent)
            }
        }
        if (info != null && info.message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                info.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProgressStrip(controller: RepoDetailController) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            controller.progressMessage,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        if (controller.progressValue <= 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { controller.progressValue.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                controller.progressLeft,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Text(
                controller.progressRight,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BranchChip(label: String, kind: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.outline)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                kind,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            Text("▾", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AvatarBadge(letter: String, color: Color) {
    Box(
        modifier = Modifier
            .size(44.dp)
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
private fun InfoPill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun loadInfo(repo: Repo): RepoInfo {
    try {
        repo.updateLatestCommitInfo()
    } catch (e: Exception) {
        timber.log.Timber.e(e, "Failed to refresh latest commit info")
    }
    val fresh = try {
        Repo.getRepoById(repo.getID().toLong())
    } catch (e: Exception) {
        repo
    }
    val full = try {
        repo.getBranchName().orEmpty()
    } catch (e: Exception) {
        ""
    }
    val isSha = full.length == 40 && full.all { it.isDigit() || it in 'a'..'f' }
    val type = Repo.getCommitType(full)
    val local = if (type == Repo.COMMIT_TYPE_REMOTE) Repo.convertRemoteName(full) ?: full else full
    val label = if (isSha) full.take(7) else Repo.getCommitDisplayName(local).orEmpty()
    val kind = when {
        isSha -> "detached"
        type == Repo.COMMIT_TYPE_TAG -> "tag"
        else -> "branch"
    }
    return RepoInfo(
        branch = label,
        kind = kind,
        message = fresh.getLastCommitMsg()?.trim().orEmpty(),
        committer = fresh.getLastCommitter()?.trim().orEmpty(),
        time = fresh.getLastCommitDate()?.time?.takeIf { it > 0L }
    )
}

private fun prettyRemote(url: String): String {
    val cleaned = url.trim().trimEnd('/').removeSuffix(".git")
    val withoutScheme = if (cleaned.contains("://")) cleaned.substringAfter("://") else cleaned
    val withoutAuth = withoutScheme.substringAfter('@')
    return withoutAuth.replaceFirst(Regex(":(?![0-9]+/)"), "/")
}

private fun relativeCommitTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()