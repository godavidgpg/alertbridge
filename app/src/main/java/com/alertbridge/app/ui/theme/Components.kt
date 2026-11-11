package com.alertbridge.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 운전 중 조작 기준: 터치 영역 최소 76dp */
val TouchMin = 76.dp

enum class Tone { Primary, Danger, Neutral }

/** 주 행동 버튼. 화면당 하나만 둔다. */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = TouchMin,
) {
    val sem = AB.semantic
    val colors = when (tone) {
        Tone.Danger -> ButtonDefaults.buttonColors(containerColor = sem.dangerStrong, contentColor = sem.onDangerStrong)
        Tone.Neutral -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)
        Tone.Primary -> ButtonDefaults.buttonColors()
    }
    Button(
        onClick, modifier.fillMaxWidth().defaultMinSize(minHeight = height), enabled = enabled, colors = colors,
        shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.m),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(24.dp))
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

/** 보조 행동 버튼 */
@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = TouchMin,
    large: Boolean = false,
) {
    OutlinedButton(
        onClick, modifier.defaultMinSize(minHeight = height), enabled = enabled,
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = Space.s, vertical = Space.m),
    ) {
        Text(text, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
    }
}

/** 내용을 묶는 카드. 테두리 한 줄, 그림자 없음. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = container, border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m), content = content)
    }
}

/** 작은 상태 표시. 색으로만 의미를 전하지 않도록 글자를 함께 쓴다. */
@Composable
fun StatusPill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.background(bg, CircleShape).padding(horizontal = Space.m, vertical = Space.xs),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelMedium) }
}

/** 원 안의 아이콘 */
@Composable
fun IconBadge(icon: ImageVector, fg: Color, bg: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.55f))
    }
}

/** 라벨-값 한 줄 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** 섹션 제목 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * 차량 화면의 가로 2단. 왼쪽은 지금 할 일과 큰 버튼, 오른쪽은 참고 정보.
 * 운전자 쪽(왼쪽)에 주 행동을 둬 손이 덜 가게 한다.
 */
@Composable
fun CarPanes(
    modifier: Modifier = Modifier,
    leftWeight: Float = 1f,
    rightWeight: Float = 1f,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.padding(Space.xl), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
        Column(Modifier.weight(leftWeight).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.l), content = left)
        Column(Modifier.weight(rightWeight).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.l), content = right)
    }
}
