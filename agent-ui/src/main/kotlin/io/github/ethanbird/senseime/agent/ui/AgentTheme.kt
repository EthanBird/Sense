package io.github.ethanbird.senseime.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class AgentPalette(
    val background: Color,
    val secondaryBackground: Color,
    val composerBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val userBubble: Color,
    val toolCapsule: Color,
    val toolSurface: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val codeBackground: Color,
    val codeText: Color,
    val inlineCodeBackground: Color,
    val inlineCodeText: Color,
    val danger: Color,
)

private val LightAgentPalette = AgentPalette(
    background = Color.White,
    secondaryBackground = Color(0xFFF2F2F7),
    composerBackground = Color.White,
    textPrimary = Color(0xFF111113),
    textSecondary = Color(0xFF6D6D72),
    textTertiary = Color(0xFFA6A6AB),
    userBubble = Color(0x1E787880),
    toolCapsule = Color(0xFFF4F4F7),
    toolSurface = Color.White,
    border = Color(0x1A6D6D72),
    accent = Color(0xFF34C86F),
    accentSoft = Color(0x2434C86F),
    codeBackground = Color(0xFF101012),
    codeText = Color(0xFF72E78F),
    inlineCodeBackground = Color(0xFFF2F2F7),
    inlineCodeText = Color(0xFFFF9500),
    danger = Color(0xFFFF3B30),
)

private val DarkAgentPalette = AgentPalette(
    background = Color.Black,
    secondaryBackground = Color(0xFF26262A),
    composerBackground = Color(0xFF2C2C30),
    textPrimary = Color.White,
    textSecondary = Color(0xFFA9A9AE),
    textTertiary = Color(0xFF6D6D72),
    userBubble = Color(0xFF2F3A5C),
    toolCapsule = Color(0xFF28282C),
    toolSurface = Color(0xFF3A3A3F),
    border = Color(0x33FFFFFF),
    accent = Color(0xFF48DD82),
    accentSoft = Color(0x2948DD82),
    codeBackground = Color(0xFF262626),
    codeText = Color(0xFF8CF38C),
    inlineCodeBackground = Color(0xFF34343A),
    inlineCodeText = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
)

val LocalAgentPalette = staticCompositionLocalOf { LightAgentPalette }

@Composable
fun SenseAgentTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkAgentPalette else LightAgentPalette
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = palette.accent,
                background = palette.background,
                surface = palette.toolSurface,
                onBackground = palette.textPrimary,
                onSurface = palette.textPrimary,
            )
        } else {
            lightColorScheme(
                primary = palette.accent,
                background = palette.background,
                surface = palette.toolSurface,
                onBackground = palette.textPrimary,
                onSurface = palette.textPrimary,
            )
        },
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAgentPalette provides palette,
                content = content,
            )
        },
    )
}
