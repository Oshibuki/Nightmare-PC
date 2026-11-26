package com.abrah.nightmare.desktop.gui.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens shared by the desktop shells. Composables read colours,
 * spacing and type sizes from here instead of repeating literals, so a future
 * component-library swap is confined to this file and [ComponentsKt].
 */
object AppTheme {
    val colors: AppColors = AppColors
    val dims: AppDims = AppDims
    val typography: AppTypography = AppTypography
}

/** Palette extracted from the existing default/chat/advanced shells. */
object AppColors {
    val canvas = Color(0xFFF5F7FB)
    val surface = Color.White
    val previewSurface = Color(0xFF1E2431)
    val cardSurface = Color(0xFF2E3A52)
    val logSurface = Color(0xFF141A26)

    val primary = Color(0xFF3F6FC7)
    val onPrimary = Color.White
    val chipSurface = Color(0xFFF8FAFD)

    val ink = Color(0xFF243049)
    val muted = Color(0xFF66738C)
    val subtle = Color(0xFF94A0B5)
    val onCard = Color.White
    val onCardMuted = Color(0xFFCFD8EA)
    val onCardFaint = Color(0xFF9FADC7)
    val logInk = Color(0xFFB8C6DE)

    val success = Color(0xFF9BE0A6)
    val successInk = Color(0xFF2E7D4F)
    val successSurface = Color(0xFFE7F5EA)

    val danger = Color(0xFFB23B3B)
    val dangerInk = Color(0xFF9B2C2C)
    val dangerSurface = Color(0xFFFDECEA)
    val neutral = Color(0xFF6B7280)

    val toolSurface = Color(0xFFEDF2FA)
    val askSurface = Color(0xFFFFF4DE)
    val askInk = Color(0xFF8A6A22)
    val askSelected = Color(0xFFE2C678)
}

/** Spacing and corner radii; keep the existing rhythm of the shells. */
object AppDims {
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 24.dp
    val cardCorner = 12.dp
    val chipCorner = 20.dp
}

/** Type scale used by the shared result and chat components. */
object AppTypography {
    val caption: TextUnit = 11.sp
    val bodySmall: TextUnit = 12.sp
    val body: TextUnit = 13.sp
    val title: TextUnit = 20.sp
}
