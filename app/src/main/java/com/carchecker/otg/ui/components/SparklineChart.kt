package com.carchecker.otg.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun SparklineChart(
    points: List<Double>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
    ) {
        if (points.isEmpty()) return@Canvas
        if (points.size == 1) {
            drawCircle(
                color = lineColor,
                radius = 3.dp.toPx(),
                center = Offset(size.width / 2f, size.height / 2f)
            )
            return@Canvas
        }

        val minVal = points.minOrNull() ?: 0.0
        val maxVal = points.maxOrNull() ?: 1.0
        val span = (maxVal - minVal).takeIf { it > 0.0001 } ?: 1.0
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        val padY = 4.dp.toPx()
        val usableH = (size.height - padY * 2f).coerceAtLeast(1f)

        val path = Path()
        points.forEachIndexed { idx, v ->
            val x = idx * stepX
            val norm = ((v - minVal) / span).toFloat().coerceIn(0f, 1f)
            val y = size.height - padY - norm * usableH
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
