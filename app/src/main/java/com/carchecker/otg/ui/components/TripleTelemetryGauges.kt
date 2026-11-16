package com.carchecker.otg.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carchecker.otg.model.LiveSensorReading
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.ui.theme.CockpitColors
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Three circular automotive HUD dials inspired by modern digital cockpit scanners:
 * 1) Engine RPM
 * 2) Coolant Temperature
 * 3) Speed / Battery Voltage (tap to switch)
 */
@Composable
fun TripleTelemetryGauges(
    sensors: List<LiveSensorReading>,
    batteryVoltage: Double?,
    unit: MeasurementUnit,
    isAr: Boolean,
    modifier: Modifier = Modifier
) {
    val sensorMap = remember(sensors) { sensors.associateBy { it.pidHex } }
    var showBatteryOnThird by remember { mutableStateOf(false) }

    val rpm = sensorMap["0C"]?.value ?: 0.0
    val coolantVal = sensorMap["05"]?.value
    val speedVal = sensorMap["0D"]?.value ?: 0.0
    val voltageVal = sensorMap["42"]?.value ?: batteryVoltage

    val isImperial = unit == MeasurementUnit.IMPERIAL
    val coolantMin = if (isImperial) 100f else 40f
    val coolantMax = if (isImperial) 265f else 130f
    val coolantUnit = if (isImperial) "°F" else "°C"
    val coolantReading = coolantVal?.toFloat() ?: if (isImperial) 104f else 40f
    val coolantText = coolantVal?.let { "${it.toInt()}" } ?: "--"
    val coolantAlert = coolantVal != null && (
        (!isImperial && coolantVal >= 105.0) || (isImperial && coolantVal >= 221.0)
        )

    val speedMax = if (isImperial) 140f else 220f
    val speedUnit = if (isImperial) "mph" else "km/h"

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Engine RPM Dial
        CircularHudGauge(
            title = if (isAr) "المحرك" else "Engine",
            valueText = if (rpm > 0.0) "${rpm.toInt()}" else "0",
            unitText = "RPM",
            value = rpm.toFloat(),
            minValue = 0f,
            maxValue = 8000f,
            redZoneStartFraction = 0.78f,
            primaryTint = CockpitColors.HealthyGreen,
            alertTint = if (rpm > 6200.0) CockpitColors.CriticalRed else null,
            modifier = Modifier.weight(1f)
        )

        // 2. Coolant Temperature Dial
        CircularHudGauge(
            title = if (isAr) "الحرارة" else "Coolant",
            valueText = coolantText,
            unitText = coolantUnit,
            value = coolantReading,
            minValue = coolantMin,
            maxValue = coolantMax,
            redZoneStartFraction = 0.76f,
            primaryTint = CockpitColors.AccentCyan,
            alertTint = if (coolantAlert) CockpitColors.CriticalRed else null,
            modifier = Modifier.weight(1f)
        )

        // 3. Speed / Voltage Dial (tap to alternate)
        if (!showBatteryOnThird) {
            CircularHudGauge(
                title = if (isAr) "السرعة" else "Speed",
                valueText = "${speedVal.toInt()}",
                unitText = speedUnit,
                value = speedVal.toFloat(),
                minValue = 0f,
                maxValue = speedMax,
                redZoneStartFraction = 0.82f,
                primaryTint = CockpitColors.AccentBlue,
                alertTint = null,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showBatteryOnThird = true }
            )
        } else {
            val vFloat = (voltageVal ?: 12.0).toFloat()
            val vBad = voltageVal != null && (voltageVal < 12.1 || voltageVal > 15.0)
            CircularHudGauge(
                title = if (isAr) "البطارية" else "Battery",
                valueText = voltageVal?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                unitText = "V",
                value = vFloat,
                minValue = 10f,
                maxValue = 16f,
                redZoneStartFraction = 0.85f,
                primaryTint = CockpitColors.HealthyGreen,
                alertTint = if (vBad) CockpitColors.WarningAmber else null,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showBatteryOnThird = false }
            )
        }
    }
}

@Composable
fun CircularHudGauge(
    title: String,
    valueText: String,
    unitText: String,
    value: Float,
    minValue: Float,
    maxValue: Float,
    redZoneStartFraction: Float,
    primaryTint: Color,
    alertTint: Color?,
    modifier: Modifier = Modifier
) {
    val clamped = ((value - minValue) / (maxValue - minValue).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 500),
        label = "hud_gauge_anim"
    )
    val activeAccent = alertTint ?: primaryTint

    Column(
        modifier = modifier.padding(bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            val compact = maxWidth < 112.dp
            val valueSp = if (compact) 15.sp else 18.sp
            val unitSp = if (compact) 8.5.sp else 9.5.sp

            Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val strokePx = (radius * 0.13f).coerceIn(5.dp.toPx(), 9.dp.toPx())
            val arcRadius = radius - strokePx * 0.7f
            val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)

            // Outer radial cockpit dial backdrop
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = 0.14f),
                        CockpitColors.Surface,
                        CockpitColors.BackgroundDeep
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius
            )

            // Subtle outer metallic ring
            drawCircle(
                color = CockpitColors.SurfaceBorder.copy(alpha = 0.75f),
                radius = radius - 1.dp.toPx(),
                style = Stroke(width = 1.2.dp.toPx())
            )

            val startAngle = 135f
            val fullSweep = 270f

            // Background track arc
            drawArc(
                color = CockpitColors.SurfaceElevated,
                startAngle = startAngle,
                sweepAngle = fullSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // Cyan-to-Green glowing band
            val safeSweep = fullSweep * redZoneStartFraction
            drawArc(
                brush = Brush.sweepGradient(
                    0.0f to CockpitColors.AccentCyan.copy(alpha = 0.65f),
                    0.45f to primaryTint,
                    0.80f to CockpitColors.HealthyGreen,
                    1.0f to CockpitColors.AccentCyan,
                    center = center
                ),
                startAngle = startAngle,
                sweepAngle = safeSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx * 0.65f, cap = StrokeCap.Round)
            )

            // Redline high-alert arc tail
            drawArc(
                color = CockpitColors.CriticalRed.copy(alpha = 0.85f),
                startAngle = startAngle + safeSweep,
                sweepAngle = fullSweep * (1f - redZoneStartFraction),
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx * 0.65f, cap = StrokeCap.Round)
            )

            // Glow arc following needle progress
            drawArc(
                color = activeAccent.copy(alpha = 0.30f),
                startAngle = startAngle,
                sweepAngle = fullSweep * animatedFraction,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx * 1.45f, cap = StrokeCap.Round)
            )

            // Crisp radial dial ticks
            val tickCount = 24
            val outerTickR = arcRadius - strokePx * 0.65f
            for (i in 0..tickCount) {
                val frac = i / tickCount.toFloat()
                val deg = startAngle + frac * fullSweep
                val rad = Math.toRadians(deg.toDouble())
                val isMajor = i % 4 == 0
                val innerTickR = outerTickR - if (isMajor) radius * 0.14f else radius * 0.08f
                val tickColor = when {
                    frac >= redZoneStartFraction -> CockpitColors.CriticalRed.copy(alpha = 0.85f)
                    isMajor -> Color.White.copy(alpha = 0.85f)
                    else -> CockpitColors.TextSecondary.copy(alpha = 0.45f)
                }
                drawLine(
                    color = tickColor,
                    start = Offset(
                        center.x + (cos(rad) * innerTickR).toFloat(),
                        center.y + (sin(rad) * innerTickR).toFloat()
                    ),
                    end = Offset(
                        center.x + (cos(rad) * outerTickR).toFloat(),
                        center.y + (sin(rad) * outerTickR).toFloat()
                    ),
                    strokeWidth = if (isMajor) 1.6.dp.toPx() else 0.9.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Needle pointer
            val needleDeg = startAngle + animatedFraction * fullSweep
            val needleRad = Math.toRadians(needleDeg.toDouble())
            val tipLen = arcRadius - strokePx * 0.85f
            val tailLen = radius * 0.16f
            val tip = Offset(
                center.x + (cos(needleRad) * tipLen).toFloat(),
                center.y + (sin(needleRad) * tipLen).toFloat()
            )
            val tail = Offset(
                center.x - (cos(needleRad) * tailLen).toFloat(),
                center.y - (sin(needleRad) * tailLen).toFloat()
            )
            drawLine(
                color = activeAccent.copy(alpha = 0.45f),
                start = tail,
                end = tip,
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = tail,
                end = tip,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = activeAccent,
                radius = 4.2.dp.toPx(),
                center = center
            )
            drawCircle(
                color = CockpitColors.BackgroundDeep,
                radius = 2.dp.toPx(),
                center = center
            )
        }

            // Digital value inside the open bottom dial sector (keeps center unobstructed for needle)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (compact) 4.dp else 6.dp)
            ) {
                Text(
                    text = valueText,
                    fontSize = valueSp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (alertTint != null) alertTint else Color.White,
                    maxLines = 1
                )
                Text(
                    text = unitText,
                    fontSize = unitSp,
                    color = activeAccent,
                    fontWeight = FontWeight.Medium,
                    lineHeight = unitSp
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = CockpitColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
