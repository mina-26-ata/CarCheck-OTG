package com.carchecker.otg.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.DtcKnowledgeEntry
import com.carchecker.otg.model.FreezeFrameReading
import com.carchecker.otg.model.VehicleBodyStyle
import com.carchecker.otg.ui.components.MiniCarZoneLocator
import com.carchecker.otg.ui.components.StatusPill
import com.carchecker.otg.ui.components.dtcSeverityLabel
import com.carchecker.otg.ui.components.severityColor
import com.carchecker.otg.ui.i18n.AppStrings
import com.carchecker.otg.ui.theme.CockpitColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaultDetailSheet(
    knowledge: DtcKnowledgeEntry,
    ecuLabel: String?,
    statusLabel: String?,
    freezeFrame: List<FreezeFrameReading>,
    aiInsightText: String?,
    language: AppLanguage,
    strings: AppStrings,
    onDismiss: () -> Unit,
    bodyStyle: VehicleBodyStyle = VehicleBodyStyle.SEDAN
) {
    val isAr = language == AppLanguage.AR
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sevColor = severityColor(knowledge.severity)
    val targetZone = CarZoneMapper.zoneForKnowledge(knowledge)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CockpitColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = knowledge.code,
                        style = MaterialTheme.typography.headlineMedium,
                        color = sevColor,
                        fontWeight = FontWeight.ExtraBold
                    )
                    StatusPill(text = dtcSeverityLabel(knowledge.severity, isAr), color = sevColor)
                    if (!statusLabel.isNullOrBlank()) {
                        StatusPill(text = statusLabel, color = CockpitColors.InfoBlue)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = CockpitColors.TextSecondary)
                }
            }

            StatusPill(
                text = if (knowledge.isFromBuiltInDb) strings.builtInDbBadge else strings.saeDecoderBadge,
                color = if (knowledge.isFromBuiltInDb) CockpitColors.AccentTeal else CockpitColors.AccentBlue
            )

            Text(
                text = if (isAr) knowledge.titleAr else knowledge.titleEn,
                style = MaterialTheme.typography.titleLarge,
                color = CockpitColors.TextPrimary
            )

            if (!ecuLabel.isNullOrBlank()) {
                Text(
                    text = "${strings.ecuModuleLabel}: $ecuLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CockpitColors.AccentBlue
                )
            }

            Text(
                text = if (isAr) knowledge.descriptionAr else knowledge.descriptionEn,
                style = MaterialTheme.typography.bodyLarge,
                color = CockpitColors.TextSecondary
            )

            // Mini X-Ray Vehicle Fault Location Map
            MiniCarZoneLocator(
                targetZone = targetZone,
                accentColor = sevColor,
                isAr = isAr,
                bodyStyle = bodyStyle
            )

            // Built-in Smart AI Contextual Insight
            if (!aiInsightText.isNullOrBlank()) {
                Surface(
                    color = CockpitColors.AccentCyan.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CockpitColors.AccentCyan.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = CockpitColors.AccentCyan, modifier = Modifier.size(18.dp))
                            Text(
                                text = if (isAr) "تحليل الذكاء الاصطناعي المدمج" else "Built-in Smart AI Diagnosis",
                                style = MaterialTheme.typography.titleMedium,
                                color = CockpitColors.AccentCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = aiInsightText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextPrimary
                        )
                    }
                }
            }

            // Driving Safety Banner
            val safeColor = if (knowledge.canDriveSafely) CockpitColors.WarningAmber else CockpitColors.CriticalRed
            Surface(
                color = safeColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (knowledge.canDriveSafely) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = safeColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (knowledge.canDriveSafely) strings.safeToDrive else strings.doNotDriveWarn,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = safeColor
                    )
                }
            }

            // Probable Causes
            BulletSection(
                title = strings.causesTitle,
                items = if (isAr) knowledge.causesAr else knowledge.causesEn,
                accent = CockpitColors.WarningAmber
            )

            // Typical Symptoms
            BulletSection(
                title = strings.symptomsTitle,
                items = if (isAr) knowledge.symptomsAr else knowledge.symptomsEn,
                accent = CockpitColors.InfoBlue
            )

            // Fix Steps
            Surface(
                color = CockpitColors.SurfaceElevated,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Build, contentDescription = null, tint = CockpitColors.AccentTeal)
                        Text(
                            text = strings.fixStepsTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.AccentTeal
                        )
                    }
                    val steps = if (isAr) knowledge.fixStepsAr else knowledge.fixStepsEn
                    steps.forEachIndexed { idx, step ->
                        Text(
                            text = "${idx + 1}. $step",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CockpitColors.TextPrimary
                        )
                    }
                    HorizontalDivider(color = CockpitColors.SurfaceBorder)
                    Text(
                        text = "${strings.difficultyLabel}: ${if (isAr) knowledge.estimatedDifficultyAr else knowledge.estimatedDifficultyEn}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CockpitColors.TextSecondary
                    )
                }
            }

            // Freeze Frame snapshot (when available)
            if (freezeFrame.isNotEmpty()) {
                Surface(
                    color = CockpitColors.SurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = strings.freezeFrameTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.AccentBlue
                        )
                        freezeFrame.forEach { ff ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isAr) ff.labelAr else ff.labelEn,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CockpitColors.TextSecondary
                                )
                                Text(
                                    text = "${ff.valueDisplay} ${ff.unit}".trim(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CockpitColors.TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BulletSection(
    title: String,
    items: List<String>,
    accent: androidx.compose.ui.graphics.Color
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = accent
        )
        items.forEach { line ->
            Text(
                text = "• $line",
                style = MaterialTheme.typography.bodyMedium,
                color = CockpitColors.TextPrimary
            )
        }
    }
}
