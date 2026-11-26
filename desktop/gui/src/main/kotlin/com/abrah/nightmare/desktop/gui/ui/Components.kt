package com.abrah.nightmare.desktop.gui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * Thin wrappers around the current Material widgets. They add no behaviour of
 * their own: every visual decision comes from [AppTheme]. Replacing the widget
 * library later means editing only this file and [ThemeKt], not each shell.
 */

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = AppTheme.colors.primary,
    contentColor: Color = AppTheme.colors.onPrimary,
    shape: Shape = RoundedCornerShape(AppTheme.dims.cardCorner),
    fontSize: TextUnit = AppTheme.typography.bodySmall,
    fontWeight: FontWeight? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = backgroundColor,
            contentColor = contentColor,
        ),
    ) {
        AppText(text, color = contentColor, fontSize = fontSize, fontWeight = fontWeight)
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.surface,
    corner: Dp = AppTheme.dims.cardCorner,
    padding: Dp = AppTheme.dims.spaceXl,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(color, RoundedCornerShape(corner))
            .padding(padding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.ink,
    fontSize: TextUnit = AppTheme.typography.body,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = maxLines,
    )
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label,
        singleLine = singleLine,
        maxLines = maxLines,
    )
}
