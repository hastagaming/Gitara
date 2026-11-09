package io.gitara.plugins

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.io.File

class PluginException(message: String) : Exception(message)

data class PluginCommand(
    val id: String,
    val name: String,
    val description: String,
    val script: String,
    val shell: String,
    val timeoutMinutes: Int
)

data class PluginDependency(
    val id: String,
    val minVersion: String,
    val repo: String
)

data class PluginMeta(
    val id: String,
    val title: String,
    val version: String,
    val supported: List<String>,
    val readme: String,
    val dependencies: List<PluginDependency>,
    val actions: Int,
    val templates: Int,
    val commands: Int
)

data class PluginRepository(
    val name: String,
    val url: String,
    val branch: String,
    val builtin: Boolean
)

data class CatalogPlugin(
    val id: String,
    val meta: PluginMeta?,
    val error: String?,
    val dir: File
)

object PluginFormats {
    val ID = Regex("^[a-z0-9][a-z0-9-]{0,47}$")
    private val COMMAND_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$")
    private val VERSION = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$")
    private val SUPPORT_ITEM = Regex("^-\\s+(v\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)$")
    private val TITLE = Regex("^#\\s+(.+?)\\s*$", RegexOption.MULTILINE)
    private val SHELLS = setOf("sh", "ash", "bash", "python3", "node")
    private const val MAX_TEXT = 256 * 1024

    fun parseVersion(text: String): String {
        val value = text.trim()
        if (!VERSION.matches(value)) throw PluginException("VERSION must look like 1.2.3 or 1.2.3-rc.1")
        return value.removePrefix("v")
    }

    fun parseVersionSupport(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines[0] != "Gitara version [") {
            throw PluginException("VERSION_SUPPORT must start with: Gitara version [")
        }
        val versions = ArrayList<String>()
        var closed = false
        for (line in lines.drop(1)) {
            if (closed) throw PluginException("Nothing may follow the closing ] in VERSION_SUPPORT")
            if (line == "]") {
                closed = true
                continue
            }
            val match = SUPPORT_ITEM.matchEntire(line)
                ?: throw PluginException("Unexpected line in VERSION_SUPPORT: $line")
            versions.add(match.groupValues[1])
        }
        if (!closed) throw PluginException("VERSION_SUPPORT is missing the closing ]")
        if (versions.isEmpty()) throw PluginException("VERSION_SUPPORT must list at least one Gitara version")
        return versions
    }

    fun isSupported(current: String, supported: List<String>): Boolean {
        if (current.endsWith("-dev")) return true
        val normalized = current.removePrefix("v")
        return supported.any { it.removePrefix("v") == normalized }
    }

    fun compareVersions(a: String, b: String): Int {
        val x = VERSION.matchEntire(a.trim())
        val y = VERSION.matchEntire(b.trim())
        if (x == null || y == null) return a.compareTo(b)
        for (i in 1..3) {
            val c = (x.groupValues[i].toLongOrNull() ?: 0L).compareTo(y.groupValues[i].toLongOrNull() ?: 0L)
            if (c != 0) return c
        }
        val sx = x.groupValues[4]
        val sy = y.groupValues[4]
        return when {
            sx.isEmpty() && sy.isEmpty() -> 0
            sx.isEmpty() -> 1
            sy.isEmpty() -> -1
            else -> compareSuffix(sx, sy)
        }
    }

    private fun compareSuffix(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until minOf(pa.size, pb.size)) {
            val na = pa[i].toLongOrNull()
            val nb = pb[i].toLongOrNull()
            val c = if (na != null && nb != null) na.compareTo(nb) else pa[i].compareTo(pb[i])
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    fun parseDependency(id: String, text: String): PluginDependency {
        val doc = try {
            Load(LoadSettings.builder().build()).loadFromString(text)
        } catch (e: YamlEngineException) {
            throw PluginException("including/$id.yml is not valid YAML")
        }
        val map: Map<*, *> = (doc as? Map<*, *>) ?: emptyMap<Any, Any>()
        var version = ""
        var repo = ""
        for ((key, value) in map) {
            when (key.toString()) {
                "version" -> version = value?.toString().orEmpty().trim().removePrefix("v")
                "repo" -> repo = value?.toString().orEmpty().trim()
                else -> throw PluginException("including/$id.yml may only contain version: and repo:")
            }
        }
        if (version.isNotEmpty() && !VERSION.matches(version)) {
            throw PluginException("including/$id.yml has an invalid version (use 1.2.3)")
        }
        if (repo.isNotEmpty() && !repo.startsWith("https://")) {
            throw PluginException("including/$id.yml needs an https:// repo")
        }
        return PluginDependency(id, version, repo)
    }

    fun readMeta(dir: File): PluginMeta {
        val id = dir.name
        if (!ID.matches(id)) throw PluginException("'$id' is not a valid plugin name")
        for (name in listOf("VERSION", "VERSION_SUPPORT", "README.md")) {
            if (!File(dir, name).isFile) throw PluginException("$name is missing")
        }
        if (!File(dir, "main").isDirectory) throw PluginException("The main folder is missing")

        val version = parseVersion(readText(File(dir, "VERSION")))
        val supported = parseVersionSupport(readText(File(dir, "VERSION_SUPPORT")))
        val readme = readText(File(dir, "README.md"))
        val title = TITLE.find(readme)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: id
        val dependencies = yamlFiles(File(dir, "including")).map {
            parseDependency(it.name.substringBeforeLast('.'), readText(it))
        }
        val actions = File(dir, "main/actions").listFiles()?.count { d ->
            d.isDirectory && (File(d, "action.yml").isFile || File(d, "action.yaml").isFile)
        } ?: 0
        return PluginMeta(
            id = id,
            title = title,
            version = version,
            supported = supported,
            readme = readme,
            dependencies = dependencies,
            actions = actions,
            templates = yamlFiles(File(dir, "main/templates")).size,
            commands = yamlFiles(File(dir, "main/commands")).size
        )
    }

    fun readCommand(file: File): PluginCommand? {
        val id = file.name.substringBeforeLast('.')
        if (!COMMAND_ID.matches(id)) return null
        val doc = try {
            Load(LoadSettings.builder().build()).loadFromString(readText(file))
        } catch (e: YamlEngineException) {
            return null
        }
        val raw = doc as? Map<*, *> ?: return null
        val map = LinkedHashMap<String, Any?>()
        for ((key, value) in raw) map[key.toString()] = value
        val script = map["run"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val shell = map["shell"]?.toString() ?: "sh"
        if (shell !in SHELLS) return null
        val timeout = map["timeout-minutes"]?.toString()?.toDoubleOrNull()?.toInt() ?: 30
        if (timeout < 1 || timeout > 720) return null
        return PluginCommand(
            id = id,
            name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id,
            description = map["description"]?.toString().orEmpty(),
            script = script,
            shell = shell,
            timeoutMinutes = timeout
        )
    }

    private fun yamlFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun readText(file: File): String {
        val buffer = ByteArray(minOf(file.length(), MAX_TEXT.toLong()).toInt())
        file.inputStream().use { input ->
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
        }
        return String(buffer, Charsets.UTF_8)
    }
}