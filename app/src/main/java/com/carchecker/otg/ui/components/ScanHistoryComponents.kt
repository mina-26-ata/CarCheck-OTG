package com.carchecker.otg.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.ui.i18n.AppStrings
import com.carchecker.otg.ui.theme.CockpitColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatVehicleDisplayName(report: FullScanReport): String {
    val cleanAdapter = report.adapterDescription.trim()
    val makeYear = listOfNotNull(
        report.vehicleInfo.make?.takeIf { it.isNotBlank() && it != "Unknown" },
        report.vehicleInfo.modelYear?.toString()
    ).joinToString(" ")
    return makeYear.ifBlank { cleanAdapter.ifBlank { "OBD-II Vehicle" } }
}

fun formatScanTimestamp(timestampMs: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd  •  HH:mm", Locale.US)
    return fmt.format(Date(timestampMs))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanHistoryBottomSheet(
    history: List<FullScanReport>,
    activeReportId: String?,
    isAr: Boolean,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSelectReport: (FullScanReport) -> Unit,
    onDeleteReport: (String) -> Unit,
    onDownloadPdf: ((FullScanReport) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CockpitColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = CockpitColors.AccentCyan
                    )
                    Text(
                        text = "${strings.previousScansBtn} (${history.size})",
                        style = MaterialTheme.typography.titleLarge,
                        color = CockpitColors.TextPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = CockpitColors.TextSecondary
                    )
                }
            }

            if (history.isEmpty()) {
                CockpitCard {
                    Text(
                        text = strings.noHistoryHint,
                        style = MaterialTheme.typography.bodyLarge,
                        color = CockpitColors.TextSecondary
                    )
                }
            } else {
                Text(
                    text = strings.historyLongPressHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = CockpitColors.AccentCyan
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(history, key = { it.id }) { item ->
                        ScanHistoryItemCard(
                            report = item,
                            isActive = item.id == activeReportId,
                            isAr = isAr,
                            strings = strings,
                            onOpen = {
                                onSelectReport(item)
                                onDismiss()
                            },
                            onDelete = { onDeleteReport(item.id) },
                            onDownloadPdf = onDownloadPdf?.let { cb -> { cb(item) } }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScanHistoryItemCard(
    report: FullScanReport,
    isActive: Boolean,
    isAr: Boolean,
    strings: AppStrings,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDownloadPdf: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var showLongPressDialog by remember { mutableStateOf(false) }

    val carName = remember(report) { formatVehicleDisplayName(report) }
    val dateStr = remember(report.timestampMs) { formatScanTimestamp(report.timestampMs) }
    val scoreColor = when {
        report.healthScore >= 85 -> CockpitColors.HealthyGreen
        report.healthScore >= 60 -> CockpitColors.WarningAmber
        else -> CockpitColors.CriticalRed
    }

    if (showLongPressDialog) {
        AlertDialog(
            onDismissRequest = { showLongPressDialog = false },
            containerColor = CockpitColors.Surface,
            title = {
                Text(
                    text = strings.recordOptionsTitle,
                    color = CockpitColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = carName,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.AccentCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextSecondary
                        )
                    }
                    Button(
                        onClick = {
                            showLongPressDialog = false
                            onOpen()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CockpitColors.AccentCyan,
                            contentColor = CockpitColors.BackgroundDeep
                        )
                    ) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.openScanAction, fontWeight = FontWeight.Bold)
                    }
                    if (onDownloadPdf != null) {
                        Button(
                            onClick = {
                                showLongPressDialog = false
                                onDownloadPdf()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitColors.HealthyGreen,
                                contentColor = CockpitColors.BackgroundDeep
                            )
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.exportPdfBtn, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            showLongPressDialog = false
                            onDelete()
                        },
                        border = BorderStroke(1.dp, CockpitColors.CriticalRed.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CockpitColors.CriticalRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.deleteAction, fontWeight = FontWeight.ExtraBold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLongPressDialog = false }) {
                    Text(strings.cancelBtn)
                }
            }
        )
    }

    val borderCol = if (isActive) CockpitColors.AccentCyan.copy(alpha = 0.8f) else CockpitColors.SurfaceBorder
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    showLongPressDialog = true
                }
            ),
        shape = shape,
        color = CockpitColors.SurfaceElevated,
        border = BorderStroke(1.dp, borderCol)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            CockpitColors.SurfaceElevated,
                            CockpitColors.BackgroundDeep
                        )
                    )
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(scoreColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DirectionsCar,
                    contentDescription = null,
                    tint = scoreColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = carName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CockpitColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        tint = CockpitColors.TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CockpitColors.TextSecondary,
                        maxLines = 1
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatusPill(
                    text = "${report.healthScore}%",
                    color = scoreColor
                )
                val dtcLabel = if (report.dtcs.isEmpty()) {
                    if (isAr) "سليم" else "Clean"
                } else {
                    if (isAr) "${report.dtcs.size} أعطال" else "${report.dtcs.size} DTCs"
                }
                Text(
                    text = dtcLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (report.dtcs.isEmpty()) CockpitColors.HealthyGreen else CockpitColors.CriticalRed,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
