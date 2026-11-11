package io.gitara.plugins

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.io.File
import java.io.IOException

data class PluginHook(
    val id: String,
    val name: String,
    val event: String,
    val operations: Set<String>,
    val script: String,
    val shell: String,
    val timeoutMinutes: Int
)

data class PluginTheme(
    val pluginId: String,
    val id: String,
    val name: String,
    val light: Map<String, Int>,
    val dark: Map<String, Int>
) {
    val key: String get() = "$pluginId/$id"
}

object PluginExtensionFormats {
    val EVENTS = setOf("repo-opened", "after-operation", "workflow-finished")
    val OPERATIONS = setOf("pull", "push", "fetch")
    val PLACES = setOf("plugins", "repo-card", "repo-actions")
    val THEME_KEYS = setOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
        "secondary", "onSecondary", "background", "onBackground",
        "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
        "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
        "surfaceContainerHigh", "surfaceContainerHighest",
        "outline", "outlineVariant", "error", "onError"
    )

    private val COLOR = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
    private val FILE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$")
    private val SHELLS = setOf("sh", "ash", "bash", "python3", "node")
    private const val MAX_BYTES = 128 * 1024

    fun places(file: File): Set<String> {
        val declared = loadMap(file)?.get("places")
        return setOf("plugins") + stringSet(declared).filter { it in PLACES }
    }

    fun readHook(file: File): PluginHook? {
        val id = file.name.substringBeforeLast('.')
        if (!FILE_ID.matches(id)) return null
        val map = loadMap(file) ?: return null
        val event = map["on"]?.toString()?.trim() ?: return null
        if (event !in EVENTS) return null
        val script = map["run"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val shell = map["shell"]?.toString() ?: "sh"
        if (shell !in SHELLS) return null
        val timeout = map["timeout-minutes"]?.toString()?.toDoubleOrNull()?.toInt() ?: 5
        if (timeout < 1 || timeout > 120) return null
        return PluginHook(
            id = id,
            name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id,
            event = event,
            operations = stringSet(map["operations"]).filter { it in OPERATIONS }.toSet(),
            script = script,
            shell = shell,
            timeoutMinutes = timeout
        )
    }

    fun readTheme(pluginId: String, file: File): PluginTheme? {
        val id = file.name.substringBeforeLast('.')
        if (!FILE_ID.matches(id)) return null
        val map = loadMap(file) ?: return null
        val light = colors(map["light"])
        val dark = colors(map["dark"])
        if (light.isEmpty() && dark.isEmpty()) return null
        return PluginTheme(
            pluginId = pluginId,
            id = id,
            name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id,
            light = light,
            dark = dark
        )
    }

    private fun colors(value: Any?): Map<String, Int> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for ((key, item) in raw) {
            val name = key.toString()
            if (name !in THEME_KEYS) continue
            val text = item?.toString()?.trim() ?: continue
            if (!COLOR.matches(text)) continue
            val hex = text.removePrefix("#")
            val argb = if (hex.length == 6) 0xFF000000L or hex.toLong(16) else hex.toLong(16)
            out[name] = argb.toInt()
        }
        return out
    }

    private fun stringSet(value: Any?): Set<String> = when (value) {
        null -> emptySet()
        is List<*> -> value.mapNotNull { it?.toString()?.trim() }.toSet()
        else -> setOf(value.toString().trim())
    }

    private fun loadMap(file: File): Map<String, Any?>? {
        if (file.length() > MAX_BYTES) return null
        val doc = try {
            Load(LoadSettings.builder().build()).loadFromString(file.readText(Charsets.UTF_8))
        } catch (e: YamlEngineException) {
            return null
        } catch (e: IOException) {
            return null
        }
        val raw = doc as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in raw) out[key.toString()] = value
        return out
    }
}