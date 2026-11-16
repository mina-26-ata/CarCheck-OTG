package com.carchecker.otg.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.ConnectionMode
import com.carchecker.otg.model.DiagnosedDtc
import com.carchecker.otg.model.DtcKnowledgeEntry
import com.carchecker.otg.model.DtcStatus
import com.carchecker.otg.model.DtcSystem
import com.carchecker.otg.ui.components.CockpitCard
import com.carchecker.otg.ui.components.SectionHeader
import com.carchecker.otg.ui.components.StatusPill
import com.carchecker.otg.ui.components.severityColor
import com.carchecker.otg.ui.i18n.AppStrings
import com.carchecker.otg.ui.state.DiagnosticUiState
import com.carchecker.otg.ui.theme.CockpitColors

@Composable
fun FaultsScreen(
    state: DiagnosticUiState,
    strings: AppStrings,
    onSearchLibrary: (String, DtcSystem?) -> Unit,
    onBuildFaultAiInsight: (DtcKnowledgeEntry) -> String,
    onClearDtcs: () -> Unit
) {
    val isAr = state.settings.language == AppLanguage.AR
    val report = state.currentReport
    var selectedTab by remember { mutableIntStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }

    var selectedFault by remember { mutableStateOf<DiagnosedDtc?>(null) }
    var selectedLibraryEntry by remember { mutableStateOf<DtcKnowledgeEntry?>(null) }

    val confirmedDtcs = remember(report) {
        report?.dtcs?.filter { it.status == DtcStatus.CONFIRMED || it.status == DtcStatus.STORED_UDS }.orEmpty()
    }
    val pendingDtcs = remember(report) {
        report?.dtcs?.filter { it.status == DtcStatus.PENDING }.orEmpty()
    }
    val permanentDtcs = remember(report) {
        report?.dtcs?.filter { it.status == DtcStatus.PERMANENT }.orEmpty()
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(strings.clearDtcsDialogTitle) },
            text = { Text(strings.clearDtcsDialogBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearDtcs()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = CockpitColors.CriticalRed)
                ) {
                    Text(strings.confirmBtn, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(strings.cancelBtn)
                }
            }
        )
    }

    selectedFault?.let { dtc ->
        FaultDetailSheet(
            knowledge = dtc.knowledge,
            ecuLabel = "${if (isAr) dtc.ecuNameAr else dtc.ecuNameEn} (${dtc.ecuAddress})",
            statusLabel = dtc.status.name,
            freezeFrame = report?.freezeFrame.orEmpty(),
            aiInsightText = onBuildFaultAiInsight(dtc.knowledge),
            language = state.settings.language,
            strings = strings,
            onDismiss = { selectedFault = null },
            bodyStyle = CarZoneMapper.detectBodyStyle(report?.vehicleInfo, report?.adapterDescription)
        )
    }

    selectedLibraryEntry?.let { entry ->
        FaultDetailSheet(
            knowledge = entry,
            ecuLabel = null,
            statusLabel = entry.system.name,
            freezeFrame = emptyList(),
            aiInsightText = onBuildFaultAiInsight(entry),
            language = state.settings.language,
            strings = strings,
            onDismiss = { selectedLibraryEntry = null },
            bodyStyle = CarZoneMapper.detectBodyStyle(report?.vehicleInfo, report?.adapterDescription)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SectionHeader(
            title = strings.faultsHeader
        )

        if (state.connectionMode != ConnectionMode.DISCONNECTED) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showClearDialog = true },
                enabled = !state.scanProgress.isScanning,
                border = BorderStroke(1.dp, CockpitColors.CriticalRed.copy(alpha = 0.65f)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CockpitColors.CriticalRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  ${strings.clearDtcsBtn}", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val tabs = listOf(
            "${strings.tabConfirmed} (${confirmedDtcs.size})",
            "${strings.tabPending} (${pendingDtcs.size})",
            "${strings.tabPermanent} (${permanentDtcs.size})",
            strings.tabLibrary
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tabs.size) { idx ->
                FilterChip(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    label = { Text(tabs[idx]) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedTab == 3) {
            // Searchable Offline DTC Library
            OutlinedTextField(
                value = state.libraryQuery,
                onValueChange = { onSearchLibrary(it, state.librarySystemFilter) },
                placeholder = { Text(strings.searchDtcHint) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = state.librarySystemFilter == null,
                        onClick = { onSearchLibrary(state.libraryQuery, null) },
                        label = { Text(strings.allCategories) }
                    )
                }
                items(DtcSystem.entries) { sys ->
                    FilterChip(
                        selected = state.librarySystemFilter == sys,
                        onClick = { onSearchLibrary(state.libraryQuery, sys) },
                        label = { Text(sys.name) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.libraryResults, key = { it.code }) { entry ->
                    val col = severityColor(entry.severity)
                    CockpitCard(onClick = { selectedLibraryEntry = entry }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry.code,
                                style = MaterialTheme.typography.titleLarge,
                                color = col,
                                fontWeight = FontWeight.ExtraBold
                            )
                            StatusPill(text = entry.system.name, color = col)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAr) entry.titleAr else entry.titleEn,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAr) entry.descriptionAr else entry.descriptionEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextSecondary,
                            maxLines = 2
                        )
                    }
                }
            }
        } else {
            val activeList = when (selectedTab) {
                0 -> confirmedDtcs
                1 -> pendingDtcs
                else -> permanentDtcs
            }

            if (activeList.isEmpty()) {
                CockpitCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = CockpitColors.HealthyGreen,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = strings.noFaultsFound,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.TextPrimary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(activeList, key = { "${it.code}_${it.status}_${it.ecuAddress}" }) { dtc ->
                        val col = severityColor(dtc.knowledge.severity)
                        CockpitCard(
                            borderColor = col.copy(alpha = 0.55f),
                            onClick = { selectedFault = dtc }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = dtc.code,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = col,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                StatusPill(text = dtc.knowledge.severity.name, color = col)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isAr) dtc.knowledge.titleAr else dtc.knowledge.titleEn,
                                style = MaterialTheme.typography.titleMedium,
                                color = CockpitColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val faultZone = CarZoneMapper.zoneForDtc(dtc)
                            Text(
                                text = "${strings.ecuModuleLabel}: ${if (isAr) dtc.ecuNameAr else dtc.ecuNameEn} (${dtc.ecuAddress}) • ${CarZoneMapper.zoneTitle(faultZone, isAr)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = CockpitColors.AccentCyan
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isAr) dtc.knowledge.fixStepsAr.firstOrNull().orEmpty()
                                else dtc.knowledge.fixStepsEn.firstOrNull().orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = CockpitColors.HealthyGreen
                            )
                        }
                    }
                }
            }
        }
    }
}
