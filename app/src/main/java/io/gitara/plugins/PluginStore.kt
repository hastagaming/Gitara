package io.gitara.plugins

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.gitara.box.deleteTree
import io.gitara.workflow.JobDef
import io.gitara.workflow.StepDef
import io.gitara.workflow.WorkflowDefinition
import java.io.File
import java.nio.file.Files
import java.util.Locale

typealias PluginResolver = (PluginDependency, String) -> Pair<CatalogPlugin, String>?

data class PluginInfo(
    val id: String,
    val meta: PluginMeta?,
    val error: String?,
    val enabled: Boolean,
    val compatible: Boolean,
    val source: String
) {
    val active: Boolean get() = enabled && compatible && meta != null
}

data class PluginTemplate(
    val pluginName: String,
    val title: String,
    val baseName: String,
    val content: String
)

data class PluginCommandRef(
    val pluginId: String,
    val pluginName: String,
    val command: PluginCommand
)

class PluginStore(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "home/plugins")
    private val currentVersion: String by lazy { readGitaraVersion() }

    fun gitaraVersion(): String = currentVersion

    fun list(): List<PluginInfo> {
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return emptyList()
        return dirs.sortedBy { it.name }.map { dir ->
            val source = File(dir, ".source").takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim().orEmpty()
            val enabled = !File(dir, ".disabled").exists()
            try {
                val meta = PluginFormats.readMeta(dir)
                PluginInfo(dir.name, meta, null, enabled, PluginFormats.isSupported(currentVersion, meta.supported), source)
            } catch (e: Exception) {
                PluginInfo(dir.name, null, e.message ?: "Invalid plugin", enabled, false, source)
            }
        }
    }

    fun installedVersion(id: String): String? {
        val file = File(root, "$id/VERSION")
        if (!file.isFile) return null
        return try {
            PluginFormats.parseVersion(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun commands(): List<PluginCommandRef> = list().filter { it.active }.flatMap { info ->
        val meta = info.meta ?: return@flatMap emptyList<PluginCommandRef>()
        yamlFiles(File(root, "${info.id}/main/commands")).mapNotNull { file ->
            PluginFormats.readCommand(file)?.let { PluginCommandRef(info.id, meta.title, it) }
        }
    }

    fun templates(): List<PluginTemplate> {
        val out = ArrayList<PluginTemplate>()
        for (info in list()) {
            val meta = info.meta
            if (!info.active || meta == null) continue
            for (file in yamlFiles(File(root, "${info.id}/main/templates"))) {
                if (file.length() > MAX_FILE) continue
                val content = try {
                    file.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    continue
                }
                val title = TITLE_NAME.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: file.nameWithoutExtension
                out.add(PluginTemplate(meta.title, title, file.nameWithoutExtension, content))
            }
        }
        return out
    }

    fun actionFile(reference: String): File? {
        val slash = reference.indexOf('/')
        if (slash <= 0) return null
        val pluginId = reference.substring(0, slash)
        val actionId = reference.substring(slash + 1)
        if (!PluginFormats.ID.matches(pluginId) || !ACTION_ID.matches(actionId)) return null
        val dir = File(root, pluginId)
        if (!isActive(dir)) return null
        val actionDir = File(dir, "main/actions/$actionId")
        return listOf("action.yml", "action.yaml").map { File(actionDir, it) }.firstOrNull { it.isFile }
    }

    fun commandDefinition(ref: PluginCommandRef): WorkflowDefinition {
        val step = StepDef(
            id = null,
            name = ref.command.name,
            condition = null,
            uses = null,
            withArgs = emptyMap(),
            script = ref.command.script,
            shell = ref.command.shell,
            workdir = null,
            env = emptyMap(),
            continueOnError = "false",
            timeoutMinutes = null
        )
        val job = JobDef(
            id = "command",
            name = ref.command.name,
            needs = emptyList(),
            condition = null,
            env = emptyMap(),
            timeoutMinutes = ref.command.timeoutMinutes,
            outputs = emptyMap(),
            steps = listOf(step)
        )
        return WorkflowDefinition(
            fileName = "plugin:${ref.pluginId}/${ref.command.id}",
            name = "${ref.pluginName}: ${ref.command.name}",
            env = emptyMap(),
            inputs = emptyMap(),
            jobs = listOf(job),
            sandboxed = true
        )
    }

    fun localCandidates(repoDir: File): List<File> {
        val found = File(repoDir, "plugins").listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && File(it, "main").isDirectory && File(it, "VERSION").isFile }
            ?.sortedBy { it.name }
        if (!found.isNullOrEmpty()) return found
        return if (File(repoDir, "main").isDirectory && File(repoDir, "VERSION").isFile) listOf(repoDir) else emptyList()
    }

    fun installLocal(dir: File, onStage: (String) -> Unit): List<String> {
        val meta = PluginFormats.readMeta(dir)
        return install(CatalogPlugin(meta.id, meta, null, dir), "local:" + dir.name, { _, _ -> null }, onStage)
    }

    fun install(plugin: CatalogPlugin, repoUrl: String, resolve: PluginResolver, onStage: (String) -> Unit): List<String> {
        val installed = ArrayList<String>()
        installRecursive(plugin, repoUrl, resolve, onStage, installed, HashSet(), 0)
        return installed
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (!PluginFormats.ID.matches(id)) return
        val dir = File(root, id)
        if (!dir.isDirectory) return
        val marker = File(dir, ".disabled")
        if (enabled) marker.delete() else marker.createNewFile()
    }

    fun uninstall(id: String) {
        if (!PluginFormats.ID.matches(id)) return
        val dependents = list()
            .filter { it.id != id && it.meta?.dependencies?.any { dep -> dep.id == id } == true }
            .map { it.id }
        if (dependents.isNotEmpty()) {
            throw PluginException("Required by ${dependents.joinToString(", ")}. Uninstall those first.")
        }
        deleteTree(File(root, id))
    }

    private fun installRecursive(
        plugin: CatalogPlugin,
        repoUrl: String,
        resolve: PluginResolver,
        onStage: (String) -> Unit,
        installed: MutableList<String>,
        visiting: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 8) throw PluginException("The dependencies are nested too deeply")
        val meta = plugin.meta ?: throw PluginException("${plugin.id}: ${plugin.error ?: "invalid plugin"}")
        if (!visiting.add(meta.id)) throw PluginException("Circular dependency involving ${meta.id}")
        if (!PluginFormats.isSupported(currentVersion, meta.supported)) {
            throw PluginException(
                "${meta.id} supports Gitara ${meta.supported.joinToString(", ")} only (this is v$currentVersion)"
            )
        }
        for (dep in meta.dependencies) {
            val have = installedVersion(dep.id)
            val minimum = dep.minVersion.ifEmpty { "0.0.0" }
            if (have != null && PluginFormats.compareVersions(have, minimum) >= 0) continue
            val found = resolve(dep, repoUrl) ?: throw PluginException(
                "${meta.id} needs the plugin '${dep.id}' ${dep.minVersion}, which was not found. " +
                    "Add the repository that provides it first."
            )
            val depMeta = found.first.meta
            if (depMeta != null && PluginFormats.compareVersions(depMeta.version, minimum) < 0) {
                throw PluginException("${meta.id} needs ${dep.id} $minimum or newer, but the repository has ${depMeta.version}")
            }
            installRecursive(found.first, found.second, resolve, onStage, installed, visiting, depth + 1)
        }
        onStage("Installing ${meta.id} ${meta.version}…")
        copyIn(plugin.dir, meta.id, repoUrl)
        installed.add(meta.id)
        visiting.remove(meta.id)
    }

    private fun copyIn(from: File, id: String, source: String) {
        validateTree(from)
        root.mkdirs()
        val temp = File(root, ".install-$id")
        deleteTree(temp)
        copyTree(from, temp, true)
        File(temp, ".source").writeText(source, Charsets.UTF_8)
        val target = File(root, id)
        val wasDisabled = File(target, ".disabled").exists()
        deleteTree(target)
        if (!temp.renameTo(target)) {
            deleteTree(temp)
            throw PluginException("Could not install $id")
        }
        if (wasDisabled) File(target, ".disabled").createNewFile()
    }

    private fun validateTree(dir: File) {
        for (entry in dir.listFiles().orEmpty()) {
            if (Files.isSymbolicLink(entry.toPath())) throw PluginException("Symbolic links are not allowed (${entry.name})")
            if (entry.name !in ROOT_ALLOWED) throw PluginException("Unexpected entry '${entry.name}' in the plugin")
        }
        var total = 0L
        fun walk(folder: File, area: String) {
            for (child in folder.listFiles().orEmpty()) {
                if (Files.isSymbolicLink(child.toPath())) throw PluginException("Symbolic links are not allowed (${child.name})")
                if (child.isDirectory) {
                    walk(child, area)
                    continue
                }
                if (!NAME.matches(child.name)) throw PluginException("Forbidden file name: ${child.name}")
                val ext = child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val allowed = when (area) {
                    "assets" -> ext == "png" || ext == "svg" || ext == "xml"
                    "main" -> ext == "yml" || ext == "yaml"
                    else -> ext == "yml"
                }
                if (!allowed) throw PluginException("File type not allowed in $area/: ${child.name}")
                if (child.length() > MAX_FILE) throw PluginException("${child.name} is larger than 1 MB")
                total += child.length()
            }
        }
        for (area in listOf("assets", "main", "including")) {
            val folder = File(dir, area)
            if (folder.isDirectory) walk(folder, area)
        }
        if (total > MAX_TOTAL) throw PluginException("The plugin is larger than 10 MB")
    }

    private fun copyTree(from: File, to: File, atRoot: Boolean) {
        to.mkdirs()
        for (child in from.listFiles().orEmpty()) {
            if (atRoot && child.name == "build.sh") continue
            val target = File(to, child.name)
            if (child.isDirectory) copyTree(child, target, false) else child.copyTo(target, overwrite = true)
        }
    }

    private fun isActive(dir: File): Boolean {
        if (!dir.isDirectory || File(dir, ".disabled").exists()) return false
        return try {
            val support = PluginFormats.parseVersionSupport(File(dir, "VERSION_SUPPORT").readText(Charsets.UTF_8))
            PluginFormats.isSupported(currentVersion, support)
        } catch (e: Exception) {
            false
        }
    }

    private fun yamlFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun readGitaraVersion(): String {
        val pm = appContext.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                legacyPackageInfo(pm)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return "0.0.0-dev"
        }
        return info.versionName ?: "0.0.0-dev"
    }

    @Suppress("DEPRECATION")
    private fun legacyPackageInfo(pm: PackageManager) = pm.getPackageInfo(appContext.packageName, 0)

    private companion object {
        const val MAX_FILE = 1024L * 1024L
        const val MAX_TOTAL = 10L * 1024L * 1024L
        val NAME = Regex("^[A-Za-z0-9._-]+$")
        val ACTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$")
        val TITLE_NAME = Regex("^name:\\s*[\"']?(.+?)[\"']?\\s*$", RegexOption.MULTILINE)
        val ROOT_ALLOWED = setOf("assets", "main", "including", "VERSION", "VERSION_SUPPORT", "LICENSE", "README.md", "build.sh")
    }
}