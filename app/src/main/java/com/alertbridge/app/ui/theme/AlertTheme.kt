package com.alertbridge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AlertBridge 디자인 토큰.
 * - 강조색은 딥 틸 하나. 빨강은 119·인명 위험에만 쓴다.
 * - 간격은 4의 배수 (4/8/12/16/24/32/48).
 * - 글자 크기는 12/14/16/20/28/36 여섯 단계.
 */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

/** 의미 색. Material 색 체계 밖의 성공·주의 색을 둔다. */
@Immutable
data class SemanticColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDanger: Color,
)

private val LightSemantic = SemanticColors(
    success = Color(0xFF1E7A45), successContainer = Color(0xFFDDF2E4),
    warning = Color(0xFF9A5B00), warningContainer = Color(0xFFFFF0D4),
    danger = Color(0xFFC7322B), dangerContainer = Color(0xFFFCE5E2), onDanger = Color.White,
)
private val DarkSemantic = SemanticColors(
    success = Color(0xFF6FD69A), successContainer = Color(0xFF173A26),
    warning = Color(0xFFF2B65C), warningContainer = Color(0xFF3D2C10),
    danger = Color(0xFFFF7D72), dangerContainer = Color(0xFF4A1A17), onDanger = Color(0xFF2B0503),
)

val LocalSemantic = staticCompositionLocalOf { LightSemantic }

private val Light = lightColorScheme(
    primary = Color(0xFF0E6B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEE8), onPrimaryContainer = Color(0xFF00382F),
    secondaryContainer = Color(0xFFE9EEF0), onSecondaryContainer = Color(0xFF1C2328),
    background = Color(0xFFF6F5F2), onBackground = Color(0xFF15181C),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF15181C),
    surfaceVariant = Color(0xFFEFEDE8), onSurfaceVariant = Color(0xFF59606A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFAF8), surfaceContainer = Color(0xFFF2F1ED),
    surfaceContainerHigh = Color(0xFFEDEBE6), surfaceContainerHighest = Color(0xFFE8E6E0),
    outline = Color(0xFFD9D5CD), outlineVariant = Color(0xFFE7E4DE),
    error = Color(0xFFC7322B), onError = Color.White, errorContainer = Color(0xFFFCE5E2), onErrorContainer = Color(0xFF5A0E0A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF63D3C0), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0F4A41), onPrimaryContainer = Color(0xFFD5EEE8),
    secondaryContainer = Color(0xFF263036), onSecondaryContainer = Color(0xFFE2E8EB),
    background = Color(0xFF0F1214), onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF171B1E), onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF20262A), onSurfaceVariant = Color(0xFFA3ABB2),
    surfaceContainerLowest = Color(0xFF0B0E10), surfaceContainerLow = Color(0xFF14181A), surfaceContainer = Color(0xFF1B2023),
    surfaceContainerHigh = Color(0xFF22282C), surfaceContainerHighest = Color(0xFF2A3135),
    outline = Color(0xFF353D42), outlineVariant = Color(0xFF2A3135),
    error = Color(0xFFFF7D72), onError = Color(0xFF2B0503), errorContainer = Color(0xFF4A1A17), onErrorContainer = Color(0xFFFFDAD5),
)

private val Type = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val Shape = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AlertBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalSemantic provides if (dark) DarkSemantic else LightSemantic) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = Shape, content = content)
    }
}

object AB {
    val semantic: SemanticColors
        @Composable get() = LocalSemantic.current
}
