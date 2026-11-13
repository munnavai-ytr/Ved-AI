package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Lightweight vector-drawn emblem for Ved (zero bitmap allocation).
 */
@Composable
fun VedBrandMark(
  modifier: Modifier = Modifier,
  accentColor: Color = MaterialTheme.colorScheme.primary,
  petalColor: Color = MaterialTheme.colorScheme.onSurface,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) * 0.44f

    // Outer subtle ring
    drawCircle(
      color = accentColor.copy(alpha = 0.14f),
      radius = r * 1.05f,
      center = Offset(cx, cy),
    )

    // Left & right lotus wings
    val leftWing =
      Path().apply {
        moveTo(cx, cy + r * 0.55f)
        cubicTo(
          cx - r * 0.85f,
          cy + r * 0.3f,
          cx - r * 0.85f,
          cy - r * 0.35f,
          cx - r * 0.12f,
          cy - r * 0.55f,
        )
        cubicTo(
          cx - r * 0.3f,
          cy - r * 0.05f,
          cx - r * 0.25f,
          cy + r * 0.3f,
          cx,
          cy + r * 0.55f,
        )
        close()
      }

    val rightWing =
      Path().apply {
        moveTo(cx, cy + r * 0.55f)
        cubicTo(
          cx + r * 0.85f,
          cy + r * 0.3f,
          cx + r * 0.85f,
          cy - r * 0.35f,
          cx + r * 0.12f,
          cy - r * 0.55f,
        )
        cubicTo(
          cx + r * 0.3f,
          cy - r * 0.05f,
          cx + r * 0.25f,
          cy + r * 0.3f,
          cx,
          cy + r * 0.55f,
        )
        close()
      }

    drawPath(leftWing, color = accentColor.copy(alpha = 0.85f))
    drawPath(rightWing, color = accentColor.copy(alpha = 0.55f))

    // Center diamond spark
    val spark =
      Path().apply {
        moveTo(cx, cy - r * 0.72f)
        quadraticTo(cx + r * 0.08f, cy - r * 0.08f, cx + r * 0.42f, cy)
        quadraticTo(cx + r * 0.08f, cy + r * 0.08f, cx, cy + r * 0.52f)
        quadraticTo(cx - r * 0.08f, cy + r * 0.08f, cx - r * 0.42f, cy)
        quadraticTo(cx - r * 0.08f, cy - r * 0.08f, cx, cy - r * 0.72f)
        close()
      }

    drawPath(spark, color = petalColor)
    drawCircle(
      color = accentColor,
      radius = r * 1.05f,
      center = Offset(cx, cy),
      style = Stroke(width = r * 0.06f, cap = StrokeCap.Round),
    )
  }
}
