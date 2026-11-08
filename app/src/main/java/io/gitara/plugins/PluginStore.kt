package io.gitara.plugins

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.gitara.box.deleteTree
import io.gitara.workflow.JobDef
import io.gitara.workflow.StepDef
import io.gitara.workflow.WorkflowDefinition
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files

data class PluginInfo(
    val id: String,
    val manifest: PluginManifest?,
    val error: String?,
    val enabled: Boolean,
    val source: String
)

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

    fun list(): List<PluginInfo> {
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return emptyList()
        return dirs.sortedBy { it.name }.map { readInfo(it) }
    }

    fun commands(): List<PluginCommandRef> = list().flatMap { info ->
        val manifest = info.manifest
        if (!info.enabled || manifest == null) {
            emptyList()
        } else {
            manifest.commands.map { PluginCommandRef(info.id, manifest.name, it) }
        }
    }

    fun templates(): List<PluginTemplate> {
        val out = ArrayList<PluginTemplate>()
        for (info in list()) {
            val manifest = info.manifest
            if (!info.enabled || manifest == null) continue
            val files = File(root, "${info.id}/templates").listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) && it.length() <= MAX_TEMPLATE_BYTES }
                ?.sortedBy { it.name }
                ?: continue
            for (file in files) {
                val content = try {
                    file.readText(Charsets.UTF_8)
                } catch (e: IOException) {
                    continue
                }
                val title = TITLE.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: file.nameWithoutExtension
                out.add(PluginTemplate(manifest.name, title, file.nameWithoutExtension, content))
            }
        }
        return out
    }

    fun actionFile(reference: String): File? {
        val slash = reference.indexOf('/')
        if (slash <= 0) return null
        val pluginId = reference.substring(0, slash)
        val actionId = reference.substring(slash + 1)
        if (!PLUGIN_ID.matches(pluginId) || !ACTION_ID.matches(actionId)) return null
        val dir = File(root, pluginId)
        if (!dir.isDirectory || File(dir, ".disabled").exists()) return null
        val actionDir = File(dir, "actions/$actionId")
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

    fun hasManifest(directory: File): Boolean = manifestFile(directory) != null

    fun installFrom(directory: File, source: String): PluginManifest {
        val file = manifestFile(directory) ?: throw PluginException("No plugin.yml found in this folder")
        val manifest = PluginManifestParser.parse(file.readText(Charsets.UTF_8))
        checkCompatible(manifest)
        if (treeSize(directory) > MAX_PLUGIN_BYTES) throw PluginException("The plugin is larger than 20 MB")

        root.mkdirs()
        val temp = File(root, ".install-${manifest.id}")
        deleteTree(temp)
        copyTree(directory, temp)
        File(temp, ".source").writeText(source, Charsets.UTF_8)

        val target = File(root, manifest.id)
        val wasDisabled = File(target, ".disabled").exists()
        deleteTree(target)
        if (!temp.renameTo(target)) {
            deleteTree(temp)
            throw PluginException("Could not install the plugin")
        }
        if (wasDisabled) File(target, ".disabled").createNewFile()
        return manifest
    }

    fun installFromGit(url: String, username: String, token: String, onStage: (String) -> Unit): PluginManifest {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            throw PluginException("Use an https:// URL")
        }
        val work = File(appContext.cacheDir, "plugin-clone-${System.nanoTime()}")
        try {
            onStage("Cloning…")
            val command = Git.cloneRepository().setURI(trimmed).setDirectory(work)
            if (token.isNotEmpty()) {
                command.setCredentialsProvider(UsernamePasswordCredentialsProvider(username.ifEmpty { "token" }, token))
            }
            val git = command.call()
            git.repository.close()
            onStage("Installing…")
            return installFrom(work, trimmed)
        } catch (e: PluginException) {
            throw e
        } catch (e: Exception) {
            throw PluginException("Clone failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            deleteTree(work)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (!PLUGIN_ID.matches(id)) return
        val dir = File(root, id)
        if (!dir.isDirectory) return
        val marker = File(dir, ".disabled")
        if (enabled) marker.delete() else marker.createNewFile()
    }

    fun uninstall(id: String) {
        if (!PLUGIN_ID.matches(id)) return
        deleteTree(File(root, id))
    }

    private fun readInfo(dir: File): PluginInfo {
        val source = File(dir, ".source").takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim().orEmpty()
        val enabled = !File(dir, ".disabled").exists()
        val file = manifestFile(dir)
        if (file == null) return PluginInfo(dir.name, null, "plugin.yml is missing", enabled, source)
        return try {
            PluginInfo(dir.name, PluginManifestParser.parse(file.readText(Charsets.UTF_8)), null, enabled, source)
        } catch (e: Exception) {
            PluginInfo(dir.name, null, e.message ?: "Invalid plugin", enabled, source)
        }
    }

    private fun manifestFile(directory: File): File? =
        listOf("plugin.yml", "plugin.yaml").map { File(directory, it) }.firstOrNull { it.isFile }

    private fun checkCompatible(manifest: PluginManifest) {
        if (manifest.minGitara.isEmpty()) return
        val required = versionParts(manifest.minGitara) ?: return
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            legacyPackageInfo(pm)
        }
        val name = info.versionName ?: return
        if (name.contains("dev")) return
        val current = versionParts(name) ?: return
        for (i in 0..2) {
            if (required[i] != current[i]) {
                if (required[i] > current[i]) {
                    throw PluginException("This plugin needs Gitara ${manifest.minGitara} or newer (you have $name)")
                }
                return
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyPackageInfo(pm: PackageManager) = pm.getPackageInfo(appContext.packageName, 0)

    private fun versionParts(text: String): List<Int>? {
        val match = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(text.trim()) ?: return null
        return listOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toIntOrNull() ?: 0,
            match.groupValues[3].toIntOrNull() ?: 0
        )
    }

    private fun treeSize(dir: File): Long {
        var total = 0L
        for (child in dir.listFiles().orEmpty()) {
            if (child.name == ".git" || Files.isSymbolicLink(child.toPath())) continue
            total += if (child.isDirectory) treeSize(child) else child.length()
        }
        return total
    }

    private fun copyTree(from: File, to: File) {
        to.mkdirs()
        for (child in from.listFiles().orEmpty()) {
            if (child.name == ".git" || Files.isSymbolicLink(child.toPath())) continue
            val target = File(to, child.name)
            if (child.isDirectory) copyTree(child, target) else child.copyTo(target, overwrite = true)
        }
    }

    private companion object {
        const val MAX_PLUGIN_BYTES = 20L * 1024L * 1024L
        const val MAX_TEMPLATE_BYTES = 256L * 1024L
        val PLUGIN_ID = Regex("^[a-z0-9][a-z0-9-]{0,47}$")
        val ACTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$")
        val TITLE = Regex("^name:\\s*[\"']?(.+?)[\"']?\\s*$", RegexOption.MULTILINE)
    }
}