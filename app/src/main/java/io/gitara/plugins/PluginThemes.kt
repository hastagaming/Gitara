package io.gitara.plugins

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object PluginThemes {
    private val state = MutableStateFlow<PluginTheme?>(null)
    val active: StateFlow<PluginTheme?> = state.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (!loaded) reload(context)
    }

    fun reload(context: Context) {
        val app = context.applicationContext
        val key = selectedKey(app)
        state.value = if (key.isEmpty()) null else PluginStore(app).themes().firstOrNull { it.key == key }
        loaded = true
    }

    fun select(context: Context, theme: PluginTheme?) {
        val file = selectionFile(context.applicationContext)
        file.parentFile?.mkdirs()
        if (theme == null) file.delete() else file.writeText(theme.key, Charsets.UTF_8)
        state.value = theme
        loaded = true
    }

    fun selectedKey(context: Context): String {
        val file = selectionFile(context.applicationContext)
        return if (file.isFile) file.readText(Charsets.UTF_8).trim() else ""
    }

    private fun selectionFile(context: Context): File = File(context.filesDir, "home/theme.txt")
}