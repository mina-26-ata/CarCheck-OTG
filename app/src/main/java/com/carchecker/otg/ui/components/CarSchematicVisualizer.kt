package com.carchecker.otg.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carchecker.otg.R
import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.model.CarSubsystemZone
import com.carchecker.otg.model.CarZoneSummary
import com.carchecker.otg.model.VehicleBodyStyle
import com.carchecker.otg.model.ZoneStatus
import com.carchecker.otg.ui.theme.CockpitColors
import kotlin.math.hypot

private const val XRAY_IMAGE_ASPECT_RATIO = 1024f / 540f

@DrawableRes
private fun xrayDrawableFor(bodyStyle: VehicleBodyStyle): Int = when (bodyStyle) {
    VehicleBodyStyle.SUV -> R.drawable.car_xray_suv
    VehicleBodyStyle.SEDAN -> R.drawable.car_xray_sedan
    VehicleBodyStyle.PICKUP_TRUCK -> R.drawable.car_xray_pickup
    VehicleBodyStyle.HATCHBACK -> R.drawable.car_xray_hatchback
    VehicleBodyStyle.COUPE -> R.drawable.car_xray_coupe
}

/**
 * Calibrated normalized (x, y) coordinates [0f..1f] for each subsystem hotspot
 * on each 1024x540 3/4 front-left X-Ray vehicle illustration.
 */
private fun zoneNormalizedPos(zone: CarSubsystemZone, bodyStyle: VehicleBodyStyle): Offset =
    when (bodyStyle) {
        VehicleBodyStyle.SUV -> when (zone) {
            CarSubsystemZone.ENGINE -> Offset(0.31f, 0.34f)
            CarSubsystemZone.ELECTRICAL -> Offset(0.14f, 0.56f)
            CarSubsystemZone.TRANSMISSION -> Offset(0.48f, 0.65f)
            CarSubsystemZone.BRAKES_CHASSIS -> Offset(0.58f, 0.72f)
            CarSubsystemZone.FUEL_EXHAUST -> Offset(0.79f, 0.64f)
            CarSubsystemZone.BODY_CABIN -> Offset(0.62f, 0.27f)
        }
        VehicleBodyStyle.SEDAN -> when (zone) {
            CarSubsystemZone.ENGINE -> Offset(0.28f, 0.43f)
            CarSubsystemZone.ELECTRICAL -> Offset(0.14f, 0.57f)
            CarSubsystemZone.TRANSMISSION -> Offset(0.54f, 0.64f)
            CarSubsystemZone.BRAKES_CHASSIS -> Offset(0.47f, 0.72f)
            CarSubsystemZone.FUEL_EXHAUST -> Offset(0.83f, 0.55f)
            CarSubsystemZone.BODY_CABIN -> Offset(0.57f, 0.32f)
        }
        VehicleBodyStyle.PICKUP_TRUCK -> when (zone) {
            CarSubsystemZone.ENGINE -> Offset(0.26f, 0.43f)
            CarSubsystemZone.ELECTRICAL -> Offset(0.14f, 0.56f)
            CarSubsystemZone.TRANSMISSION -> Offset(0.55f, 0.66f)
            CarSubsystemZone.BRAKES_CHASSIS -> Offset(0.43f, 0.73f)
            CarSubsystemZone.FUEL_EXHAUST -> Offset(0.84f, 0.58f)
            CarSubsystemZone.BODY_CABIN -> Offset(0.53f, 0.30f)
        }
        VehicleBodyStyle.HATCHBACK -> when (zone) {
            CarSubsystemZone.ENGINE -> Offset(0.24f, 0.42f)
            CarSubsystemZone.ELECTRICAL -> Offset(0.13f, 0.58f)
            CarSubsystemZone.TRANSMISSION -> Offset(0.56f, 0.64f)
            CarSubsystemZone.BRAKES_CHASSIS -> Offset(0.44f, 0.72f)
            CarSubsystemZone.FUEL_EXHAUST -> Offset(0.83f, 0.57f)
            CarSubsystemZone.BODY_CABIN -> Offset(0.56f, 0.31f)
        }
        VehicleBodyStyle.COUPE -> when (zone) {
            CarSubsystemZone.ENGINE -> Offset(0.31f, 0.39f)
            CarSubsystemZone.ELECTRICAL -> Offset(0.15f, 0.56f)
            CarSubsystemZone.TRANSMISSION -> Offset(0.59f, 0.62f)
            CarSubsystemZone.BRAKES_CHASSIS -> Offset(0.54f, 0.70f)
            CarSubsystemZone.FUEL_EXHAUST -> Offset(0.86f, 0.54f)
            CarSubsystemZone.BODY_CABIN -> Offset(0.57f, 0.31f)
        }
    }

/**
 * Interactive 3/4-perspective X-Ray vehicle diagnostic map with 3D touch tilt,
 * body-style-matched X-Ray illustrations, and calibrated subsystem hotspots.
 */
@Composable
fun CarSchematicVisualizer(
    zoneMap: Map<CarSubsystemZone, CarZoneSummary>,
    selectedZone: CarSubsystemZone?,
    onSelectZone: (CarSubsystemZone?) -> Unit,
    isScanning: Boolean,
    hasScan: Boolean,
    isAr: Boolean,
    onOpenFaults: () -> Unit,
    onOpenObservations: () -> Unit,
    modifier: Modifier = Modifier,
    bodyStyle: VehicleBodyStyle = VehicleBodyStyle.SUV,
    vehicleName: String? = null
) {
    val infinite = rememberInfiniteTransition(label = "xray_car_anim")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    val scanSweepX by infinite.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_sweep"
    )

    // Interactive 3D touch tilt & spring-back state
    var targetTiltX by remember { mutableFloatStateOf(0f) }
    var targetTiltY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val animTiltX by animateFloatAsState(
        targetValue = targetTiltX,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = if (isDragging) Spring.StiffnessHigh else Spring.StiffnessLow
        ),
        label = "tilt_x"
    )
    val animTiltY by animateFloatAsState(
        targetValue = targetTiltY,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = if (isDragging) Spring.StiffnessHigh else Spring.StiffnessLow
        ),
        label = "tilt_y"
    )
    val animScale by animateFloatAsState(
        targetValue = if (isDragging) 1.045f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "drag_scale"
    )

    val alertZoneCount = zoneMap.values.count {
        it.status == ZoneStatus.CRITICAL || it.status == ZoneStatus.WARNING
    }

    CockpitCard(
        modifier = modifier,
        borderColor = when {
            isScanning -> CockpitColors.AccentCyan.copy(alpha = 0.85f)
            zoneMap.values.any { it.status == ZoneStatus.CRITICAL } -> CockpitColors.CriticalRed.copy(alpha = 0.55f)
            zoneMap.values.any { it.status == ZoneStatus.WARNING } -> CockpitColors.WarningAmber.copy(alpha = 0.55f)
            hasScan -> CockpitColors.HealthyGreen.copy(alpha = 0.45f)
            else -> CockpitColors.HudGlowBorder
        }
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isAr) "خريطة الفحص التفاعلية للسيارة (X-Ray)" else "Interactive Vehicle X-Ray Map",
                    style = MaterialTheme.typography.titleMedium,
                    color = CockpitColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        isScanning -> if (isAr) "جاري مسح الوحدات وتحديد مواقع الأعطال..." else "Scanning ECUs & locating subsystems..."
                        alertZoneCount > 0 -> if (isAr) "اضغط على أي نقطة في السيارة لمعرفة العطل أو حرّك بإصبعك للمعاينة" else "Tap any zone on the car to inspect faults, or drag to tilt 3D"
                        hasScan -> if (isAr) "جميع الأنظمة الظاهرة سليمة — اضغط أي جزء للتفاصيل" else "All subsystems look healthy — tap any zone to inspect"
                        else -> if (isAr) "حرّك السيارة بإصبعك أو اضغط على أي جزء لمعاينة الأنظمة" else "Drag to tilt in 3D or tap any car subsystem"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = CockpitColors.TextSecondary,
                    maxLines = 2
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = when {
                    isScanning -> if (isAr) "مسح حي" else "SCANNING"
                    alertZoneCount > 0 -> if (isAr) "$alertZoneCount تنبيه" else "$alertZoneCount Alerts"
                    hasScan -> if (isAr) "سليمة" else "HEALTHY"
                    else -> if (isAr) "جاهز" else "READY"
                },
                color = when {
                    isScanning -> CockpitColors.AccentCyan
                    zoneMap.values.any { it.status == ZoneStatus.CRITICAL } -> CockpitColors.CriticalRed
                    zoneMap.values.any { it.status == ZoneStatus.WARNING } -> CockpitColors.WarningAmber
                    hasScan -> CockpitColors.HealthyGreen
                    else -> CockpitColors.AccentBlue
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Color Legend + 3D Touch badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot(color = CockpitColors.HealthyGreen, label = if (isAr) "سليم" else "Healthy")
                LegendDot(color = CockpitColors.WarningAmber, label = if (isAr) "تحذير" else "Warning")
                LegendDot(color = CockpitColors.CriticalRed, label = if (isAr) "عطل" else "Fault")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CockpitColors.AccentCyan.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.TouchApp,
                    contentDescription = null,
                    tint = CockpitColors.AccentCyan,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (isAr) "3D باللمس" else "3D Touch",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CockpitColors.AccentCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Interactive 3D-Tilting X-Ray Car Stage (exact 1024:540 aspect ratio)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(XRAY_IMAGE_ASPECT_RATIO)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0A2B47),
                            Color(0xFF040F1C),
                            CockpitColors.BackgroundDeep
                        )
                    )
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                            targetTiltX = 0f
                            targetTiltY = 0f
                        },
                        onDragCancel = {
                            isDragging = false
                            targetTiltX = 0f
                            targetTiltY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            targetTiltY = (targetTiltY + dragAmount.x * 0.12f).coerceIn(-18f, 18f)
                            targetTiltX = (targetTiltX - dragAmount.y * 0.10f).coerceIn(-12f, 12f)
                        }
                    )
                }
                .pointerInput(zoneMap, selectedZone, bodyStyle) {
                    detectTapGestures { tap ->
                        val canvasSize: IntSize = size
                        val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                        val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                        val threshold = minOf(w, h) * 0.22f
                        val nearest = CarSubsystemZone.entries.minByOrNull { zone ->
                            val norm = zoneNormalizedPos(zone, bodyStyle)
                            hypot(norm.x * w - tap.x, norm.y * h - tap.y)
                        }
                        if (nearest != null) {
                            val norm = zoneNormalizedPos(nearest, bodyStyle)
                            if (hypot(norm.x * w - tap.x, norm.y * h - tap.y) < threshold) {
                                val next = if (selectedZone == nearest) null else nearest
                                onSelectZone(next)
                            }
                        }
                    }
                }
        ) {
            // Subtle perspective floor grid behind the car
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawHudFloorGrid()
            }

            // 3D-tilting layer containing the X-Ray car + locked hotspot pins
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationX = animTiltX
                        rotationY = animTiltY
                        scaleX = animScale
                        scaleY = animScale
                        cameraDistance = 14f * density
                    }
            ) {
                Image(
                    painter = painterResource(id = xrayDrawableFor(bodyStyle)),
                    contentDescription = vehicleName ?: CarZoneMapper.bodyStyleLabel(bodyStyle, isAr),
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawZoneAurasAndPins(
                        bodyStyle = bodyStyle,
                        zoneMap = zoneMap,
                        selectedZone = selectedZone,
                        pulseAlpha = pulseAlpha
                    )
                    if (isScanning) {
                        val beamX = size.width * scanSweepX
                        val beamWidth = size.width * 0.14f
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    CockpitColors.AccentCyan.copy(alpha = 0.25f),
                                    CockpitColors.HealthyGreen.copy(alpha = 0.50f),
                                    Color.Transparent
                                ),
                                startX = beamX - beamWidth / 2f,
                                endX = beamX + beamWidth / 2f
                            ),
                            topLeft = Offset(beamX - beamWidth / 2f, 0f),
                            size = Size(beamWidth, size.height)
                        )
                        drawLine(
                            color = CockpitColors.AccentCyan.copy(alpha = 0.9f),
                            start = Offset(beamX, size.height * 0.06f),
                            end = Offset(beamX, size.height * 0.94f),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
            }
        }

        // Detected Vehicle Nameplate directly underneath the X-Ray Car
        Spacer(modifier = Modifier.height(6.dp))
        val bodyLabel = CarZoneMapper.bodyStyleLabel(bodyStyle, isAr)
        val displayTitle = vehicleName?.takeIf { it.isNotBlank() }
            ?: if (isAr) "مركبة جاهزة للفحص (OBD-II)" else "Vehicle Ready (OBD-II)"

        Surface(
            color = CockpitColors.SurfaceElevated.copy(alpha = 0.85f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, CockpitColors.AccentCyan.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsCar,
                        contentDescription = null,
                        tint = CockpitColors.AccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CockpitColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(text = bodyLabel, color = CockpitColors.AccentCyan)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive Subsystem Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedZone == null,
                onClick = { onSelectZone(null) },
                label = { Text(if (isAr) "كل الأنظمة" else "All Zones", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CockpitColors.AccentCyan.copy(alpha = 0.18f),
                    selectedLabelColor = CockpitColors.AccentCyan
                )
            )
            CarSubsystemZone.entries.forEach { zone ->
                val summary = zoneMap[zone]
                val status = summary?.status ?: ZoneStatus.IDLE
                val zColor = zoneStatusColor(status)
                val isSelected = selectedZone == zone
                val count = summary?.issueCount ?: 0
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectZone(if (isSelected) null else zone) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(zColor)
                        )
                    },
                    label = {
                        val base = CarZoneMapper.zoneShortLabel(zone, isAr)
                        Text(
                            text = if (count > 0) "$base ($count)" else base,
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = zColor.copy(alpha = 0.18f),
                        selectedLabelColor = zColor
                    )
                )
            }
        }

        // Focused Zone Detail Drawer
        val detailSummary = selectedZone?.let { zoneMap[it] }
        AnimatedVisibility(visible = detailSummary != null) {
            if (detailSummary != null) {
                val zColor = zoneStatusColor(detailSummary.status)
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = CockpitColors.SurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, zColor.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = when (detailSummary.status) {
                                        ZoneStatus.CRITICAL -> Icons.Rounded.Error
                                        ZoneStatus.WARNING -> Icons.Rounded.Warning
                                        else -> Icons.Rounded.CheckCircle
                                    },
                                    contentDescription = null,
                                    tint = zColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = CarZoneMapper.zoneTitle(detailSummary.zone, isAr),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CockpitColors.TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            StatusPill(text = zoneStatusLabel(detailSummary.status, isAr), color = zColor)
                        }

                        if (detailSummary.faultCodes.isNotEmpty()) {
                            detailSummary.faultCodes.take(3).forEach { dtc ->
                                Text(
                                    text = "• ${dtc.code}: ${if (isAr) dtc.knowledge.titleAr else dtc.knowledge.titleEn}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CockpitColors.CriticalRed,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val firstFix = if (isAr) dtc.knowledge.fixStepsAr.firstOrNull() else dtc.knowledge.fixStepsEn.firstOrNull()
                                if (!firstFix.isNullOrBlank()) {
                                    Text(
                                        text = "   ${if (isAr) "الحل:" else "Fix:"} $firstFix",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CockpitColors.AccentTeal,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        } else {
                            val activeObs = detailSummary.observations.filter {
                                it.severity.name in setOf("CRITICAL", "WARNING")
                            }
                            if (activeObs.isNotEmpty()) {
                                activeObs.take(2).forEach { obs ->
                                    Text(
                                        text = "• ${if (isAr) obs.titleAr else obs.titleEn}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CockpitColors.WarningAmber
                                    )
                                    val firstSol = if (isAr) obs.resolutionStepsAr.firstOrNull() else obs.resolutionStepsEn.firstOrNull()
                                    if (!firstSol.isNullOrBlank()) {
                                        Text(
                                            text = "   ${if (isAr) "الحل:" else "Fix:"} $firstSol",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = CockpitColors.AccentCyan
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = if (isAr) "هذه المنطقة من السيارة سليمة وخالية من أكواد الأعطال." else "This vehicle subsystem is healthy with zero active faults.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CockpitColors.TextSecondary
                                )
                            }
                        }

                        if (detailSummary.faultCodes.isNotEmpty() || detailSummary.issueCount > 0) {
                            Spacer(modifier = Modifier.height(2.dp))
                            OutlinedButton(
                                onClick = if (detailSummary.faultCodes.isNotEmpty()) onOpenFaults else onOpenObservations,
                                border = BorderStroke(1.dp, zColor.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                            ) {
                                Text(
                                    text = if (detailSummary.faultCodes.isNotEmpty()) {
                                        if (isAr) "عرض جميع الأعطال وطرق الإصلاح" else "Open Faults & Full Fix Guide"
                                    } else {
                                        if (isAr) "عرض الملاحظات والذكاء الاصطناعي" else "Open Notes & AI Analysis"
                                    },
                                    color = zColor,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = zColor, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = CockpitColors.TextSecondary
        )
    }
}

fun zoneStatusColor(status: ZoneStatus): Color = when (status) {
    ZoneStatus.CRITICAL -> CockpitColors.CriticalRed
    ZoneStatus.WARNING -> CockpitColors.WarningAmber
    ZoneStatus.HEALTHY -> CockpitColors.HealthyGreen
    ZoneStatus.IDLE -> CockpitColors.AccentCyan
}

fun zoneStatusLabel(status: ZoneStatus, isAr: Boolean): String = when (status) {
    ZoneStatus.CRITICAL -> if (isAr) "عطل / خطر" else "CRITICAL"
    ZoneStatus.WARNING -> if (isAr) "تحذير" else "WARNING"
    ZoneStatus.HEALTHY -> if (isAr) "سليم" else "HEALTHY"
    ZoneStatus.IDLE -> if (isAr) "غير مفحوص" else "IDLE"
}

private fun DrawScope.drawHudFloorGrid() {
    val gridColor = CockpitColors.AccentCyan.copy(alpha = 0.08f)
    val horizonY = size.height * 0.62f
    for (i in 0..8) {
        val t = i / 8f
        val topX = size.width * (0.20f + 0.60f * t)
        val botX = size.width * (-0.10f + 1.20f * t)
        drawLine(
            color = gridColor,
            start = Offset(topX, horizonY),
            end = Offset(botX, size.height),
            strokeWidth = 0.9.dp.toPx()
        )
    }
    for (j in 0..4) {
        val y = horizonY + (size.height - horizonY) * (j / 4f)
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 0.8.dp.toPx()
        )
    }
}

private fun DrawScope.drawZoneAurasAndPins(
    bodyStyle: VehicleBodyStyle,
    zoneMap: Map<CarSubsystemZone, CarZoneSummary>,
    selectedZone: CarSubsystemZone?,
    pulseAlpha: Float
) {
    CarSubsystemZone.entries.forEach { zone ->
        val summary = zoneMap[zone]
        val status = summary?.status ?: ZoneStatus.IDLE
        val pinColor = zoneStatusColor(status)
        val norm = zoneNormalizedPos(zone, bodyStyle)
        val center = Offset(norm.x * size.width, norm.y * size.height)
        val isSelected = selectedZone == zone
        val isAlert = status == ZoneStatus.CRITICAL || status == ZoneStatus.WARNING

        if (isAlert || isSelected) {
            val auraRadius = if (isSelected) 34.dp.toPx() else (28.dp.toPx() * (0.88f + 0.24f * pulseAlpha))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        pinColor.copy(alpha = if (isAlert) 0.42f * pulseAlpha else 0.36f),
                        pinColor.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = auraRadius
                ),
                radius = auraRadius,
                center = center
            )

            val ringRadius = if (isSelected) 16.dp.toPx() else (13.dp.toPx() * (0.85f + 0.28f * pulseAlpha))
            drawCircle(
                color = pinColor.copy(alpha = 0.92f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 1.8.dp.toPx())
            )
        } else {
            // Subtle target reticle so user sees where each part can be tapped
            drawCircle(
                color = pinColor.copy(alpha = 0.32f),
                radius = 9.5.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Crisp dark backing + glowing core dot
        drawCircle(
            color = CockpitColors.BackgroundDeep.copy(alpha = 0.90f),
            radius = 6.2.dp.toPx(),
            center = center
        )
        drawCircle(
            color = pinColor,
            radius = if (isAlert || isSelected) 4.4.dp.toPx() else 3.4.dp.toPx(),
            center = center
        )
    }
}

/**
 * Compact X-Ray car locator badge used inside Fault Detail sheets to show
 * exactly which subsystem on the vehicle is affected.
 */
@Composable
fun MiniCarZoneLocator(
    targetZone: CarSubsystemZone,
    accentColor: Color,
    isAr: Boolean,
    modifier: Modifier = Modifier,
    bodyStyle: VehicleBodyStyle = VehicleBodyStyle.SUV
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CockpitColors.BackgroundDeep,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(114.dp)
                    .aspectRatio(XRAY_IMAGE_ASPECT_RATIO)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF040F1C))
            ) {
                Image(
                    painter = painterResource(id = xrayDrawableFor(bodyStyle)),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val norm = zoneNormalizedPos(targetZone, bodyStyle)
                    val center = Offset(norm.x * size.width, norm.y * size.height)
                    drawCircle(
                        color = accentColor.copy(alpha = 0.42f),
                        radius = 11.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = CockpitColors.BackgroundDeep,
                        radius = 5.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = accentColor,
                        radius = 3.6.dp.toPx(),
                        center = center
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isAr) "الموقع على السيارة" else "Vehicle Fault Location",
                    style = MaterialTheme.typography.labelMedium,
                    color = CockpitColors.TextSecondary
                )
                Text(
                    text = CarZoneMapper.zoneTitle(targetZone, isAr),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
        }
    }
}
