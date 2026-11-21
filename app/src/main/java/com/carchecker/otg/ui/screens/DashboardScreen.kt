package com.carchecker.otg.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carchecker.otg.data.export.ReportExporter
import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.domain.analyzer.TelemetryEvaluator
import com.carchecker.otg.domain.analyzer.TelemetryRating
import com.carchecker.otg.domain.analyzer.TelemetryRatingLevel
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.BluetoothAdapterInfo
import com.carchecker.otg.model.CarSubsystemZone
import com.carchecker.otg.model.ConnectionMode
import com.carchecker.otg.model.ConnectionState
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.model.ObservationSeverity
import com.carchecker.otg.model.SafetyVerdict
import com.carchecker.otg.ui.components.BluetoothDevicesBottomSheet
import com.carchecker.otg.ui.components.CarSchematicVisualizer
import com.carchecker.otg.ui.components.CockpitCard
import com.carchecker.otg.ui.components.HealthScoreGauge
import com.carchecker.otg.ui.components.ScanHistoryBottomSheet
import com.carchecker.otg.ui.components.SectionHeader
import com.carchecker.otg.ui.components.StatusPill
import com.carchecker.otg.ui.components.TripleTelemetryGauges
import com.carchecker.otg.ui.components.dtcStatusLabel
import com.carchecker.otg.ui.components.formatScanTimestamp
import com.carchecker.otg.ui.components.formatVehicleDisplayName
import com.carchecker.otg.ui.components.localizeAdapterLabel
import com.carchecker.otg.ui.components.observationSeverityColor
import com.carchecker.otg.ui.components.observationSeverityLabel
import com.carchecker.otg.ui.i18n.AppStrings
import com.carchecker.otg.ui.state.DiagnosticUiState
import com.carchecker.otg.ui.theme.CockpitColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DashboardScreen(
    state: DiagnosticUiState,
    strings: AppStrings,
    onConnectUsb: (Int?) -> Unit,
    onRefreshUsb: () -> Unit,
    bluetoothRequiredPermissions: Array<String>,
    onRefreshBluetooth: () -> Unit,
    onStartBluetoothScan: () -> Unit,
    onStopBluetoothScan: () -> Unit,
    onConnectBluetooth: (BluetoothAdapterInfo) -> Unit,
    onDisconnect: () -> Unit,
    onStartSimulation: () -> Unit,
    onRunFullScan: () -> Unit,
    onToggleLiveStreaming: () -> Unit,
    onClearDtcs: () -> Unit,
    onSelectZone: (CarSubsystemZone?) -> Unit,
    onLoadHistoryReport: (FullScanReport) -> Unit,
    onDeleteHistoryReport: (String) -> Unit,
    onNavigateToFaults: () -> Unit,
    onNavigateToObservations: () -> Unit
) {
    val context = LocalContext.current
    val isAr = state.settings.language == AppLanguage.AR
    val report = state.currentReport
    val isConnected = state.connectionState == ConnectionState.CONNECTED
    val activeSensors = if (state.liveSensors.isNotEmpty()) state.liveSensors else report?.sensorSnapshot.orEmpty()
    val sensorMap = activeSensors.associateBy { it.pidHex }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showBluetoothSheet by remember { mutableStateOf(false) }
    var showClearMilDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (showClearMilDialog) {
        AlertDialog(
            onDismissRequest = { showClearMilDialog = false },
            containerColor = CockpitColors.Surface,
            title = { Text(strings.clearDtcsDialogTitle, color = CockpitColors.CriticalRed, fontWeight = FontWeight.Bold) },
            text = { Text(strings.clearDtcsDialogBody, color = CockpitColors.TextPrimary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearMilDialog = false
                        onClearDtcs()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = CockpitColors.CriticalRed)
                ) {
                    Text(strings.confirmBtn, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMilDialog = false }) {
                    Text(strings.cancelBtn)
                }
            }
        )
    }

    if (showHistorySheet) {
        ScanHistoryBottomSheet(
            history = state.scanHistory,
            activeReportId = report?.id,
            isAr = isAr,
            strings = strings,
            onDismiss = { showHistorySheet = false },
            onSelectReport = { selected ->
                onLoadHistoryReport(selected)
                scope.launch { listState.animateScrollToItem(0) }
            },
            onDeleteReport = onDeleteHistoryReport
        )
    }

    if (showBluetoothSheet) {
        BluetoothDevicesBottomSheet(
            devices = state.bluetoothDevices,
            isScanning = state.isBluetoothScanning,
            hasPermissions = state.hasBluetoothPermission,
            isBluetoothEnabled = state.isBluetoothEnabled,
            requiredPermissions = bluetoothRequiredPermissions,
            isAr = isAr,
            onRefreshBluetooth = onRefreshBluetooth,
            onStartScan = onStartBluetoothScan,
            onStopScan = onStopBluetoothScan,
            onConnectDevice = onConnectBluetooth,
            onDismiss = { showBluetoothSheet = false }
        )
    }

    val rpm = sensorMap["0C"]?.value
    val coolant = sensorMap["05"]?.value
    val voltage = sensorMap["42"]?.value ?: report?.vehicleInfo?.batteryVoltage
    val tempUnit = if (state.settings.unit == MeasurementUnit.IMPERIAL) "°F" else "°C"

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. HUD Cockpit Panel: Triple Circular Gauges + Live Telemetry & Status Strip
        item {
            CockpitCard(borderColor = CockpitColors.HudGlowBorder) {
                if (isConnected) {
                    LiveCockpitStatusBar(
                        isStreaming = state.isLiveStreaming,
                        isBusy = state.scanProgress.isScanning || state.isResettingMil,
                        isAr = isAr,
                        onToggle = onToggleLiveStreaming
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                TripleTelemetryGauges(
                    sensors = activeSensors,
                    batteryVoltage = report?.vehicleInfo?.batteryVoltage,
                    unit = state.settings.unit,
                    isAr = isAr
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = CockpitColors.SurfaceBorder)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Telemetry Readout lines with immediate quality rating badge (ممتاز / جيد / مقبول / سيئ)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        TelemetryReadoutLine(
                            label = if (isAr) "دورات المحرك" else "Engine RPM",
                            value = rpm?.let { "${it.toInt()}" } ?: "--",
                            valueColor = CockpitColors.TextPrimary,
                            rating = TelemetryEvaluator.rateRpm(rpm),
                            isAr = isAr
                        )
                        TelemetryReadoutLine(
                            label = if (isAr) "حرارة التبريد" else "Coolant Temp",
                            value = coolant?.let { "${it.toInt()}$tempUnit" } ?: "--",
                            valueColor = CockpitColors.AccentCyan,
                            rating = TelemetryEvaluator.rateCoolant(coolant, state.settings.unit),
                            isAr = isAr
                        )
                        TelemetryReadoutLine(
                            label = if (isAr) "جهد البطارية" else "Battery",
                            value = voltage?.let { String.format(Locale.US, "%.1fV", it) } ?: "--",
                            valueColor = CockpitColors.AccentBlue,
                            rating = TelemetryEvaluator.rateBatteryVoltage(voltage),
                            isAr = isAr
                        )
                        val faultCount = report?.dtcs?.size
                        TelemetryReadoutLine(
                            label = strings.faultsCountLabel,
                            value = faultCount?.toString() ?: "--",
                            valueColor = if (faultCount == 0 && report != null) CockpitColors.HealthyGreen else if ((faultCount ?: 0) > 0) CockpitColors.CriticalRed else CockpitColors.TextSecondary,
                            rating = null,
                            isAr = isAr,
                            trailingAlertIcon = when {
                                faultCount == null -> null
                                faultCount > 0 -> {
                                    {
                                        Surface(
                                            color = CockpitColors.CriticalRed.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(0.8.dp, CockpitColors.CriticalRed.copy(alpha = 0.55f))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Warning,
                                                contentDescription = null,
                                                tint = CockpitColors.CriticalRed,
                                                modifier = Modifier
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    .size(14.dp)
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = CockpitColors.HealthyGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Health & Status HUD column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (report != null) {
                            HealthScoreGauge(
                                score = report.healthScore,
                                label = strings.healthScoreLabel,
                                size = 76.dp
                            )
                            val overallText = when {
                                state.scanProgress.isScanning -> if (isAr) "جاري الفحص..." else "Scanning..."
                                report.dtcs.isEmpty() -> if (isAr) "السيارة سليمة" else "Vehicle Healthy"
                                else -> if (isAr) "توجد أعطال (${report.dtcs.size})" else "${report.dtcs.size} Faults"
                            }
                            Text(
                                text = overallText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    state.scanProgress.isScanning -> CockpitColors.AccentCyan
                                    report.dtcs.isEmpty() -> CockpitColors.HealthyGreen
                                    else -> CockpitColors.CriticalRed
                                }
                            )
                        } else {
                            StatusPill(
                                text = when (state.connectionMode) {
                                    ConnectionMode.USB_OTG -> strings.connectedOtg
                                    ConnectionMode.BLUETOOTH -> strings.connectedBluetooth
                                    ConnectionMode.SIMULATION -> strings.connectedSim
                                    ConnectionMode.DISCONNECTED -> strings.disconnected
                                },
                                color = if (isConnected) CockpitColors.HealthyGreen else CockpitColors.WarningAmber
                            )
                            Text(
                                text = if (isAr) "بانتظار الفحص" else "Awaiting Scan",
                                style = MaterialTheme.typography.labelMedium,
                                color = CockpitColors.TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = CockpitColors.SurfaceBorder)
                Spacer(modifier = Modifier.height(10.dp))

                if (report != null) {
                    Text(
                        text = "${formatVehicleDisplayName(report)}  •  ${formatScanTimestamp(report.timestampMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CockpitColors.AccentCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedButton(
                    onClick = { showHistorySheet = true },
                    border = BorderStroke(1.dp, CockpitColors.AccentCyan.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = CockpitColors.AccentCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${strings.previousScansBtn} (${state.scanHistory.size})",
                        fontWeight = FontWeight.Bold,
                        color = CockpitColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = CockpitColors.AccentCyan, modifier = Modifier.size(16.dp))
                }
            }
        }

        // 2. Hero Feature: Interactive 3/4 X-Ray Car Fault Locator Map
        item {
            val detectedBodyStyle = CarZoneMapper.detectBodyStyle(
                vehicleInfo = report?.vehicleInfo,
                adapterDescription = report?.adapterDescription ?: state.activeAdapterLabel
            )
            val detectedVehicleName = report?.let { formatVehicleDisplayName(it) }
                ?: state.activeAdapterLabel.removePrefix("Simulator:").trim().takeIf { it.isNotBlank() }

            CarSchematicVisualizer(
                zoneMap = state.zoneMap,
                selectedZone = state.focusedZone,
                onSelectZone = onSelectZone,
                isScanning = state.scanProgress.isScanning,
                hasScan = report != null,
                isAr = isAr,
                onOpenFaults = onNavigateToFaults,
                onOpenObservations = onNavigateToObservations,
                bodyStyle = detectedBodyStyle,
                vehicleName = detectedVehicleName
            )
        }

        // 3. Prominent Futuristic Scan & Connection Bar
        item {
            val statusColor = when (state.connectionMode) {
                ConnectionMode.USB_OTG, ConnectionMode.BLUETOOTH -> CockpitColors.HealthyGreen
                ConnectionMode.SIMULATION -> CockpitColors.AccentCyan
                ConnectionMode.DISCONNECTED -> if (state.usbAdapters.isNotEmpty()) CockpitColors.WarningAmber else CockpitColors.TextSecondary
            }
            val statusText = when {
                state.connectionState == ConnectionState.CONNECTING || state.connectionState == ConnectionState.REQUESTING_PERMISSION -> strings.connecting
                state.connectionMode == ConnectionMode.USB_OTG -> strings.connectedOtg
                state.connectionMode == ConnectionMode.BLUETOOTH -> strings.connectedBluetooth
                state.connectionMode == ConnectionMode.SIMULATION -> strings.connectedSim
                else -> strings.disconnected
            }

            CockpitCard(borderColor = statusColor.copy(alpha = 0.55f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = when (state.connectionMode) {
                                ConnectionMode.SIMULATION -> Icons.Rounded.Radar
                                ConnectionMode.BLUETOOTH -> Icons.Rounded.BluetoothConnected
                                else -> Icons.Rounded.Usb
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = if (state.activeAdapterLabel.isNotBlank()) {
                                    localizeAdapterLabel(state.activeAdapterLabel, isAr)
                                } else {
                                    strings.connectionTitle
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = CockpitColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (report?.vehicleInfo?.vin != null || report?.vehicleInfo?.make != null) {
                                Text(
                                    text = listOfNotNull(
                                        report.vehicleInfo.make,
                                        report.vehicleInfo.modelYear?.toString(),
                                        report.vehicleInfo.vin
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CockpitColors.AccentCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(text = statusText, color = statusColor)
                        if (!isConnected) {
                            IconButton(onClick = onRefreshUsb, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = strings.refreshUsbBtn,
                                    tint = CockpitColors.TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (state.scanProgress.isScanning) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val progress = state.scanProgress
                    val stepFraction = (progress.stepIndex.toFloat() / progress.totalSteps.coerceAtLeast(1)).coerceIn(0f, 1f)
                    Text(
                        text = "${progress.stepIndex}/${progress.totalSteps}: ${if (isAr) progress.stepLabelAr else progress.stepLabelEn}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CockpitColors.AccentCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { stepFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        color = CockpitColors.AccentCyan,
                        trackColor = CockpitColors.SurfaceElevated
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (state.usbAdapters.isNotEmpty() && !isConnected) {
                    state.usbAdapters.forEach { adapter ->
                        Surface(
                            color = CockpitColors.SurfaceElevated,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CockpitColors.AccentTeal.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = adapter.chipsetLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = CockpitColors.TextPrimary
                                    )
                                    Text(
                                        text = "${adapter.deviceName} (VID:${adapter.vendorId} PID:${adapter.productId})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CockpitColors.TextSecondary
                                    )
                                }
                                Button(
                                    onClick = { onConnectUsb(adapter.deviceId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CockpitColors.AccentTeal),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 46.dp)
                                ) {
                                    Icon(Icons.Rounded.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.connectUsbBtn, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else if (!isConnected) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onStartSimulation,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitColors.AccentCyan,
                                contentColor = CockpitColors.BackgroundDeep
                            )
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = strings.startSimBtn,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        OutlinedButton(
                            onClick = { showBluetoothSheet = true },
                            border = BorderStroke(1.dp, CockpitColors.HealthyGreen.copy(alpha = 0.65f)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = CockpitColors.HealthyGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = strings.connectBluetoothBtn,
                                color = CockpitColors.HealthyGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = { onConnectUsb(null) },
                            border = BorderStroke(1.dp, CockpitColors.AccentCyan.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = strings.connectUsbBtn,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onRunFullScan,
                            enabled = !state.scanProgress.isScanning && !state.isResettingMil,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitColors.AccentCyan,
                                contentColor = CockpitColors.BackgroundDeep
                            )
                        ) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (state.scanProgress.isScanning) strings.scanningInProgress else strings.fullScanBtn,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { showClearMilDialog = true },
                            enabled = !state.scanProgress.isScanning && !state.isResettingMil,
                            border = BorderStroke(
                                1.dp,
                                if (report?.readiness?.milOn == true || report?.dtcs?.isNotEmpty() == true) {
                                    CockpitColors.CriticalRed.copy(alpha = 0.75f)
                                } else {
                                    CockpitColors.WarningAmber.copy(alpha = 0.65f)
                                }
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (report?.readiness?.milOn == true || report?.dtcs?.isNotEmpty() == true) {
                                    CockpitColors.CriticalRed
                                } else {
                                    CockpitColors.WarningAmber
                                }
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp)
                        ) {
                            if (state.isResettingMil) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CockpitColors.CriticalRed
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isAr) "جاري إطفاء لمبة المحرك..." else "Resetting Check Engine...",
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.clearDtcsBtn, fontWeight = FontWeight.Bold)
                            }
                        }
                        OutlinedButton(
                            onClick = onDisconnect,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                        ) {
                            Text(strings.disconnectBtn, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (!state.connectionError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = CockpitColors.CriticalRed.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Error, contentDescription = null, tint = CockpitColors.CriticalRed)
                            Text(
                                text = state.connectionError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CockpitColors.CriticalRed
                            )
                        }
                    }
                }
            }
        }

        // 4. Built-In Smart AI Quick Verdict Card + Export Buttons
        if (report != null) {
            val ai = state.smartAiReport
            if (ai != null) {
                val aiColor = when (ai.verdict) {
                    SafetyVerdict.STOP_IMMEDIATELY -> CockpitColors.CriticalRed
                    SafetyVerdict.CAUTION_REPAIR_SOON -> CockpitColors.WarningAmber
                    SafetyVerdict.SAFE_MONITOR -> CockpitColors.AccentCyan
                    SafetyVerdict.OPTIMAL -> CockpitColors.HealthyGreen
                }
                item {
                    CockpitCard(
                        borderColor = aiColor.copy(alpha = 0.55f),
                        onClick = onNavigateToObservations
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
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = CockpitColors.AccentCyan)
                                Text(
                                    text = strings.aiSectionTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CockpitColors.TextPrimary
                                )
                            }
                            StatusPill(
                                text = if (isAr) "ذكاء مدمج" else "Smart AI",
                                color = CockpitColors.HealthyGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isAr) ai.summaryAr else ai.summaryEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextPrimary
                        )
                        val topHyp = ai.hypotheses.firstOrNull()
                        if (topHyp != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${if (isAr) "السبب المرجح:" else "Top Root Cause:"} ${if (isAr) topHyp.titleAr else topHyp.titleEn} (${topHyp.confidencePercent}%)",
                                style = MaterialTheme.typography.labelMedium,
                                color = aiColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onNavigateToObservations,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 46.dp),
                                border = BorderStroke(1.dp, CockpitColors.AccentCyan.copy(alpha = 0.55f))
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isAr) "اسأل الذكاء الاصطناعي" else "Ask Smart AI",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { ReportExporter.shareTextReport(context, report, state.settings.language) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                ) {
                                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(strings.exportShareBtn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedButton(
                                    onClick = { ReportExporter.printOrSavePdf(context, report, state.settings.language) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                ) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(strings.exportPdfBtn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // Preview of Faults & Top Notes
            if (report.dtcs.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "${strings.faultsCountLabel} (${report.dtcs.size})",
                        trailing = {
                            OutlinedButton(
                                onClick = onNavigateToFaults,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(if (isAr) "كل الأعطال والحلول" else "All Fixes", fontSize = 12.sp)
                            }
                        }
                    )
                }
                items(count = minOf(3, report.dtcs.size)) { idx ->
                    val dtc = report.dtcs[idx]
                    CockpitCard(
                        borderColor = CockpitColors.CriticalRed.copy(alpha = 0.45f),
                        onClick = onNavigateToFaults
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dtc.code,
                                style = MaterialTheme.typography.titleLarge,
                                color = CockpitColors.CriticalRed,
                                fontWeight = FontWeight.ExtraBold
                            )
                            StatusPill(text = dtcStatusLabel(dtc.status, isAr), color = CockpitColors.WarningAmber)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAr) dtc.knowledge.titleAr else dtc.knowledge.titleEn,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAr) dtc.knowledge.fixStepsAr.firstOrNull().orEmpty()
                            else dtc.knowledge.fixStepsEn.firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.HealthyGreen
                        )
                    }
                }
            }

            if (report.observations.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "${strings.notesCountLabel} (${report.observations.size})",
                        trailing = {
                            OutlinedButton(
                                onClick = onNavigateToObservations,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(if (isAr) "كل الملاحظات" else "All Notes", fontSize = 12.sp)
                            }
                        }
                    )
                }
                items(count = minOf(2, report.observations.size)) { idx ->
                    val obs = report.observations[idx]
                    val col = observationSeverityColor(obs.severity)
                    CockpitCard(
                        borderColor = if (obs.severity == ObservationSeverity.CRITICAL) col.copy(alpha = 0.6f) else CockpitColors.SurfaceBorder,
                        onClick = onNavigateToObservations
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isAr) obs.titleAr else obs.titleEn,
                                style = MaterialTheme.typography.titleMedium,
                                color = CockpitColors.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            StatusPill(text = observationSeverityLabel(obs.severity, isAr), color = col)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAr) obs.summaryAr else obs.summaryEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun telemetryRatingColor(level: TelemetryRatingLevel): Color = when (level) {
    TelemetryRatingLevel.EXCELLENT -> CockpitColors.HealthyGreen
    TelemetryRatingLevel.GOOD -> CockpitColors.AccentCyan
    TelemetryRatingLevel.FAIR -> CockpitColors.WarningAmber
    TelemetryRatingLevel.POOR -> CockpitColors.CriticalRed
    TelemetryRatingLevel.IDLE -> CockpitColors.TextSecondary
}

@Composable
private fun LiveCockpitStatusBar(
    isStreaming: Boolean,
    isBusy: Boolean,
    isAr: Boolean,
    onToggle: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "live_dot_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_dot_alpha"
    )

    val statusColor = when {
        isBusy -> CockpitColors.AccentCyan
        isStreaming -> CockpitColors.HealthyGreen
        else -> CockpitColors.WarningAmber
    }
    val statusText = when {
        isBusy -> if (isAr) "جاري الفحص..." else "Scanning..."
        isStreaming -> if (isAr) "بث مباشر للمؤشرات" else "LIVE GAUGES"
        else -> if (isAr) "البث المباشر متوقف" else "Live Paused"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = if (isStreaming && !isBusy) pulseAlpha else 1f))
            )
            Text(
                text = statusText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                maxLines = 1
            )
        }

        Surface(
            onClick = { if (!isBusy) onToggle() },
            enabled = !isBusy,
            color = statusColor.copy(alpha = 0.14f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(0.9.dp, statusColor.copy(alpha = 0.55f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (isStreaming) {
                        if (isAr) "إيقاف مؤقت" else "Pause"
                    } else {
                        if (isAr) "تشغيل البث" else "Go Live"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CockpitColors.TextPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TelemetryReadoutLine(
    label: String,
    value: String,
    valueColor: Color,
    rating: TelemetryRating?,
    isAr: Boolean,
    trailingAlertIcon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            fontSize = 12.sp,
            color = CockpitColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = value,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = valueColor,
                maxLines = 1
            )
            if (trailingAlertIcon != null) {
                trailingAlertIcon()
            } else if (rating != null) {
                val badgeColor = telemetryRatingColor(rating.level)
                Surface(
                    color = badgeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.50f))
                ) {
                    Text(
                        text = if (isAr) rating.labelAr else rating.labelEn,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}
