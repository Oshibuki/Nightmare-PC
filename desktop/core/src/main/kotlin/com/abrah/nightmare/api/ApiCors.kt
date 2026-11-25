// Copied (excerpt) from app/src/main/java/com/abrah/nightmare/api/NightmareApi.kt on 2026-11-25 (ADR-0001). Edit the desktop copy; never sync back.
package com.abrah.nightmare.api

/**
 * ⭐ "Allow browser pages" ([ApiSettings.CORS_HEADERS]): the CORS headers on an answer, and true
 * when [req] was a preflight, answered here with 204 before the token check (a browser sends no
 * `Authorization` with it). Off: nothing changes, and an `OPTIONS` meets the token check like
 * any call.
 *
 * ⚠ The rest of mobile `NightmareApi` (`route`, `run`, `info`) is not copied: it needs
 * `ApiCore`, `Graph`/`NodeType` and the Android `Context`. Only this pure shell helper moved.
 */
fun corsPreflight(req: MiniHttp.Request, res: MiniHttp.Response, cors: Boolean): Boolean {
    if (!cors) return false
    res.extra.putAll(ApiSettings.CORS_HEADERS)
    if (req.method != "OPTIONS") return false
    res.send(204, "text/plain", ByteArray(0))
    return true
}
