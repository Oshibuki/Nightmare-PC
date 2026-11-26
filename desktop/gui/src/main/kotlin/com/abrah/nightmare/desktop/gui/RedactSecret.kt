package com.abrah.nightmare.desktop.gui

/** Replaces a literal key wherever it appears in provider text; never echoes it (ADR-0012). */
fun redactSecret(text: String, key: String): String =
    if (key.isBlank()) text else text.replace(key, "[redacted]")
