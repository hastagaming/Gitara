package io.gitara.plugins

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

class PluginException(message: String) : Exception(message)

data class PluginCommand(
    val id: String,
    val name: String,
    val description: String,
    val script: String,
    val shell: String,
    val timeoutMinutes: Int
)

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val minGitara: String,
    val commands: List<PluginCommand>
)

object PluginManifestParser {
    private val idPattern = Regex("^[a-z0-9][a-z0-9-]{0,47}$")
    private val commandIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$")
    private val versionPattern = Regex("^\\d+(\\.\\d+){0,2}$")
    private val shells = setOf("sh", "ash", "bash", "python3", "node")

    fun parse(text: String): PluginManifest {
        val doc = try {
            Load(LoadSettings.builder().build()).loadFromString(text)
        } catch (e: YamlEngineException) {
            throw PluginException("Invalid YAML: " + (e.message ?: "").take(300))
        }
        val root = asMap(doc) ?: throw PluginException("plugin.yml must be a YAML mapping")

        val id = scalar(root["id"], "id") ?: throw PluginException("'id' is required")
        if (!idPattern.matches(id)) throw PluginException("'id' must use lowercase letters, digits and dashes (max 48)")

        val minGitara = scalar(root["min-gitara"], "min-gitara").orEmpty()
        if (minGitara.isNotEmpty() && !versionPattern.matches(minGitara)) {
            throw PluginException("'min-gitara' must look like 1.2.0")
        }

        val commands = ArrayList<PluginCommand>()
        val rawCommands = root["commands"]
        if (rawCommands != null) {
            val list = rawCommands as? List<*> ?: throw PluginException("'commands' must be a list")
            for ((index, raw) in list.withIndex()) {
                val where = "commands[${index + 1}]"
                val m = asMap(raw) ?: throw PluginException("$where must be a mapping")
                val commandId = scalar(m["id"], "$where.id") ?: throw PluginException("$where needs an 'id'")
                if (!commandIdPattern.matches(commandId)) throw PluginException("$where has an invalid id '$commandId'")
                if (commands.any { it.id == commandId }) throw PluginException("Duplicate command id '$commandId'")
                val script = scalar(m["run"], "$where.run")
                if (script.isNullOrBlank()) throw PluginException("$where needs a non-empty 'run'")
                val shell = scalar(m["shell"], "$where.shell") ?: "sh"
                if (shell !in shells) throw PluginException("$where.shell must be one of ${shells.joinToString(", ")}")
                val timeout = scalar(m["timeout-minutes"], "$where.timeout-minutes")?.toIntOrNull() ?: 30
                if (timeout < 1 || timeout > 720) throw PluginException("$where.timeout-minutes must be between 1 and 720")
                commands.add(
                    PluginCommand(
                        id = commandId,
                        name = scalar(m["name"], "$where.name")?.ifBlank { null } ?: commandId,
                        description = scalar(m["description"], "$where.description").orEmpty(),
                        script = script,
                        shell = shell,
                        timeoutMinutes = timeout
                    )
                )
            }
        }

        return PluginManifest(
            id = id,
            name = scalar(root["name"], "name")?.ifBlank { null } ?: id,
            version = scalar(root["version"], "version").orEmpty().ifBlank { "0.0.0" },
            author = scalar(root["author"], "author").orEmpty(),
            description = scalar(root["description"], "description").orEmpty(),
            minGitara = minGitara,
            commands = commands
        )
    }

    private fun asMap(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *>) return null
        val out = LinkedHashMap<String, Any?>()
        for ((key, item) in value) out[key.toString()] = item
        return out
    }

    private fun scalar(value: Any?, where: String): String? = when (value) {
        null -> null
        is Map<*, *>, is List<*> -> throw PluginException("'$where' must be a plain value")
        is Double -> if (value == Math.rint(value)) value.toLong().toString() else value.toString()
        else -> value.toString()
    }
}