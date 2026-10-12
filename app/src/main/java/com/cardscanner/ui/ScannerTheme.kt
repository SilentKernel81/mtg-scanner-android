package com.cardscanner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFFB83B00), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2D3), onPrimaryContainer = Color(0xFF692100),
    secondary = Color(0xFF475D6B), secondaryContainer = Color(0xFFE3EBF0), onSecondaryContainer = Color(0xFF243A48),
    tertiary = Color(0xFF765400), onTertiary = Color.White,
    background = Color(0xFFF2F4F5), onBackground = Color(0xFF1C2429),
    surface = Color.White, onSurface = Color(0xFF1C2429),
    surfaceVariant = Color(0xFFE9EDF0), onSurfaceVariant = Color(0xFF52616B),
    outline = Color(0xFF78858D), outlineVariant = Color(0xFFDCE2E6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFB),
    surfaceContainer = Color(0xFFF0F3F5), surfaceContainerHigh = Color(0xFFE9EDF0),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFAB82), onPrimary = Color(0xFF542000),
    primaryContainer = Color(0xFF73300D), onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFB4C9D6), secondaryContainer = Color(0xFF2F424E), onSecondaryContainer = Color(0xFFD5E7F2),
    tertiary = Color(0xFFEAC16A),
    background = Color(0xFF101416), onBackground = Color(0xFFE3E8EB),
    surface = Color(0xFF1B2226), onSurface = Color(0xFFE3E8EB),
    surfaceVariant = Color(0xFF303B42), onSurfaceVariant = Color(0xFFB6C3CB),
    outline = Color(0xFF8A989F), outlineVariant = Color(0xFF354149),
    surfaceContainerLowest = Color(0xFF101416), surfaceContainerLow = Color(0xFF1B2226),
    surfaceContainer = Color(0xFF232C31), surfaceContainerHigh = Color(0xFF2D373D),
)

/** Shared rounded surfaces, warm accents and readable feed typography in both system themes. */
@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    val type = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(16.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        typography = type.copy(
            titleLarge = type.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = type.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = type.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}
