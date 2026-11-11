package com.alertbridge.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 어떡car노 디자인 토큰. 차량 화면(Pleos Connect, 가로 2560×1440, 160dpi) 전용이다.
 * - 어두운 테마 하나. 밤에는 어두운 화면이 필수이고, 낮에도 대비가 높아 한눈에 읽힌다.
 * - 강조색은 틸 하나. 빨강은 119·인명 위험·사고 신고에만 쓴다.
 * - 간격은 4의 배수 (4/8/12/16/24/32/48) × 화면 배율.
 * - 글자·터치 크기는 [CAR_SCALE]을 곱해 Pleos 기준에 맞춘다.
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
    /** 위급 버튼(사고가 났어요, 119) 바탕. 어두운 테마에서도 진한 빨강에 흰 글자로 둔다 */
    val dangerStrong: Color = danger,
    val onDangerStrong: Color = onDanger,
)

private val DarkSemantic = SemanticColors(
    success = Color(0xFF6FD69A), successContainer = Color(0xFF173A26),
    warning = Color(0xFFF2B65C), warningContainer = Color(0xFF3D2C10),
    danger = Color(0xFFFF7D72), dangerContainer = Color(0xFF4A1A17), onDanger = Color(0xFF2B0503),
    dangerStrong = Color(0xFFD93A30), onDangerStrong = Color.White,
)

val LocalSemantic = staticCompositionLocalOf { DarkSemantic }

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

/**
 * 차량 화면 배율. Pleos 디스플레이는 160dpi 기준 px로 글자 크기를 정한다
 * (본문 26~30, 제목 32~40, 헤드라인 48~60, 라벨 20~24).
 * 토큰(본문 16, 제목 20, 질문 36)에 1.75배를 곱하면 이 범위에 들고,
 * 터치 영역 76dp는 133px(약 13mm)이 되어 Pleos 기준 12.5mm를 넘는다.
 */
const val CAR_SCALE = 1.75f

/**
 * [scale]은 차량용 Android에서 [CAR_SCALE], 그 밖(개발용 에뮬레이터 등)에서는 1이다.
 */
@Composable
fun AlertBridgeTheme(scale: Float = 1f, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSemantic provides DarkSemantic, LocalCarScale provides scale) {
        MaterialTheme(colorScheme = Dark, typography = Type, shapes = Shape) { Scaled(content) }
    }
}

val LocalCarScale = staticCompositionLocalOf { 1f }

/**
 * 화면 배율을 적용한다. 바텀시트·대화상자는 별도 창이라 배율이 이어지지 않으므로 그 안에서 다시 감싼다.
 * 기준은 창 밀도가 아니라 화면 밀도라서 두 번 감싸도 배율이 겹치지 않는다.
 */
@Composable
fun Scaled(content: @Composable () -> Unit) {
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val d = androidx.compose.ui.unit.Density(res.displayMetrics.density * LocalCarScale.current, res.configuration.fontScale)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides d, content = content)
}

/** 바텀시트 최대 폭. 시트 밖(창 기준) 단위라 배율을 직접 곱한다. */
val SheetMaxWidth: androidx.compose.ui.unit.Dp
    @Composable get() = 760.dp * LocalCarScale.current

object AB {
    val semantic: SemanticColors
        @Composable get() = LocalSemantic.current
}
