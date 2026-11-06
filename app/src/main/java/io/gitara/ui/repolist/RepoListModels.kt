package io.gitara.ui.repolist

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.gitara.hosting.GitHost
import me.sheimi.sgit.database.RepoDbManager
import me.sheimi.sgit.database.models.Repo
import timber.log.Timber

data class CloneRequest(
    val remoteUrl: String,
    val folderName: String,
    val username: String,
    val password: String,
    val recursive: Boolean
)

interface RepoListHost {
    fun onOpenRepo(repo: Repo)
    fun onImportRepo()
    fun onOpenSettings()
    fun onCloneRequested(request: CloneRequest): String?
}

@Stable
class RepoListController {
    var refreshTick by mutableIntStateOf(0)
        private set

    var clonePrefill by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        refreshTick++
    }

    fun openClone(remoteUrl: String) {
        clonePrefill = remoteUrl
    }

    fun consumeClonePrefill() {
        clonePrefill = null
    }
}

@Immutable
data class RepoItem(
    val repo: Repo,
    val id: Int,
    val name: String,
    val remoteUrl: String,
    val host: GitHost,
    val status: String,
    val busy: Boolean,
    val failed: Boolean,
    val lastMessage: String,
    val lastCommitter: String,
    val lastCommitTime: Long?,
    val external: Boolean
)

enum class SortMode(val label: String) {
    RECENT("Recently updated"),
    NAME("Name"),
    ADDED("Recently added");

    fun comparator(): Comparator<RepoItem> = when (this) {
        RECENT -> compareByDescending<RepoItem> { it.lastCommitTime ?: Long.MIN_VALUE }
            .thenByDescending { it.id }
        NAME -> compareBy<RepoItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        ADDED -> compareByDescending<RepoItem> { it.id }
    }
}

object RepoLoader {
    fun load(context: Context): List<RepoItem> {
        return try {
            val cursor = RepoDbManager.queryAllRepo() ?: return emptyList()
            try {
                Repo.getRepoList(context, cursor).map { toItem(it) }
            } finally {
                cursor.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load repository list")
            emptyList()
        }
    }

    private fun toItem(repo: Repo): RepoItem {
        val status = repo.getRepoStatus()?.trim().orEmpty()
        val lower = status.lowercase()
        val failed = lower.contains("fail") || lower.contains("error") || lower.contains("cancel")
        val remote = repo.getRemoteURL()?.trim().orEmpty()
        val time = repo.getLastCommitDate()?.time?.takeIf { it > 0L }
        return RepoItem(
            repo = repo,
            id = repo.getID(),
            name = repo.getDiaplayName().orEmpty().removeSuffix(" (external)"),
            remoteUrl = remote,
            host = GitHost.fromUrl(remote),
            status = status,
            busy = status.isNotEmpty() && !failed,
            failed = failed,
            lastMessage = repo.getLastCommitMsg()?.trim().orEmpty(),
            lastCommitter = repo.getLastCommitter()?.trim().orEmpty(),
            lastCommitTime = time,
            external = repo.isExternal()
        )
    }
}