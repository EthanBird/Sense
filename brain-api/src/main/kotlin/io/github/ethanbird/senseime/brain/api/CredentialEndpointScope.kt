package io.github.ethanbird.senseime.brain.api

import java.net.URI
import java.util.Locale

/**
 * Canonical endpoint identity used only to decide whether an already-saved
 * credential may be preserved.
 *
 * Schemes and host names are case-insensitive. URL paths are not, so their
 * spelling and case must remain exact to avoid sharing a key across distinct
 * tenant endpoints.
 */
object CredentialEndpointScope {
    fun normalize(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (
            uri == null ||
            !uri.isAbsolute ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null
        ) {
            return trimmed.trimEnd('/')
        }

        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val port = when {
            scheme == "https" && uri.port == 443 -> -1
            scheme == "http" && uri.port == 80 -> -1
            else -> uri.port
        }
        val renderedHost = if (':' in host && !host.startsWith('[')) {
            "[$host]"
        } else {
            host
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return buildString {
            append(scheme)
            append("://")
            append(renderedHost)
            if (port >= 0) {
                append(':')
                append(port)
            }
            append(path)
            uri.rawQuery?.let {
                append('?')
                append(it)
            }
            uri.rawFragment?.let {
                append('#')
                append(it)
            }
        }
    }
}
