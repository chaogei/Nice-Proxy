package com.niceproxy.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niceproxy.core.designsystem.theme.LatencyColors
import kotlin.math.abs

/** 协议短标签，用色块区分，避免节点列表里一眼望去全是文字。 */
@Composable
fun ProtocolBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val color = protocolColor(text)
    Box(
        modifier = modifier
            .clip(BadgeShape)
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

private val BadgeShape = RoundedCornerShape(6.dp)

/**
 * 协议配色。同一协议在任何页面都用同一个颜色，
 * 用户扫一眼颜色就能定位，不必逐个读文字。
 */
private fun protocolColor(badge: String): Color = when (badge.uppercase()) {
    "HY2", "HY" -> Color(0xFF8B5CF6)
    "VL" -> Color(0xFF0EA5E9)
    "VM" -> Color(0xFF6366F1)
    "TR" -> Color(0xFFEF4444)
    "SS" -> Color(0xFF14B8A6)
    "TUIC" -> Color(0xFFF59E0B)
    "ATLS" -> Color(0xFFEC4899)
    "STLS" -> Color(0xFFA855F7)
    "SOCKS" -> Color(0xFF64748B)
    "HTTP" -> Color(0xFF64748B)
    "SSH" -> Color(0xFF78716C)
    else -> Color(0xFF94A3B8)
}

/** 延迟指示：一个圆点 + 毫秒数。null 表示未测速。 */
@Composable
fun LatencyIndicator(
    latencyMs: Int?,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when {
        latencyMs == null -> LatencyColors.unknown to "--"
        latencyMs < 0 -> LatencyColors.timeout to "超时"
        latencyMs < 200 -> LatencyColors.good to "$latencyMs ms"
        latencyMs < 500 -> LatencyColors.fair to "$latencyMs ms"
        else -> LatencyColors.poor to "$latencyMs ms"
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** 字节数的人类可读格式，流量统计与订阅余量共用。 */
fun formatBytes(bytes: Long): String {
    val value = abs(bytes).toDouble()
    return when {
        value < 1024 -> "${bytes} B"
        value < 1024 * 1024 -> "%.1f KB".format(value / 1024)
        value < 1024.0 * 1024 * 1024 -> "%.1f MB".format(value / (1024 * 1024))
        value < 1024.0 * 1024 * 1024 * 1024 -> "%.2f GB".format(value / (1024.0 * 1024 * 1024))
        else -> "%.2f TB".format(value / (1024.0 * 1024 * 1024 * 1024))
    }
}

fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"
