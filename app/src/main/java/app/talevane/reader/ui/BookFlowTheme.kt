package app.talevane.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val BookFlowGold = Color(0xFFD5B66F)
internal val BookFlowGoldSoft = Color(0xFFB99A55)
internal val BookFlowGraphite = Color(0xFF151513)
internal val BookFlowPanel = Color(0xFF1D1D1A)
internal val BookFlowPageSurface = Color(0xFF25241F)
internal val BookFlowPageText = Color(0xFFE8E2D4)
internal val BookFlowMuted = Color(0xFFA9A394)

private val BookFlowColors = darkColorScheme(
    primary = BookFlowGold,
    onPrimary = Color(0xFF241D0F),
    primaryContainer = Color(0xFF3A3020),
    onPrimaryContainer = Color(0xFFF5DDA3),
    secondary = BookFlowGoldSoft,
    onSecondary = Color(0xFF211B10),
    secondaryContainer = Color(0xFF332B1D),
    onSecondaryContainer = Color(0xFFEBD29B),
    background = BookFlowGraphite,
    onBackground = BookFlowPageText,
    surface = BookFlowPanel,
    onSurface = BookFlowPageText,
    surfaceVariant = Color(0xFF292823),
    onSurfaceVariant = BookFlowMuted,
    outline = Color(0xFF777164),
    outlineVariant = Color(0xFF49463E)
)

@Composable
internal fun BookFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BookFlowColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
