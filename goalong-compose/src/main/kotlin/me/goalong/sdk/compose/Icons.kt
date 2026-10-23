package me.goalong.sdk.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// アイコンフォントの依存を増やさないため、状態アイコンは線で描く(モノクロ・同じ線幅)

@Composable
internal fun CheckIcon(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val path = Path().apply {
            moveTo(w * 0.18f, w * 0.52f)
            lineTo(w * 0.42f, w * 0.75f)
            lineTo(w * 0.84f, w * 0.27f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
internal fun CloseIcon(color: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.16f
        drawLine(color, Offset(w * 0.15f, w * 0.15f), Offset(w * 0.85f, w * 0.85f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.85f, w * 0.15f), Offset(w * 0.15f, w * 0.85f), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun ChevronDownIcon(color: Color, size: Dp = 10.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val path = Path().apply {
            moveTo(w * 0.12f, w * 0.32f)
            lineTo(w * 0.5f, w * 0.7f)
            lineTo(w * 0.88f, w * 0.32f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
internal fun GiftIcon(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // ふた
        drawRect(color, Offset(w * 0.1f, w * 0.32f), Size(w * 0.8f, w * 0.2f), style = stroke)
        // 箱
        drawRect(color, Offset(w * 0.18f, w * 0.52f), Size(w * 0.64f, w * 0.38f), style = stroke)
        // リボン
        drawLine(color, Offset(w * 0.5f, w * 0.32f), Offset(w * 0.5f, w * 0.9f), w * 0.1f)
        val bow = Path().apply {
            moveTo(w * 0.5f, w * 0.32f)
            cubicTo(w * 0.3f, w * 0.05f, w * 0.12f, w * 0.25f, w * 0.5f, w * 0.32f)
            moveTo(w * 0.5f, w * 0.32f)
            cubicTo(w * 0.7f, w * 0.05f, w * 0.88f, w * 0.25f, w * 0.5f, w * 0.32f)
        }
        drawPath(bow, color, style = stroke)
    }
}
