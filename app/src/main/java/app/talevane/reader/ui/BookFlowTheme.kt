package app.talevane.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val BookFlowGold = Color(0xFFD6B45F)
internal val BookFlowGoldSoft = Color(0xFFB9984F)
internal val BookFlowGraphite = Color(0xFF0E0F12)
internal val BookFlowPanel = Color(0xFF17191F)
internal val BookFlowPageSurface = Color(0xFF202127)
internal val BookFlowPageText = Color(0xFFF3F0E8)
internal val BookFlowMuted = Color(0xFF9C9AA2)

private val BookFlowColors = darkColorScheme(
    primary = BookFlowGold,
    onPrimary = Color(0xFF1E190E),
    primaryContainer = Color(0xFF2B2518),
    onPrimaryContainer = Color(0xFFF4D98E),
    secondary = BookFlowGoldSoft,
    onSecondary = Color(0xFF1F1A0F),
    secondaryContainer = Color(0xFF26231D),
    onSecondaryContainer = Color(0xFFE9D39A),
    background = BookFlowGraphite,
    onBackground = BookFlowPageText,
    surface = BookFlowPanel,
    onSurface = BookFlowPageText,
    surfaceVariant = BookFlowPageSurface,
    onSurfaceVariant = BookFlowMuted,
    outline = Color(0xFF555762),
    outlineVariant = Color(0xFF2E3038)
)

@Composable
internal fun BookFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BookFlowColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
