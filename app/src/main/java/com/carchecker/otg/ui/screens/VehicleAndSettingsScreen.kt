package com.carchecker.otg.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carchecker.otg.BuildConfig
import com.carchecker.otg.data.export.ReportExporter
import com.carchecker.otg.data.export.SavedPdfResult
import com.carchecker.otg.data.update.AppUpdateState
import kotlinx.coroutines.launch
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.DemoProfile
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.ui.components.CockpitCard
import com.carchecker.otg.ui.components.ScanHistoryItemCard
import com.carchecker.otg.ui.components.SectionHeader
import com.carchecker.otg.ui.components.StatusPill
import com.carchecker.otg.ui.components.localizeCountry
import com.carchecker.otg.ui.i18n.AppStrings
import com.carchecker.otg.ui.state.DiagnosticUiState
import com.carchecker.otg.ui.theme.CockpitColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleAndSettingsScreen(
    state: DiagnosticUiState,
    strings: AppStrings,
    onSetLanguage: (AppLanguage) -> Unit,
    onSetUnit: (MeasurementUnit) -> Unit,
    onSetBaudRate: (Int) -> Unit,
    onSetDemoProfile: (DemoProfile) -> Unit,
    onSaveGeminiConfig: (String, String) -> Unit,
    onCheckForUpdates: () -> Unit,
    onStartInAppUpdate: () -> Unit,
    onLoadHistoryReport: (FullScanReport) -> Unit,
    onDeleteHistoryReport: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAr = state.settings.language == AppLanguage.AR
    val report = state.currentReport
    var apiKeyDraft by remember(state.settings.geminiApiKey) { mutableStateOf(state.settings.geminiApiKey) }
    var modelDraft by remember(state.settings.geminiModel) { mutableStateOf(state.settings.geminiModel) }
    var savedPdfDialog by remember { mutableStateOf<Pair<SavedPdfResult, FullScanReport>?>(null) }

    savedPdfDialog?.let { (saved, savedReport) ->
        AlertDialog(
            onDismissRequest = { savedPdfDialog = null },
            containerColor = CockpitColors.Surface,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CockpitColors.HealthyGreen)
                    Text(
                        text = if (isAr) "تم تحميل التقرير PDF" else "PDF Report Downloaded",
                        color = CockpitColors.HealthyGreen,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${saved.fileName}\n${if (isAr) saved.displayLocationAr else saved.displayLocationEn}",
                        color = CockpitColors.TextPrimary,
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                ReportExporter.openPdfUri(context, saved.uri, state.settings.language)
                                savedPdfDialog = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitColors.AccentCyan,
                                contentColor = CockpitColors.BackgroundDeep
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (isAr) "فتح" else "Open", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                ReportExporter.sharePdfUri(context, saved.uri, savedReport, state.settings.language)
                                savedPdfDialog = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (isAr) "مشاركة" else "Share", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { savedPdfDialog = null }) {
                    Text(if (isAr) "إغلاق" else "Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Vehicle Identity & Detected ECUs
        item {
            SectionHeader(title = strings.vehicleInfoTitle)
        }
        item {
            val v = report?.vehicleInfo
            CockpitCard {
                if (v == null) {
                    Text(
                        text = strings.noScanYetDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CockpitColors.TextSecondary
                    )
                } else {
                    InfoRow(strings.vinLabel, v.vin ?: "-")
                    InfoRow(strings.makeLabel, v.make ?: "-")
                    InfoRow(strings.countryLabel, localizeCountry(v.country, isAr) ?: "-")
                    InfoRow(strings.yearLabel, v.modelYear?.toString() ?: "-")
                    InfoRow(strings.protocolLabel, v.protocolName ?: "-")
                    InfoRow(strings.calidLabel, v.calibrationId ?: "-")
                    InfoRow(strings.cvnLabel, v.cvn ?: "-")
                    v.odometerKm?.let {
                        val unitText = if (state.settings.unit == MeasurementUnit.METRIC) {
                            if (isAr) "كم" else "km"
                        } else {
                            if (isAr) "ميل" else "mi"
                        }
                        val distVal = if (state.settings.unit == MeasurementUnit.METRIC) it.toInt() else (it * 0.621371).toInt()
                        InfoRow(strings.odometerLabel, "$distVal $unitText")
                    }

                    if (v.detectedEcus.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = strings.detectedEcusTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.AccentTeal
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        v.detectedEcus.forEach { ecu ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isAr) ecu.nameAr else ecu.nameEn,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CockpitColors.TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                StatusPill(text = ecu.address, color = CockpitColors.AccentBlue)
                            }
                        }
                    }
                }
            }
        }

        // 2. Settings
        item {
            SectionHeader(title = strings.settingsTitle)
        }
        item {
            CockpitCard {
                Text(strings.languageLabel, style = MaterialTheme.typography.titleMedium, color = CockpitColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = state.settings.language == AppLanguage.AR,
                        onClick = { onSetLanguage(AppLanguage.AR) },
                        label = { Text("العربية") }
                    )
                    FilterChip(
                        selected = state.settings.language == AppLanguage.EN,
                        onClick = { onSetLanguage(AppLanguage.EN) },
                        label = { Text("English") }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(strings.unitsLabel, style = MaterialTheme.typography.titleMedium, color = CockpitColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = state.settings.unit == MeasurementUnit.METRIC,
                        onClick = { onSetUnit(MeasurementUnit.METRIC) },
                        label = { Text(strings.metricLabel) }
                    )
                    FilterChip(
                        selected = state.settings.unit == MeasurementUnit.IMPERIAL,
                        onClick = { onSetUnit(MeasurementUnit.IMPERIAL) },
                        label = { Text(strings.imperialLabel) }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(strings.baudRateLabel, style = MaterialTheme.typography.titleMedium, color = CockpitColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                val baudChoices = listOf(0, 38400, 115200, 9600, 57600)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(baudChoices) { baud ->
                        FilterChip(
                            selected = state.settings.preferredBaudRate == baud,
                            onClick = { onSetBaudRate(baud) },
                            label = { Text(if (baud == 0) strings.baudAuto else "$baud") }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(strings.demoProfileLabel, style = MaterialTheme.typography.titleMedium, color = CockpitColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DemoProfile.entries.forEach { profile ->
                        val label = when (profile) {
                            DemoProfile.HEALTHY -> if (isAr) "سيارة سليمة (2022 تويوتا كامري)" else "Healthy Car (2022 Toyota Camry)"
                            DemoProfile.ENGINE_MISFIRE_FUEL -> if (isAr) "تفتفة وتسريب هواء (P0300 + P0171 + P0420)" else "Engine Misfire & Lean Trim (P0300, P0171)"
                            DemoProfile.TRANS_ABS_COOLING -> if (isAr) "حرارة وقير وABS وضعف بطارية (P0128 + P0700 + C0035)" else "Cooling, Trans, ABS & Low Battery"
                        }
                        FilterChip(
                            selected = state.settings.demoProfile == profile,
                            onClick = { onSetDemoProfile(profile) },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = CockpitColors.SurfaceBorder)
                Spacer(Modifier.height(12.dp))
                Text(strings.geminiKeyLabel, style = MaterialTheme.typography.titleMedium, color = CockpitColors.AccentCyan)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = strings.aiNoKeyHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = CockpitColors.TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    placeholder = { Text(strings.geminiKeyHint) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = modelDraft,
                        onValueChange = { modelDraft = it },
                        label = { Text(if (isAr) "اسم النموذج" else "Model") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { onSaveGeminiConfig(apiKeyDraft, modelDraft) }) {
                        Text(strings.saveKeyBtn, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2.5 App Version & GitHub Releases Update Checker
        item {
            SectionHeader(title = strings.updatesSectionTitle)
        }
        item {
            val upd = state.updateState
            val borderColor = when (upd) {
                is AppUpdateState.UpdateAvailable -> CockpitColors.HealthyGreen.copy(alpha = 0.65f)
                is AppUpdateState.UpToDate -> CockpitColors.AccentCyan.copy(alpha = 0.45f)
                is AppUpdateState.Error -> CockpitColors.WarningAmber.copy(alpha = 0.55f)
                else -> CockpitColors.SurfaceBorder
            }
            CockpitCard(borderColor = borderColor) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${strings.appVersionLabel}: v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleMedium,
                        color = CockpitColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    when (upd) {
                        is AppUpdateState.UpToDate -> StatusPill(
                            text = strings.upToDateLabel,
                            color = CockpitColors.HealthyGreen
                        )
                        is AppUpdateState.UpdateAvailable -> StatusPill(
                            text = "v${upd.latestVersion}",
                            color = CockpitColors.AccentCyan
                        )
                        else -> Unit
                    }
                }

                when (upd) {
                    is AppUpdateState.UpdateAvailable -> {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = CockpitColors.HealthyGreen.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CockpitColors.HealthyGreen.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${strings.updateAvailableLabel} (${upd.releaseTitle})",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = CockpitColors.HealthyGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                if (upd.releaseNotes.isNotBlank()) {
                                    Text(
                                        text = upd.releaseNotes.take(280),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CockpitColors.TextPrimary
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onStartInAppUpdate,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitColors.HealthyGreen,
                                contentColor = CockpitColors.BackgroundDeep
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp)
                        ) {
                            Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.downloadUpdateBtn, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    is AppUpdateState.UpToDate -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isAr) {
                                "أنت تستخدم أحدث إصدار من التطبيق (v${upd.currentVersion})."
                            } else {
                                "You have the latest release installed (v${upd.currentVersion})."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.HealthyGreen
                        )
                    }
                    is AppUpdateState.Error -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isAr) {
                                "تعذر الاتصال بخادم التحديثات: ${upd.message}"
                            } else {
                                "Unable to reach update server: ${upd.message}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.WarningAmber
                        )
                    }
                    else -> Unit
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = upd !is AppUpdateState.Checking,
                    border = BorderStroke(1.dp, CockpitColors.AccentCyan.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    if (upd is AppUpdateState.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = CockpitColors.AccentCyan
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(strings.checkingUpdatesLabel, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = null, tint = CockpitColors.AccentCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.checkUpdatesBtn, color = CockpitColors.AccentCyan, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 3. Scan History
        item {
            SectionHeader(
                title = "${strings.scanHistoryTitle} (${state.scanHistory.size})",
                subtitle = if (state.scanHistory.isNotEmpty()) strings.historyLongPressHint else null
            )
        }
        if (state.scanHistory.isEmpty()) {
            item {
                CockpitCard {
                    Text(
                        text = strings.noHistoryHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CockpitColors.TextSecondary
                    )
                }
            }
        } else {
            items(state.scanHistory, key = { it.id }) { item ->
                ScanHistoryItemCard(
                    report = item,
                    isActive = item.id == state.currentReport?.id,
                    isAr = isAr,
                    strings = strings,
                    onOpen = { onLoadHistoryReport(item) },
                    onDelete = { onDeleteHistoryReport(item.id) },
                    onDownloadPdf = {
                        scope.launch {
                            val ai = if (item.id == state.currentReport?.id) state.smartAiReport else null
                            ReportExporter.downloadPdfReport(context, item, ai, state.settings.language)
                                .onSuccess { savedPdfDialog = it to item }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = CockpitColors.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = CockpitColors.TextPrimary
        )
    }
    HorizontalDivider(color = CockpitColors.SurfaceBorder.copy(alpha = 0.4f))
}
