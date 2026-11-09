package io.gitara.plugins

import android.content.Context
import io.gitara.box.deleteTree
import org.eclipse.jgit.api.Git
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class PluginRepos(context: Context) {

    data class State(val repositories: List<PluginRepository>, val selected: String)

    private val home = File(context.applicationContext.filesDir, "home")
    private val listFile = File(home, "plugin-repos.yml")
    private val officialFile = File(home, "official-repos.txt")
    private val cacheRoot = File(home, "plugin-repos")

    fun load(): State {
        val repos = ArrayList<PluginRepository>()
        var selected = ""
        if (listFile.isFile) {
            try {
                val root = asMap(Load(LoadSettings.builder().build()).loadFromString(listFile.readText(Charsets.UTF_8)))
                if (root != null) {
                    selected = root["selected"]?.toString().orEmpty()
                    (root["repositories"] as? List<*>)?.forEach { item ->
                        val entry = asMap(item) ?: return@forEach
                        val url = entry["url"]?.toString()?.trim().orEmpty()
                        if (!isValidUrl(url) || repos.any { same(it.url, url) }) return@forEach
                        repos.add(
                            PluginRepository(
                                name = entry["name"]?.toString()?.trim().orEmpty().ifEmpty { nameOf(url) },
                                url = url,
                                branch = entry["branch"]?.toString()?.trim().orEmpty(),
                                builtin = same(url, DEFAULT_URL)
                            )
                        )
                    }
                }
            } catch (e: YamlEngineException) {
                // a damaged list falls back to the default repository below
            } catch (e: IOException) {
                // an unreadable list falls back to the default repository below
            }
        }
        if (repos.none { it.builtin }) repos.add(0, PluginRepository("GCR", DEFAULT_URL, DEFAULT_BRANCH, true))
        if (repos.none { same(it.url, selected) }) selected = DEFAULT_URL
        return State(repos, selected)
    }

    fun add(name: String, url: String, branch: String): State {
        val cleanUrl = url.trim()
        if (!isValidUrl(cleanUrl)) throw PluginException("Use a full https:// repository URL")
        val cleanBranch = branch.trim()
        if (!BRANCH.matches(cleanBranch)) throw PluginException("The branch name is not valid")
        val state = load()
        if (state.repositories.any { same(it.url, cleanUrl) }) throw PluginException("This repository is already in the list")
        val repo = PluginRepository(name.trim().ifEmpty { nameOf(cleanUrl) }.take(60), cleanUrl, cleanBranch, false)
        val next = State(state.repositories + repo, state.selected)
        save(next)
        return next
    }

    fun remove(url: String): State {
        val state = load()
        val repo = state.repositories.firstOrNull { same(it.url, url) } ?: return state
        if (repo.builtin) throw PluginException("The default repository cannot be removed")
        deleteTree(cacheDir(repo))
        val selected = if (same(state.selected, url)) DEFAULT_URL else state.selected
        val next = State(state.repositories.filter { it !== repo }, selected)
        save(next)
        return next
    }

    fun select(url: String): State {
        val state = load()
        if (state.repositories.none { same(it.url, url) }) return state
        val next = State(state.repositories, url)
        save(next)
        return next
    }

    fun isSynced(repo: PluginRepository): Boolean = cacheDir(repo).isDirectory

    fun sync(repo: PluginRepository, onStage: (String) -> Unit) {
        cacheRoot.mkdirs()
        val temp = File(cacheRoot, ".sync-" + slug(repo.url))
        deleteTree(temp)
        val branches = if (repo.branch.isNotBlank()) listOf(repo.branch) else listOf("dist", "")
        var lastError: Exception? = null
        var done = false
        for (branch in branches) {
            try {
                onStage("Fetching ${repo.name}" + if (branch.isNotEmpty()) " ($branch)…" else "…")
                val command = Git.cloneRepository().setURI(repo.url).setDirectory(temp)
                if (branch.isNotEmpty()) command.setBranch("refs/heads/$branch")
                val git = command.call()
                git.repository.close()
                if (temp.listFiles()?.any { it.name != ".git" } != true) throw IOException("The branch is empty")
                done = true
                break
            } catch (e: Exception) {
                lastError = e
                deleteTree(temp)
            }
        }
        if (!done) throw PluginException("Could not fetch ${repo.url}: ${lastError?.message ?: "unknown error"}")
        deleteTree(File(temp, ".git"))
        val target = cacheDir(repo)
        deleteTree(target)
        if (!temp.renameTo(target)) {
            deleteTree(temp)
            throw PluginException("Could not store the downloaded repository")
        }
    }

    fun catalog(repo: PluginRepository): List<CatalogPlugin> {
        val base = cacheDir(repo)
        if (!base.isDirectory) return emptyList()
        val root = File(base, "plugins").takeIf { it.isDirectory } ?: base
        val dirs = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        return dirs.map { dir ->
            try {
                CatalogPlugin(dir.name, PluginFormats.readMeta(dir), null, dir)
            } catch (e: Exception) {
                CatalogPlugin(dir.name, null, e.message ?: "Invalid plugin", dir)
            }
        }
    }

    fun resolve(dependency: PluginDependency, defaultRepoUrl: String): Pair<CatalogPlugin, String>? {
        val url = dependency.repo.ifEmpty { defaultRepoUrl }
        val repo = load().repositories.firstOrNull { same(it.url, url) } ?: return null
        if (!isSynced(repo)) sync(repo) { }
        val plugin = catalog(repo).firstOrNull { it.id == dependency.id } ?: return null
        return plugin to repo.url
    }

    fun refreshOfficial() {
        try {
            val connection = URL(OFFICIAL_SOURCE).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) {
                connection.disconnect()
                return
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val section = text.substringAfter(OFFICIAL_START, "").substringBefore(OFFICIAL_END, "")
            val urls = URL_PATTERN.findAll(section).map { normalize(it.value) }.toSet()
            if (urls.isNotEmpty()) {
                home.mkdirs()
                officialFile.writeText(urls.joinToString("\n"), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // offline or blocked: keep the cached list
        }
    }

    fun officialSet(): Set<String> {
        val set = HashSet<String>()
        set.add(normalize(DEFAULT_URL))
        if (officialFile.isFile) {
            officialFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { set.add(it) }
        }
        return set
    }

    private fun cacheDir(repo: PluginRepository): File = File(cacheRoot, slug(repo.url))

    private fun save(state: State) {
        home.mkdirs()
        val sb = StringBuilder()
        sb.append("selected: ").append(quote(state.selected)).append('\n')
        sb.append("repositories:\n")
        for (repo in state.repositories) {
            sb.append("  - name: ").append(quote(repo.name)).append('\n')
            sb.append("    url: ").append(quote(repo.url)).append('\n')
            sb.append("    branch: ").append(quote(repo.branch)).append('\n')
        }
        listFile.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

    private fun asMap(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *>) return null
        val out = LinkedHashMap<String, Any?>()
        for ((key, item) in value) out[key.toString()] = item
        return out
    }

    private fun isValidUrl(url: String): Boolean =
        url.startsWith("https://") && url.length in 13..300 && url.none { it.isWhitespace() }

    private fun nameOf(url: String): String =
        url.trimEnd('/').removeSuffix(".git").substringAfterLast('/').ifEmpty { "repository" }

    companion object {
        const val DEFAULT_URL = "https://github.com/hastagaming/GCR"
        const val DEFAULT_BRANCH = ""
        private const val OFFICIAL_SOURCE = "https://raw.githubusercontent.com/hastagaming/Gitara/HEAD/README.md"
        private const val OFFICIAL_START = "<!-- GCR-OFFICIAL-START -->"
        private const val OFFICIAL_END = "<!-- GCR-OFFICIAL-END -->"
        private val BRANCH = Regex("^[A-Za-z0-9._/-]{0,100}$")
        private val URL_PATTERN = Regex("https://[^\\s)>\\]|\"'`]+")

        fun normalize(url: String): String {
            var value = url.trim().lowercase(Locale.ROOT).trimEnd('/', ',', ';')
            if (value.endsWith(".git")) value = value.removeSuffix(".git")
            return value.trimEnd('/', '.')
        }

        fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

        fun isOfficial(url: String, official: Set<String>): Boolean = normalize(url) in official

        private fun slug(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(normalize(url).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(12)
        }
    }
}