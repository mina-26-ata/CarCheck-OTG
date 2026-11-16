package com.carchecker.otg.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.model.CarSubsystemZone
import com.carchecker.otg.model.CarZoneSummary
import com.carchecker.otg.model.VehicleBodyStyle
import com.carchecker.otg.model.ZoneStatus
import com.carchecker.otg.ui.theme.CockpitColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private data class Vec3(val x: Float, val y: Float, val z: Float)

private data class CarBodyProfile(
    val style: VehicleBodyStyle,
    val halfW: Float,
    val roofTaper: Float,
    val sillY: Float,
    val wheelRadius: Float,
    val frontAxleX: Float,
    val rearAxleX: Float,
    val noseY: Float,
    val hoodEdgeY: Float,
    val cowlX: Float,
    val cowlY: Float,
    val roofFrontX: Float,
    val roofRearX: Float,
    val roofY: Float,
    val bPillarX: Float,
    val rearDeckX: Float,
    val rearDeckY: Float,
    val tailLipX: Float,
    val tailLipY: Float,
    val hasOpenPickupBed: Boolean = false,
    val hasRoofRails: Boolean = false
)

private fun profileFor(style: VehicleBodyStyle): CarBodyProfile = when (style) {
    VehicleBodyStyle.SEDAN -> CarBodyProfile(
        style = style,
        halfW = 0.43f,
        roofTaper = 0.78f,
        sillY = 0.12f,
        wheelRadius = 0.16f,
        frontAxleX = -0.60f,
        rearAxleX = 0.58f,
        noseY = 0.28f,
        hoodEdgeY = 0.36f,
        cowlX = -0.35f,
        cowlY = 0.41f,
        roofFrontX = -0.10f,
        roofRearX = 0.44f,
        roofY = 0.62f,
        bPillarX = 0.14f,
        rearDeckX = 0.74f,
        rearDeckY = 0.43f,
        tailLipX = 0.97f,
        tailLipY = 0.40f
    )
    VehicleBodyStyle.SUV -> CarBodyProfile(
        style = style,
        halfW = 0.45f,
        roofTaper = 0.81f,
        sillY = 0.15f,
        wheelRadius = 0.185f,
        frontAxleX = -0.60f,
        rearAxleX = 0.58f,
        noseY = 0.34f,
        hoodEdgeY = 0.43f,
        cowlX = -0.36f,
        cowlY = 0.48f,
        roofFrontX = -0.12f,
        roofRearX = 0.78f,
        roofY = 0.73f,
        bPillarX = 0.16f,
        rearDeckX = 0.94f,
        rearDeckY = 0.50f,
        tailLipX = 0.97f,
        tailLipY = 0.45f,
        hasRoofRails = true
    )
    VehicleBodyStyle.PICKUP_TRUCK -> CarBodyProfile(
        style = style,
        halfW = 0.45f,
        roofTaper = 0.82f,
        sillY = 0.16f,
        wheelRadius = 0.19f,
        frontAxleX = -0.62f,
        rearAxleX = 0.60f,
        noseY = 0.36f,
        hoodEdgeY = 0.45f,
        cowlX = -0.35f,
        cowlY = 0.49f,
        roofFrontX = -0.14f,
        roofRearX = 0.25f,
        roofY = 0.73f,
        bPillarX = 0.04f,
        rearDeckX = 0.27f,
        rearDeckY = 0.49f,
        tailLipX = 0.98f,
        tailLipY = 0.48f,
        hasOpenPickupBed = true
    )
    VehicleBodyStyle.HATCHBACK -> CarBodyProfile(
        style = style,
        halfW = 0.42f,
        roofTaper = 0.80f,
        sillY = 0.12f,
        wheelRadius = 0.155f,
        frontAxleX = -0.57f,
        rearAxleX = 0.54f,
        noseY = 0.29f,
        hoodEdgeY = 0.38f,
        cowlX = -0.34f,
        cowlY = 0.43f,
        roofFrontX = -0.10f,
        roofRearX = 0.66f,
        roofY = 0.65f,
        bPillarX = 0.14f,
        rearDeckX = 0.88f,
        rearDeckY = 0.46f,
        tailLipX = 0.91f,
        tailLipY = 0.40f
    )
    VehicleBodyStyle.COUPE -> CarBodyProfile(
        style = style,
        halfW = 0.44f,
        roofTaper = 0.75f,
        sillY = 0.105f,
        wheelRadius = 0.16f,
        frontAxleX = -0.60f,
        rearAxleX = 0.58f,
        noseY = 0.25f,
        hoodEdgeY = 0.33f,
        cowlX = -0.26f,
        cowlY = 0.39f,
        roofFrontX = -0.02f,
        roofRearX = 0.38f,
        roofY = 0.57f,
        bPillarX = 0.22f,
        rearDeckX = 0.84f,
        rearDeckY = 0.41f,
        tailLipX = 0.97f,
        tailLipY = 0.39f
    )
}

private fun project3d(v: Vec3, width: Float, height: Float): Offset {
    val cx = width * 0.50f
    val cy = height * 0.68f
    val scale = minOf(width * 0.37f, height * 0.80f)
    val sx = cx + scale * (v.x * 0.84f + v.z * 0.40f)
    val sy = cy + scale * (-v.x * 0.25f + v.z * 0.27f - v.y * 0.94f)
    return Offset(sx, sy)
}

private fun DrawScope.p3(x: Float, y: Float, z: Float): Offset =
    project3d(Vec3(x, y, z), size.width, size.height)

private fun DrawScope.p3(v: Vec3): Offset =
    project3d(v, size.width, size.height)

private fun zonePinVec3(zone: CarSubsystemZone, profile: CarBodyProfile): Vec3 = when (zone) {
    CarSubsystemZone.ENGINE -> Vec3(-0.62f, profile.hoodEdgeY - 0.04f, 0.0f)
    CarSubsystemZone.ELECTRICAL -> Vec3(-0.77f, profile.noseY + 0.05f, profile.halfW * 0.55f)
    CarSubsystemZone.TRANSMISSION -> Vec3(-0.22f, profile.sillY + 0.10f, 0.02f)
    CarSubsystemZone.BRAKES_CHASSIS -> Vec3(profile.frontAxleX, profile.wheelRadius + 0.01f, profile.halfW)
    CarSubsystemZone.FUEL_EXHAUST -> Vec3(0.45f, profile.sillY + 0.10f, -0.04f)
    CarSubsystemZone.BODY_CABIN -> Vec3(0.10f, profile.roofY - 0.12f, 0.02f)
}

/**
 * Interactive 3/4-perspective X-Ray wireframe vehicle schematic that adapts
 * proportionally to the detected vehicle's body style and highlights real-time
 * health, warnings, and exact fault locations inside the car.
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
    bodyStyle: VehicleBodyStyle = VehicleBodyStyle.SEDAN,
    vehicleName: String? = null
) {
    val profile = profileFor(bodyStyle)
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
        initialValue = 0.10f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_sweep"
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
                        alertZoneCount > 0 -> if (isAr) "اضغط على أي منطقة متوهجة في السيارة لمعرفة العطل والحل" else "Tap any highlighted zone on the car to inspect faults"
                        hasScan -> if (isAr) "جميع الأنظمة الظاهرة سليمة — اضغط أي جزء للتفاصيل" else "All subsystems look healthy — tap any zone to inspect"
                        else -> if (isAr) "سيظهر هنا توزع الأعطال مباشرة على هيكل السيارة" else "Fault locations will light up live on the vehicle schematic"
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

        // Color Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(color = CockpitColors.HealthyGreen, label = if (isAr) "سليم" else "Healthy")
            LegendDot(color = CockpitColors.WarningAmber, label = if (isAr) "ملاحظة/تحذير" else "Warning")
            LegendDot(color = CockpitColors.CriticalRed, label = if (isAr) "عطل حرج" else "Fault/Critical")
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3D Projected X-Ray Car Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(212.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0A263D),
                            Color(0xFF040E1A),
                            CockpitColors.BackgroundDeep
                        )
                    )
                )
                .pointerInput(zoneMap, selectedZone, bodyStyle) {
                    detectTapGestures { tap ->
                        val canvasSize: IntSize = size
                        val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                        val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                        val threshold = minOf(w, h) * 0.16f
                        val nearest = CarSubsystemZone.entries.minByOrNull { zone ->
                            val pin = project3d(zonePinVec3(zone, profile), w, h)
                            hypot(pin.x - tap.x, pin.y - tap.y)
                        }
                        if (nearest != null) {
                            val pin = project3d(zonePinVec3(nearest, profile), w, h)
                            if (hypot(pin.x - tap.x, pin.y - tap.y) < threshold) {
                                val next = if (selectedZone == nearest) null else nearest
                                onSelectZone(next)
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawPerspectiveFloorGrid(profile)
                drawFarSideWheels(profile)
                drawCarGlassShellFill(profile)
                drawCarInternals3D(
                    profile = profile,
                    zoneMap = zoneMap,
                    selectedZone = selectedZone,
                    pulseAlpha = pulseAlpha
                )
                drawCarWireframe3D(profile)
                drawNearSideWheels(
                    profile = profile,
                    brakeZoneStatus = zoneMap[CarSubsystemZone.BRAKES_CHASSIS]?.status ?: ZoneStatus.IDLE
                )
                drawHotspotPins3D(
                    profile = profile,
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
                        start = Offset(beamX, size.height * 0.08f),
                        end = Offset(beamX, size.height * 0.92f),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }

        // Detected Vehicle Nameplate directly underneath the X-Ray Car Drawing
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
                Spacer(modifier = Modifier.width(8.dp))
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

private fun DrawScope.drawPerspectiveFloorGrid(profile: CarBodyProfile) {
    val gridColor = CockpitColors.AccentCyan.copy(alpha = 0.10f)
    for (i in 0..8) {
        val x = -1.25f + (2.50f * i / 8f)
        drawLine(
            color = gridColor,
            start = p3(x, 0f, -0.78f),
            end = p3(x, 0f, 0.78f),
            strokeWidth = 0.8.dp.toPx()
        )
    }
    for (j in 0..5) {
        val z = -0.75f + (1.50f * j / 5f)
        drawLine(
            color = gridColor,
            start = p3(-1.25f, 0f, z),
            end = p3(1.25f, 0f, z),
            strokeWidth = 0.8.dp.toPx()
        )
    }
    // Under-chassis floor shadow oval
    val shadowCenter = p3(0f, 0f, 0f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF000000).copy(alpha = 0.55f), Color.Transparent),
            center = shadowCenter,
            radius = size.minDimension * 0.55f
        ),
        radius = size.minDimension * 0.55f,
        center = shadowCenter
    )
    // Wheel ground contact markers
    val wR = profile.wheelRadius
    listOf(
        Vec3(profile.frontAxleX, 0f, profile.halfW),
        Vec3(profile.rearAxleX, 0f, profile.halfW)
    ).forEach {
        drawLine(
            color = CockpitColors.AccentCyan.copy(alpha = 0.35f),
            start = p3(it.x - wR * 0.55f, 0f, it.z),
            end = p3(it.x + wR * 0.55f, 0f, it.z),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun buildSideLowerWithArches(profile: CarBodyProfile, z: Float): List<Vec3> {
    val pts = mutableListOf<Vec3>()
    val rArch = profile.wheelRadius + 0.042f
    val frontBumpX = -0.98f
    val rearBumpX = profile.tailLipX + 0.02f

    pts.add(Vec3(frontBumpX, profile.sillY + 0.02f, z * 0.90f))
    pts.add(Vec3(profile.frontAxleX - rArch, profile.sillY, z))

    // Front wheel arch semicircle
    val steps = 8
    for (i in 0..steps) {
        val angle = PI * (1.0 - i.toDouble() / steps) // 180 deg -> 0 deg
        val ax = profile.frontAxleX + (rArch * cos(angle)).toFloat()
        val ay = maxOf(profile.sillY, profile.wheelRadius + (rArch * sin(angle)).toFloat() * 0.95f)
        pts.add(Vec3(ax, ay, z))
    }

    // Center rocker sill
    pts.add(Vec3(profile.frontAxleX + rArch, profile.sillY, z))
    pts.add(Vec3(profile.rearAxleX - rArch, profile.sillY, z))

    // Rear wheel arch semicircle
    for (i in 0..steps) {
        val angle = PI * (1.0 - i.toDouble() / steps)
        val ax = profile.rearAxleX + (rArch * cos(angle)).toFloat()
        val ay = maxOf(profile.sillY, profile.wheelRadius + (rArch * sin(angle)).toFloat() * 0.95f)
        pts.add(Vec3(ax, ay, z))
    }

    pts.add(Vec3(profile.rearAxleX + rArch, profile.sillY + 0.01f, z))
    pts.add(Vec3(rearBumpX, profile.sillY + 0.07f, z * 0.90f))
    return pts
}

private fun buildSideUpperContour(profile: CarBodyProfile, sideSign: Float): List<Vec3> {
    val z = profile.halfW * sideSign
    val zRoof = profile.halfW * profile.roofTaper * sideSign
    val pts = mutableListOf<Vec3>()

    pts.add(Vec3(-1.01f, profile.sillY + 0.05f, z * 0.89f))
    pts.add(Vec3(-1.02f, profile.noseY, z * 0.92f))
    pts.add(Vec3(-0.84f, profile.hoodEdgeY, z * 0.97f))
    pts.add(Vec3(profile.cowlX, profile.cowlY, z))
    pts.add(Vec3(profile.roofFrontX, profile.roofY - 0.02f, zRoof))
    pts.add(Vec3((profile.roofFrontX + profile.roofRearX) * 0.45f, profile.roofY, zRoof))
    pts.add(Vec3(profile.roofRearX, profile.roofY - 0.015f, zRoof))

    if (profile.hasOpenPickupBed) {
        // Upright rear cab drops down to open cargo bed rail
        pts.add(Vec3(profile.rearDeckX, profile.rearDeckY, z))
        pts.add(Vec3(profile.tailLipX, profile.tailLipY, z * 0.95f))
        pts.add(Vec3(profile.tailLipX + 0.03f, profile.sillY + 0.14f, z * 0.92f))
    } else {
        pts.add(Vec3(profile.rearDeckX, profile.rearDeckY, z * 0.97f))
        pts.add(Vec3(profile.tailLipX, profile.tailLipY, z * 0.94f))
        pts.add(Vec3(profile.tailLipX + 0.02f, profile.sillY + 0.08f, z * 0.90f))
    }
    return pts
}

private fun DrawScope.drawPolyline3d(
    points: List<Vec3>,
    color: Color,
    strokeWidth: Float,
    closed: Boolean = false
) {
    if (points.size < 2) return
    val path = Path().apply {
        val first = p3(points.first())
        moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            val pt = p3(points[i])
            lineTo(pt.x, pt.y)
        }
        if (closed) close()
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawCarGlassShellFill(profile: CarBodyProfile) {
    val nearUpper = buildSideUpperContour(profile, +1f)
    val nearLower = buildSideLowerWithArches(profile, +profile.halfW)
    val farUpper = buildSideUpperContour(profile, -1f)

    // Translucent 3D body volume silhouette so wireframe immediately reads as a solid X-Ray car
    val hull = Path().apply {
        val start = p3(nearUpper.first())
        moveTo(start.x, start.y)
        // Front bumper across far side -> far top contour -> rear corner -> near lower contour
        farUpper.take(5).forEach {
            val p = p3(it); lineTo(p.x, p.y)
        }
        farUpper.drop(4).forEach {
            val p = p3(it); lineTo(p.x, p.y)
        }
        nearUpper.reversed().take(4).forEach {
            val p = p3(it); lineTo(p.x, p.y)
        }
        nearLower.reversed().forEach {
            val p = p3(it); lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(
        path = hull,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1A5B85).copy(alpha = 0.24f),
                Color(0xFF0A2A43).copy(alpha = 0.30f),
                Color(0xFF051320).copy(alpha = 0.35f)
            )
        )
    )

    // Subtle glass roof sheen highlight
    val zRoofNear = profile.halfW * profile.roofTaper
    val zRoofFar = -profile.halfW * profile.roofTaper
    val roofHighlight = Path().apply {
        val p1 = p3(profile.roofFrontX, profile.roofY - 0.01f, zRoofFar)
        val p2 = p3(profile.roofRearX, profile.roofY - 0.01f, zRoofFar)
        val p3Pt = p3(profile.roofRearX, profile.roofY - 0.01f, zRoofNear)
        val p4 = p3(profile.roofFrontX, profile.roofY - 0.01f, zRoofNear)
        moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3Pt.x, p3Pt.y); lineTo(p4.x, p4.y)
        close()
    }
    drawPath(roofHighlight, color = CockpitColors.AccentCyan.copy(alpha = 0.14f))
}

private fun DrawScope.drawFarSideWheels(profile: CarBodyProfile) {
    drawWheel3D(
        cx = profile.frontAxleX,
        cy = profile.wheelRadius,
        cz = -profile.halfW * 0.94f,
        r = profile.wheelRadius,
        isNearSide = false,
        caliperColor = CockpitColors.AccentCyan.copy(alpha = 0.35f)
    )
    drawWheel3D(
        cx = profile.rearAxleX,
        cy = profile.wheelRadius,
        cz = -profile.halfW * 0.94f,
        r = profile.wheelRadius,
        isNearSide = false,
        caliperColor = CockpitColors.AccentCyan.copy(alpha = 0.35f)
    )
}

private fun DrawScope.drawNearSideWheels(
    profile: CarBodyProfile,
    brakeZoneStatus: ZoneStatus
) {
    val caliperColor = when (brakeZoneStatus) {
        ZoneStatus.CRITICAL -> CockpitColors.CriticalRed
        ZoneStatus.WARNING -> CockpitColors.WarningAmber
        ZoneStatus.HEALTHY -> CockpitColors.HealthyGreen
        ZoneStatus.IDLE -> CockpitColors.AccentCyan
    }
    drawWheel3D(
        cx = profile.frontAxleX,
        cy = profile.wheelRadius,
        cz = profile.halfW * 0.98f,
        r = profile.wheelRadius,
        isNearSide = true,
        caliperColor = caliperColor
    )
    drawWheel3D(
        cx = profile.rearAxleX,
        cy = profile.wheelRadius,
        cz = profile.halfW * 0.98f,
        r = profile.wheelRadius,
        isNearSide = true,
        caliperColor = caliperColor
    )
}

private fun DrawScope.drawWheel3D(
    cx: Float,
    cy: Float,
    cz: Float,
    r: Float,
    isNearSide: Boolean,
    caliperColor: Color
) {
    val segs = 28
    val tirePath = Path()
    val innerRimPath = Path()
    val brakeDiscPath = Path()

    for (i in 0..segs) {
        val theta = (2.0 * PI * i / segs)
        val dx = (r * cos(theta)).toFloat()
        val dy = (r * sin(theta)).toFloat()

        val outerPt = p3(cx + dx, cy + dy, cz)
        val rimPt = p3(cx + dx * 0.66f, cy + dy * 0.66f, cz)
        val rotorPt = p3(cx + dx * 0.44f, cy + dy * 0.44f, cz - if (isNearSide) 0.03f else 0f)

        if (i == 0) {
            tirePath.moveTo(outerPt.x, outerPt.y)
            innerRimPath.moveTo(rimPt.x, rimPt.y)
            brakeDiscPath.moveTo(rotorPt.x, rotorPt.y)
        } else {
            tirePath.lineTo(outerPt.x, outerPt.y)
            innerRimPath.lineTo(rimPt.x, rimPt.y)
            brakeDiscPath.lineTo(rotorPt.x, rotorPt.y)
        }
    }
    tirePath.close()
    innerRimPath.close()
    brakeDiscPath.close()

    val baseAlpha = if (isNearSide) 1.0f else 0.36f
    drawPath(tirePath, color = Color(0xFF040B14).copy(alpha = 0.85f * baseAlpha))
    drawPath(
        tirePath,
        color = Color(0xFFA9E4FF).copy(alpha = 0.88f * baseAlpha),
        style = Stroke(width = (if (isNearSide) 2.2f else 1.2f).dp.toPx())
    )
    drawPath(
        innerRimPath,
        color = CockpitColors.AccentCyan.copy(alpha = 0.72f * baseAlpha),
        style = Stroke(width = (if (isNearSide) 1.5f else 0.9f).dp.toPx())
    )

    if (isNearSide) {
        // Glowing brake disc & caliper
        drawPath(
            brakeDiscPath,
            color = caliperColor.copy(alpha = 0.25f)
        )
        drawPath(
            brakeDiscPath,
            color = caliperColor.copy(alpha = 0.75f),
            style = Stroke(width = 1.2.dp.toPx())
        )
        // 5 metallic alloy spokes
        val hub = p3(cx, cy, cz)
        for (s in 0 until 5) {
            val angle = (2.0 * PI * s / 5.0) + 0.35
            val spokeEnd = p3(
                cx + (r * 0.64f * cos(angle)).toFloat(),
                cy + (r * 0.64f * sin(angle)).toFloat(),
                cz
            )
            drawLine(
                color = Color(0xFFBCEBFF).copy(alpha = 0.75f),
                start = hub,
                end = spokeEnd,
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        drawCircle(color = caliperColor, radius = 3.2.dp.toPx(), center = hub)
    }
}

private fun DrawScope.drawCarInternals3D(
    profile: CarBodyProfile,
    zoneMap: Map<CarSubsystemZone, CarZoneSummary>,
    selectedZone: CarSubsystemZone?,
    pulseAlpha: Float
) {
    fun zoneColor(zone: CarSubsystemZone): Pair<Color, Float> {
        val status = zoneMap[zone]?.status ?: ZoneStatus.IDLE
        val color = zoneStatusColor(status)
        val glow = when (status) {
            ZoneStatus.CRITICAL -> 0.55f + 0.35f * pulseAlpha
            ZoneStatus.WARNING -> 0.42f + 0.30f * pulseAlpha
            ZoneStatus.HEALTHY -> 0.38f
            ZoneStatus.IDLE -> 0.26f
        }
        val boost = if (selectedZone == zone) 0.25f else 0f
        return color to (glow + boost).coerceAtMost(0.95f)
    }

    // 1. ENGINE BLOCK + RADIATOR + INTAKE MANIFOLD
    val (engCol, engAlpha) = zoneColor(CarSubsystemZone.ENGINE)
    val engCenter = p3(zonePinVec3(CarSubsystemZone.ENGINE, profile))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(engCol.copy(alpha = engAlpha * 0.80f), engCol.copy(alpha = engAlpha * 0.22f), Color.Transparent),
            center = engCenter,
            radius = size.minDimension * 0.22f
        ),
        radius = size.minDimension * 0.22f,
        center = engCenter
    )
    drawWireframeBox3d(
        x0 = -0.82f, x1 = -0.46f,
        y0 = profile.sillY + 0.08f, y1 = profile.hoodEdgeY - 0.04f,
        z0 = -0.17f, z1 = 0.17f,
        color = engCol.copy(alpha = 0.75f),
        strokeWidth = 1.3.dp.toPx()
    )
    // Engine intake manifold & 4 cylinder head nodes atop the block
    for (c in 0..3) {
        val cxCyl = -0.75f + c * 0.075f
        val cy = p3(cxCyl, profile.hoodEdgeY - 0.025f, 0.0f)
        drawCircle(color = engCol.copy(alpha = 0.88f), radius = 2.1.dp.toPx(), center = cy)
    }
    drawLine(
        color = engCol.copy(alpha = 0.80f),
        start = p3(-0.79f, profile.hoodEdgeY - 0.015f, -0.09f),
        end = p3(-0.48f, profile.hoodEdgeY - 0.015f, -0.09f),
        strokeWidth = 1.2.dp.toPx()
    )
    drawLine(
        color = engCol.copy(alpha = 0.80f),
        start = p3(-0.79f, profile.hoodEdgeY - 0.015f, 0.09f),
        end = p3(-0.48f, profile.hoodEdgeY - 0.015f, 0.09f),
        strokeWidth = 1.2.dp.toPx()
    )
    // Front Radiator frame
    drawPolyline3d(
        listOf(
            Vec3(-0.92f, profile.sillY + 0.08f, -0.22f),
            Vec3(-0.92f, profile.noseY + 0.03f, -0.22f),
            Vec3(-0.92f, profile.noseY + 0.03f, 0.22f),
            Vec3(-0.92f, profile.sillY + 0.08f, 0.22f)
        ),
        color = engCol.copy(alpha = 0.55f),
        strokeWidth = 1.1.dp.toPx(),
        closed = true
    )

    // 2. ELECTRICAL / BATTERY + WIRING HARNESS
    val (elecCol, elecAlpha) = zoneColor(CarSubsystemZone.ELECTRICAL)
    val batPos = p3(zonePinVec3(CarSubsystemZone.ELECTRICAL, profile))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(elecCol.copy(alpha = elecAlpha * 0.60f), Color.Transparent),
            center = batPos,
            radius = size.minDimension * 0.13f
        ),
        radius = size.minDimension * 0.13f,
        center = batPos
    )
    drawWireframeBox3d(
        x0 = -0.84f, x1 = -0.68f,
        y0 = profile.sillY + 0.08f, y1 = profile.noseY + 0.03f,
        z0 = profile.halfW * 0.42f, z1 = profile.halfW * 0.78f,
        color = elecCol.copy(alpha = 0.85f),
        strokeWidth = 1.2.dp.toPx()
    )
    // Glowing wiring harness bus to firewall & cabin ECU
    drawPolyline3d(
        listOf(
            Vec3(-0.74f, profile.noseY + 0.03f, profile.halfW * 0.60f),
            Vec3(-0.36f, profile.cowlY - 0.05f, profile.halfW * 0.52f),
            Vec3(-0.14f, profile.cowlY - 0.04f, 0.10f)
        ),
        color = elecCol.copy(alpha = 0.72f),
        strokeWidth = 1.3.dp.toPx()
    )

    // 3. TRANSMISSION + DRIVESHAFT + REAR DIFFERENTIAL
    val (trCol, trAlpha) = zoneColor(CarSubsystemZone.TRANSMISSION)
    val trPos = p3(zonePinVec3(CarSubsystemZone.TRANSMISSION, profile))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(trCol.copy(alpha = trAlpha * 0.55f), Color.Transparent),
            center = trPos,
            radius = size.minDimension * 0.14f
        ),
        radius = size.minDimension * 0.14f,
        center = trPos
    )
    drawWireframeBox3d(
        x0 = -0.42f, x1 = -0.06f,
        y0 = profile.sillY + 0.04f, y1 = profile.sillY + 0.16f,
        z0 = -0.09f, z1 = 0.09f,
        color = trCol.copy(alpha = 0.85f),
        strokeWidth = 1.2.dp.toPx()
    )
    drawLine(
        color = trCol.copy(alpha = 0.85f),
        start = p3(-0.06f, profile.sillY + 0.09f, 0f),
        end = p3(profile.rearAxleX, profile.wheelRadius, 0f),
        strokeWidth = 2.6.dp.toPx(),
        cap = StrokeCap.Round
    )

    // 4. BRAKE LINES & SUSPENSION STRUTS
    val (brkCol, _) = zoneColor(CarSubsystemZone.BRAKES_CHASSIS)
    drawLine(
        color = brkCol.copy(alpha = 0.45f),
        start = p3(profile.frontAxleX, profile.wheelRadius, -profile.halfW * 0.90f),
        end = p3(profile.frontAxleX, profile.wheelRadius, profile.halfW * 0.90f),
        strokeWidth = 1.4.dp.toPx()
    )
    drawLine(
        color = brkCol.copy(alpha = 0.45f),
        start = p3(profile.rearAxleX, profile.wheelRadius, -profile.halfW * 0.90f),
        end = p3(profile.rearAxleX, profile.wheelRadius, profile.halfW * 0.90f),
        strokeWidth = 1.4.dp.toPx()
    )

    // 5. FUEL TANK & CATALYTIC / EXHAUST LINE
    val (fuelCol, fuelAlpha) = zoneColor(CarSubsystemZone.FUEL_EXHAUST)
    val fuelPos = p3(zonePinVec3(CarSubsystemZone.FUEL_EXHAUST, profile))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(fuelCol.copy(alpha = fuelAlpha * 0.50f), Color.Transparent),
            center = fuelPos,
            radius = size.minDimension * 0.14f
        ),
        radius = size.minDimension * 0.14f,
        center = fuelPos
    )
    drawWireframeBox3d(
        x0 = 0.30f, x1 = 0.56f,
        y0 = profile.sillY + 0.05f, y1 = profile.sillY + 0.16f,
        z0 = -0.20f, z1 = 0.18f,
        color = fuelCol.copy(alpha = 0.72f),
        strokeWidth = 1.1.dp.toPx()
    )
    drawPolyline3d(
        listOf(
            Vec3(-0.32f, profile.sillY + 0.04f, -0.06f),
            Vec3(0.24f, profile.sillY + 0.04f, -0.14f),
            Vec3(0.68f, profile.sillY + 0.05f, -0.22f),
            Vec3(profile.tailLipX - 0.02f, profile.sillY + 0.08f, -0.24f)
        ),
        color = fuelCol.copy(alpha = 0.80f),
        strokeWidth = 1.8.dp.toPx()
    )

    // 6. CABIN ECU / STEERING WHEEL / AIRBAGS
    val (cabCol, cabAlpha) = zoneColor(CarSubsystemZone.BODY_CABIN)
    val cabPos = p3(zonePinVec3(CarSubsystemZone.BODY_CABIN, profile))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(cabCol.copy(alpha = cabAlpha * 0.45f), Color.Transparent),
            center = cabPos,
            radius = size.minDimension * 0.15f
        ),
        radius = size.minDimension * 0.15f,
        center = cabPos
    )
    // Steering wheel ring inside near cockpit
    val swCenter = p3(-0.18f, profile.cowlY + 0.05f, profile.halfW * 0.36f)
    drawCircle(
        color = cabCol.copy(alpha = 0.70f),
        radius = 5.5.dp.toPx(),
        center = swCenter,
        style = Stroke(width = 1.4.dp.toPx())
    )
}

private fun DrawScope.drawWireframeBox3d(
    x0: Float, x1: Float,
    y0: Float, y1: Float,
    z0: Float, z1: Float,
    color: Color,
    strokeWidth: Float
) {
    val f = listOf(
        Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x0, y1, z1), Vec3(x0, y0, z1)
    )
    val b = listOf(
        Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), Vec3(x1, y0, z1)
    )
    drawPolyline3d(f, color, strokeWidth, closed = true)
    drawPolyline3d(b, color, strokeWidth, closed = true)
    for (i in 0..3) {
        drawLine(color = color, start = p3(f[i]), end = p3(b[i]), strokeWidth = strokeWidth)
    }
}

private fun DrawScope.drawCarWireframe3D(profile: CarBodyProfile) {
    val lineCyan = Color(0xFF7AE5FF)
    val brightRim = Color(0xFFD6F6FF)
    val dimStruct = Color(0xFF42A8D8).copy(alpha = 0.48f)

    val nearStroke = 1.85.dp.toPx()
    val farStroke = 1.15.dp.toPx()
    val thinStroke = 1.0.dp.toPx()

    // 1. Far-side structural outline (subtle depth cue)
    val farUpper = buildSideUpperContour(profile, -1f)
    val farLower = buildSideLowerWithArches(profile, -profile.halfW)
    drawPolyline3d(farUpper, dimStruct, farStroke)
    drawPolyline3d(farLower, dimStruct.copy(alpha = 0.35f), thinStroke)

    // Far side window cutout
    val zFarGlass = -profile.halfW * 0.90f
    val zFarRoof = -profile.halfW * profile.roofTaper
    drawPolyline3d(
        listOf(
            Vec3(profile.cowlX + 0.05f, profile.cowlY + 0.02f, zFarGlass),
            Vec3(profile.roofFrontX + 0.02f, profile.roofY - 0.035f, zFarRoof),
            Vec3(profile.roofRearX - 0.03f, profile.roofY - 0.03f, zFarRoof),
            Vec3(
                if (profile.hasOpenPickupBed) profile.rearDeckX - 0.02f else profile.rearDeckX - 0.08f,
                profile.cowlY + 0.03f,
                zFarGlass * 0.96f
            )
        ),
        color = dimStruct,
        strokeWidth = thinStroke,
        closed = true
    )

    // 2. Transverse symmetry ribs bridging Left <-> Right across the 3D body
    val zN = profile.halfW
    val zF = -profile.halfW
    val zRN = profile.halfW * profile.roofTaper
    val zRF = -profile.halfW * profile.roofTaper

    // Front bumper / hood seams
    drawLine(lineCyan, p3(-1.01f, profile.sillY + 0.05f, zF * 0.89f), p3(-1.01f, profile.sillY + 0.05f, zN * 0.89f), nearStroke)
    drawLine(brightRim, p3(-1.02f, profile.noseY, zF * 0.92f), p3(-1.02f, profile.noseY, zN * 0.92f), nearStroke)
    drawLine(lineCyan.copy(alpha = 0.70f), p3(-0.84f, profile.hoodEdgeY, zF * 0.97f), p3(-0.84f, profile.hoodEdgeY, zN * 0.97f), thinStroke)

    // Cowl (windshield base) + hood sculptural V-creases
    drawLine(brightRim, p3(profile.cowlX, profile.cowlY, zF), p3(profile.cowlX, profile.cowlY, zN), nearStroke)
    drawLine(lineCyan.copy(alpha = 0.55f), p3(-0.98f, profile.noseY + 0.02f, zN * 0.55f), p3(profile.cowlX, profile.cowlY, zN * 0.65f), thinStroke)
    drawLine(lineCyan.copy(alpha = 0.55f), p3(-0.98f, profile.noseY + 0.02f, zF * 0.55f), p3(profile.cowlX, profile.cowlY, zF * 0.65f), thinStroke)

    // Roof header & rear roof header
    drawLine(brightRim, p3(profile.roofFrontX, profile.roofY - 0.02f, zRF), p3(profile.roofFrontX, profile.roofY - 0.02f, zRN), nearStroke)
    drawLine(lineCyan, p3((profile.roofFrontX + profile.roofRearX) * 0.45f, profile.roofY, zRF), p3((profile.roofFrontX + profile.roofRearX) * 0.45f, profile.roofY, zRN), thinStroke)
    drawLine(lineCyan, p3(profile.roofRearX, profile.roofY - 0.015f, zRF), p3(profile.roofRearX, profile.roofY - 0.015f, zRN), nearStroke)

    // SUV Roof Rails
    if (profile.hasRoofRails) {
        drawLine(brightRim, p3(profile.roofFrontX + 0.04f, profile.roofY + 0.02f, zRN * 0.88f), p3(profile.roofRearX - 0.04f, profile.roofY + 0.015f, zRN * 0.88f), thinStroke)
        drawLine(dimStruct, p3(profile.roofFrontX + 0.04f, profile.roofY + 0.02f, zRF * 0.88f), p3(profile.roofRearX - 0.04f, profile.roofY + 0.015f, zRF * 0.88f), thinStroke)
    }

    // Rear deck & tailgate / pickup cargo bed
    drawLine(lineCyan, p3(profile.rearDeckX, profile.rearDeckY, zF * 0.96f), p3(profile.rearDeckX, profile.rearDeckY, zN * 0.96f), thinStroke)
    drawLine(brightRim, p3(profile.tailLipX, profile.tailLipY, zF * 0.94f), p3(profile.tailLipX, profile.tailLipY, zN * 0.94f), nearStroke)
    drawLine(lineCyan.copy(alpha = 0.65f), p3(profile.tailLipX + 0.02f, profile.sillY + 0.08f, zF * 0.90f), p3(profile.tailLipX + 0.02f, profile.sillY + 0.08f, zN * 0.90f), thinStroke)

    if (profile.hasOpenPickupBed) {
        // Pickup inner cargo bed floor box ribs
        drawPolyline3d(
            listOf(
                Vec3(profile.rearDeckX + 0.02f, profile.sillY + 0.16f, zF * 0.82f),
                Vec3(profile.tailLipX - 0.03f, profile.sillY + 0.16f, zF * 0.82f),
                Vec3(profile.tailLipX - 0.03f, profile.sillY + 0.16f, zN * 0.82f),
                Vec3(profile.rearDeckX + 0.02f, profile.sillY + 0.16f, zN * 0.82f)
            ),
            color = dimStruct,
            strokeWidth = thinStroke,
            closed = true
        )
    }

    // 3. Front Grille & Angular LED Headlights (iconic front 3/4 face!)
    // Center Grille
    drawPolyline3d(
        listOf(
            Vec3(-1.02f, profile.noseY - 0.01f, -0.22f),
            Vec3(-1.02f, profile.noseY - 0.01f, 0.22f),
            Vec3(-1.01f, profile.sillY + 0.07f, 0.18f),
            Vec3(-1.01f, profile.sillY + 0.07f, -0.18f)
        ),
        color = brightRim.copy(alpha = 0.85f),
        strokeWidth = 1.3.dp.toPx(),
        closed = true
    )
    // Inner grille slats
    for (g in -1..1) {
        val zg = g * 0.09f
        drawLine(lineCyan.copy(alpha = 0.50f), p3(-1.02f, profile.noseY - 0.02f, zg), p3(-1.01f, profile.sillY + 0.08f, zg), 0.9.dp.toPx())
    }
    // Near LED Headlight
    drawPolyline3d(
        listOf(
            Vec3(-1.00f, profile.noseY + 0.01f, zN * 0.58f),
            Vec3(-0.91f, profile.noseY + 0.045f, zN * 0.94f),
            Vec3(-0.90f, profile.noseY - 0.01f, zN * 0.94f),
            Vec3(-1.00f, profile.noseY - 0.045f, zN * 0.62f)
        ),
        color = CockpitColors.AccentCyan,
        strokeWidth = 1.8.dp.toPx(),
        closed = true
    )
    // Far LED Headlight
    drawPolyline3d(
        listOf(
            Vec3(-1.00f, profile.noseY + 0.01f, zF * 0.58f),
            Vec3(-0.93f, profile.noseY + 0.04f, zF * 0.92f),
            Vec3(-0.93f, profile.noseY - 0.01f, zF * 0.92f),
            Vec3(-1.00f, profile.noseY - 0.04f, zF * 0.62f)
        ),
        color = CockpitColors.AccentCyan.copy(alpha = 0.70f),
        strokeWidth = 1.3.dp.toPx(),
        closed = true
    )

    // 4. Near-side outer contour (crispest brightest layer)
    val nearUpper = buildSideUpperContour(profile, +1f)
    val nearLower = buildSideLowerWithArches(profile, +profile.halfW)
    drawPolyline3d(nearUpper, brightRim, nearStroke)
    drawPolyline3d(nearLower, lineCyan.copy(alpha = 0.90f), nearStroke)

    // Door shoulder / beltline + sculpted side crease + A/B/C pillar frames
    val beltStart = Vec3(profile.cowlX, profile.cowlY - 0.01f, zN)
    val beltEnd = if (profile.hasOpenPickupBed) {
        Vec3(profile.rearDeckX, profile.rearDeckY - 0.01f, zN)
    } else {
        Vec3(profile.rearDeckX, profile.rearDeckY - 0.01f, zN * 0.97f)
    }
    drawLine(brightRim.copy(alpha = 0.85f), p3(beltStart), p3(beltEnd), 1.4.dp.toPx())
    // Lower door crease
    drawLine(
        lineCyan.copy(alpha = 0.45f),
        p3(profile.frontAxleX + profile.wheelRadius + 0.06f, profile.sillY + 0.14f, zN),
        p3(profile.rearAxleX - profile.wheelRadius - 0.06f, profile.sillY + 0.16f, zN),
        thinStroke
    )

    // Near Side Window Frame + B-Pillar divider
    val windowRearTopX = if (profile.hasOpenPickupBed) profile.roofRearX - 0.02f else profile.roofRearX - 0.04f
    val windowRearBotX = if (profile.hasOpenPickupBed) profile.rearDeckX - 0.03f else profile.rearDeckX - 0.09f
    val windowPts = listOf(
        Vec3(profile.cowlX + 0.06f, profile.cowlY + 0.01f, zN * 0.96f),
        Vec3(profile.roofFrontX + 0.02f, profile.roofY - 0.03f, zRN * 0.98f),
        Vec3(windowRearTopX, profile.roofY - 0.03f, zRN * 0.98f),
        Vec3(windowRearBotX, beltEnd.y + 0.02f, zN * 0.95f)
    )
    drawPolyline3d(windowPts, brightRim.copy(alpha = 0.85f), 1.3.dp.toPx(), closed = true)

    // B-pillar vertical divider + door cut line
    drawLine(
        lineCyan.copy(alpha = 0.78f),
        p3(profile.bPillarX, profile.roofY - 0.025f, zRN),
        p3(profile.bPillarX, beltStart.y, zN),
        1.4.dp.toPx()
    )
    drawLine(
        lineCyan.copy(alpha = 0.45f),
        p3(profile.bPillarX, beltStart.y, zN),
        p3(profile.bPillarX, profile.sillY + 0.02f, zN),
        thinStroke
    )

    // Side Wing Mirrors (instantly recognizable automotive silhouette cue!)
    drawWingMirror(Vec3(profile.cowlX + 0.04f, profile.cowlY + 0.03f, zN), +1f, brightRim)
    drawWingMirror(Vec3(profile.cowlX + 0.04f, profile.cowlY + 0.03f, zF), -1f, dimStruct)

    // Near Rear Taillight Accent
    drawPolyline3d(
        listOf(
            Vec3(profile.tailLipX, profile.tailLipY - 0.01f, zN * 0.82f),
            Vec3(profile.tailLipX - 0.04f, profile.tailLipY - 0.01f, zN * 0.96f),
            Vec3(profile.tailLipX - 0.02f, profile.tailLipY - 0.08f, zN * 0.95f),
            Vec3(profile.tailLipX + 0.01f, profile.tailLipY - 0.08f, zN * 0.82f)
        ),
        color = CockpitColors.CriticalRed.copy(alpha = 0.78f),
        strokeWidth = 1.4.dp.toPx(),
        closed = true
    )
}

private fun DrawScope.drawWingMirror(anchor: Vec3, sideSign: Float, color: Color) {
    val stalkOut = Vec3(anchor.x + 0.02f, anchor.y + 0.025f, anchor.z + 0.065f * sideSign)
    val outerTop = Vec3(anchor.x + 0.07f, anchor.y + 0.04f, anchor.z + 0.09f * sideSign)
    val outerBot = Vec3(anchor.x + 0.07f, anchor.y - 0.01f, anchor.z + 0.085f * sideSign)
    drawLine(color = color, start = p3(anchor), end = p3(stalkOut), strokeWidth = 1.3.dp.toPx())
    drawPolyline3d(listOf(stalkOut, outerTop, outerBot, anchor), color = color, strokeWidth = 1.2.dp.toPx(), closed = true)
}

private fun DrawScope.drawHotspotPins3D(
    profile: CarBodyProfile,
    zoneMap: Map<CarSubsystemZone, CarZoneSummary>,
    selectedZone: CarSubsystemZone?,
    pulseAlpha: Float
) {
    CarSubsystemZone.entries.forEach { zone ->
        val summary = zoneMap[zone]
        val status = summary?.status ?: ZoneStatus.IDLE
        val pinColor = zoneStatusColor(status)
        val p = p3(zonePinVec3(zone, profile))
        val isSelected = selectedZone == zone
        val isAlert = status == ZoneStatus.CRITICAL || status == ZoneStatus.WARNING

        if (isAlert || isSelected) {
            val haloR = if (isSelected) 17.dp.toPx() else (13.dp.toPx() * (0.85f + 0.3f * pulseAlpha))
            drawCircle(
                color = pinColor.copy(alpha = if (isAlert) 0.30f * pulseAlpha else 0.24f),
                radius = haloR,
                center = p
            )
            drawCircle(
                color = pinColor.copy(alpha = 0.82f),
                radius = haloR * 0.72f,
                center = p,
                style = Stroke(width = 1.4.dp.toPx())
            )
        }

        drawCircle(
            color = CockpitColors.BackgroundDeep,
            radius = 5.2.dp.toPx(),
            center = p
        )
        drawCircle(
            color = pinColor,
            radius = if (isAlert || isSelected) 4.dp.toPx() else 2.8.dp.toPx(),
            center = p
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
    bodyStyle: VehicleBodyStyle = VehicleBodyStyle.SEDAN
) {
    val profile = profileFor(bodyStyle)
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
            Canvas(modifier = Modifier.size(width = 104.dp, height = 60.dp)) {
                drawFarSideWheels(profile)
                drawCarWireframe3D(profile)
                drawNearSideWheels(
                    profile = profile,
                    brakeZoneStatus = if (targetZone == CarSubsystemZone.BRAKES_CHASSIS) ZoneStatus.CRITICAL else ZoneStatus.IDLE
                )
                val center = p3(zonePinVec3(targetZone, profile))
                drawCircle(
                    color = accentColor.copy(alpha = 0.36f),
                    radius = 11.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = accentColor,
                    radius = 3.8.dp.toPx(),
                    center = center
                )
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
