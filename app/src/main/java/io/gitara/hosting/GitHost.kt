package io.gitara.hosting

import java.net.URI
import java.net.URISyntaxException

enum class GitHost(
    val displayName: String,
    private val domains: List<String>,
    private val hostPrefixes: List<String> = emptyList()
) {
    GITHUB("GitHub", listOf("github.com")),
    GITLAB("GitLab", listOf("gitlab.com"), listOf("gitlab.")),
    BITBUCKET("Bitbucket", listOf("bitbucket.org", "bitbucket.com"), listOf("bitbucket.")),
    GITEA("Gitea", listOf("gitea.com"), listOf("gitea.", "forgejo.")),
    CODEBERG("Codeberg", listOf("codeberg.org")),
    SOURCEHUT("SourceHut", listOf("git.sr.ht", "sr.ht")),
    GENERIC("Git", emptyList());

    private fun matches(host: String): Boolean =
        domains.any { host == it || host.endsWith(".$it") } ||
            hostPrefixes.any { host.startsWith(it) }

    companion object {
        private val scpLike = Regex("^[\\w.-]+@([\\w.-]+):(?!//).+$")

        fun hostOf(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            val scp = scpLike.matchEntire(trimmed)
            if (scp != null) return scp.groupValues[1].lowercase()
            val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return try {
                URI(candidate).host?.lowercase()
            } catch (e: URISyntaxException) {
                null
            }
        }

        fun fromUrl(url: String): GitHost {
            val host = hostOf(url) ?: return GENERIC
            return entries.firstOrNull { it != GENERIC && it.matches(host) } ?: GENERIC
        }
    }
}