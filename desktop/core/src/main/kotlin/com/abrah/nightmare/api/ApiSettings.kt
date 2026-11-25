// Copied (excerpt) from app/src/main/java/com/abrah/nightmare/api/ApiSettings.kt on 2026-11-25 (ADR-0001). Edit the desktop copy; never sync back.
package com.abrah.nightmare.api

import java.security.MessageDigest

/**
 * ⭐ The platform-free part of the mobile API settings: the port, the browser headers and the
 * constant-time token comparison.
 *
 * ⚠ The mobile `Context`/`SharedPreferences` accessors (`enabled`, `token`, `regenerate`, `cors`)
 * are deliberately **not** copied: a desktop API has no Android preferences, and where its
 * token comes from belongs to the client-authentication ticket. `matches` is pure and is
 * kept verbatim.
 */
object ApiSettings {
    /** ⚠ Not 8188 (ComfyUI) or 7860 (A1111): a client written for those must not think it found one. */
    const val PORT = 8820

    /**
     * ⭐ What a browser needs to read the answer and to send `Authorization` and a JSON body.
     * ⚠ The private-network line is Chrome's, for a page served from the internet calling a
     * service on the local machine.
     */
    val CORS_HEADERS = linkedMapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" to "Authorization, Content-Type",
        "Access-Control-Allow-Private-Network" to "true",
        "Access-Control-Max-Age" to "600",
    )

    /**
     * ⭐ `Authorization: Bearer <token>`, or `?token=` for a picture opened in a browser, or HTTP Basic with the token
     * as the password (any user name): what an Automatic1111 client sends for its `--api-auth` ([A1111]).
     */
    fun matches(expected: String, header: String?, query: String?): Boolean {
        val basic = header?.trim()?.takeIf { it.startsWith("Basic ", ignoreCase = true) }?.let {
            runCatching { String(java.util.Base64.getDecoder().decode(it.substring(6).trim()), Charsets.UTF_8) }.getOrNull()
                ?.substringAfter(':')
        }
        // ⚠ The scheme is case-insensitive (RFC 7235): `bearer x` was refused.
        val bearer = header?.trim()?.replace(BEARER, "")
        val given = basic ?: bearer?.trim()?.takeIf { it.isNotEmpty() } ?: query ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    private val BEARER = Regex("""^bearer\b\s*""", RegexOption.IGNORE_CASE)
}
