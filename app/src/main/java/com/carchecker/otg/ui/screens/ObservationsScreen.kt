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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.SafetyVerdict
import com.carchecker.otg.ui.components.CockpitCard
import com.carchecker.otg.ui.components.SectionHeader
import com.carchecker.otg.ui.components.StatusPill
import com.carchecker.otg.ui.components.observationSeverityColor
import com.carchecker.otg.ui.components.observationSeverityLabel
import com.carchecker.otg.ui.i18n.AppStrings
import com.carchecker.otg.ui.state.DiagnosticUiState
import com.carchecker.otg.ui.theme.CockpitColors

@Composable
fun ObservationsScreen(
    state: DiagnosticUiState,
    strings: AppStrings,
    onAskLocalAi: (String) -> Unit,
    onClearAiChat: () -> Unit,
    onRunAiAnalysis: () -> Unit
) {
    val isAr = state.settings.language == AppLanguage.AR
    val report = state.currentReport
    val smartAi = state.smartAiReport
    var questionDraft by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeader(title = strings.observationsHeader)
        }

        if (report == null) {
            item {
                CockpitCard {
                    Text(
                        text = strings.noScanYetDesc,
                        style = MaterialTheme.typography.bodyLarge,
                        color = CockpitColors.TextSecondary
                    )
                }
            }
            return@LazyColumn
        }

        // 1. Built-In Smart AI Diagnostic Hub
        item {
            CockpitCard(borderColor = CockpitColors.AccentCyan.copy(alpha = 0.65f)) {
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
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = CockpitColors.AccentCyan
                        )
                        Text(
                            text = strings.aiSectionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    StatusPill(
                        text = if (isAr) "تحليل ذكي" else "Smart AI",
                        color = CockpitColors.HealthyGreen
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.aiKeylessBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = CockpitColors.AccentCyan
                )

                if (smartAi != null) {
                    val verdictColor = when (smartAi.verdict) {
                        SafetyVerdict.STOP_IMMEDIATELY -> CockpitColors.CriticalRed
                        SafetyVerdict.CAUTION_REPAIR_SOON -> CockpitColors.WarningAmber
                        SafetyVerdict.SAFE_MONITOR -> CockpitColors.AccentCyan
                        SafetyVerdict.OPTIMAL -> CockpitColors.HealthyGreen
                    }
                    val verdictText = when (smartAi.verdict) {
                        SafetyVerdict.STOP_IMMEDIATELY -> if (isAr) "خطورة عالية — افحص فوراً" else "High Risk — Stop & Inspect"
                        SafetyVerdict.CAUTION_REPAIR_SOON -> if (isAr) "قيادة بحذر — أصلح قريباً" else "Caution — Repair Soon"
                        SafetyVerdict.SAFE_MONITOR -> if (isAr) "آمن مع المتابعة" else "Safe — Monitor"
                        SafetyVerdict.OPTIMAL -> if (isAr) "حالة ممتازة" else "Optimal Condition"
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = verdictColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, verdictColor.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = verdictText,
                                style = MaterialTheme.typography.titleMedium,
                                color = verdictColor,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = if (isAr) smartAi.summaryAr else smartAi.summaryEn,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CockpitColors.TextPrimary
                            )
                        }
                    }

                    // Root-Cause Correlation Hypotheses
                    if (smartAi.hypotheses.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = strings.aiRootCauseTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.AccentCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        smartAi.hypotheses.forEach { hyp ->
                            Surface(
                                color = CockpitColors.SurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CockpitColors.SurfaceBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
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
                                        Text(
                                            text = if (isAr) hyp.titleAr else hyp.titleEn,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = CockpitColors.TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        StatusPill(
                                            text = "${hyp.confidencePercent}%",
                                            color = CockpitColors.AccentCyan
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { (hyp.confidencePercent / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp),
                                        color = CockpitColors.AccentCyan,
                                        trackColor = CockpitColors.BackgroundDeep
                                    )
                                    Text(
                                        text = "${if (isAr) "المنطقة:" else "Zone:"} ${CarZoneMapper.zoneTitle(hyp.zone, isAr)}" +
                                            if (hyp.relatedCodes.isNotEmpty()) " • ${hyp.relatedCodes.joinToString(", ")}" else "",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CockpitColors.WarningAmber,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (isAr) hyp.reasoningAr else hyp.reasoningEn,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CockpitColors.TextSecondary
                                    )
                                    val checks = if (isAr) hyp.firstChecksAr else hyp.firstChecksEn
                                    checks.forEach { check ->
                                        Text(
                                            text = "✓ $check",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = CockpitColors.HealthyGreen
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Cost-First Repair Plan
                    val plan = if (isAr) smartAi.priorityStepsAr else smartAi.priorityStepsEn
                    if (plan.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.aiRepairPlanTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = CockpitColors.HealthyGreen
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        plan.forEachIndexed { idx, step ->
                            Text(
                                text = "${idx + 1}. $step",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CockpitColors.TextPrimary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }

                    val prePurchase = if (isAr) smartAi.prePurchaseNoteAr else smartAi.prePurchaseNoteEn
                    if (!prePurchase.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = CockpitColors.AccentBlue.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CockpitColors.AccentBlue.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = prePurchase,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CockpitColors.AccentBlue,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                // Interactive Smart AI Chat
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = CockpitColors.SurfaceBorder)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAr) "محادثة المساعد الذكي" else "Interactive Smart AI Q&A",
                        style = MaterialTheme.typography.titleMedium,
                        color = CockpitColors.TextPrimary
                    )
                    if (state.aiChatHistory.isNotEmpty()) {
                        IconButton(onClick = onClearAiChat, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                tint = CockpitColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                val quickPrompts = if (isAr) {
                    listOf(
                        "هل القيادة آمنة الآن؟",
                        "أبدأ بأيه في التصليح؟",
                        "وين مكان الأعطال بالسيارة؟",
                        "ما السبب الأهم؟",
                        "فحص ما قبل الشراء"
                    )
                } else {
                    listOf(
                        "Is it safe to drive?",
                        "Where to start repair?",
                        "Where are the faults located?",
                        "What is the main root cause?",
                        "Pre-purchase check"
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickPrompts) { prompt ->
                        AssistChip(
                            onClick = { onAskLocalAi(prompt) },
                            label = { Text(prompt, fontSize = 12.sp) }
                        )
                    }
                }

                if (state.aiChatHistory.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.aiChatHistory.forEach { msg ->
                            Surface(
                                color = if (msg.isUser) CockpitColors.AccentCyan.copy(alpha = 0.16f) else CockpitColors.SurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (msg.isUser) CockpitColors.AccentCyan.copy(alpha = 0.45f) else CockpitColors.SurfaceBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CockpitColors.TextPrimary,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = questionDraft,
                        onValueChange = { questionDraft = it },
                        placeholder = { Text(strings.aiAskPlaceholder, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (questionDraft.isNotBlank()) {
                                onAskLocalAi(questionDraft)
                                questionDraft = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CockpitColors.AccentCyan,
                            contentColor = CockpitColors.BackgroundDeep
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = strings.aiAskBtn, modifier = Modifier.size(18.dp))
                    }
                }

                // Optional Gemini Cloud Bonus (strictly optional, never required)
                Spacer(modifier = Modifier.height(10.dp))
                if (state.settings.geminiApiKey.isBlank()) {
                    Text(
                        text = strings.aiNoKeyHint,
                        style = MaterialTheme.typography.labelMedium,
                        color = CockpitColors.TextSecondary
                    )
                } else {
                    OutlinedButton(
                        onClick = onRunAiAnalysis,
                        enabled = !state.isAiAnalyzing,
                        border = BorderStroke(1.dp, CockpitColors.AccentBlue.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isAiAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CockpitColors.AccentBlue
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(strings.aiAnalyzing)
                        } else {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.aiPromptBtn, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (!state.aiError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.aiError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CockpitColors.CriticalRed
                    )
                }

                if (!report.aiAnalysisText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = CockpitColors.SurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CockpitColors.AccentBlue.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = report.aiAnalysisText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        // 2. Smart Health Observations + Solutions
        items(report.observations, key = { it.id }) { obs ->
            val col = observationSeverityColor(obs.severity)
            CockpitCard(borderColor = col.copy(alpha = 0.55f)) {
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

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isAr) obs.summaryAr else obs.summaryEn,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CockpitColors.TextSecondary
                )

                if (!obs.measuredValue.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = CockpitColors.SurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${strings.measuredLabel}: ${obs.measuredValue}",
                                style = MaterialTheme.typography.labelMedium,
                                color = col,
                                fontWeight = FontWeight.Bold
                            )
                            if (!obs.normalRange.isNullOrBlank()) {
                                Text(
                                    text = "${strings.normalRangeLabel}: ${obs.normalRange}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CockpitColors.TextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${strings.impactLabel}: ${if (isAr) obs.impactAr else obs.impactEn}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CockpitColors.TextPrimary
                )

                val steps = if (isAr) obs.resolutionStepsAr else obs.resolutionStepsEn
                if (steps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = CockpitColors.SurfaceBorder)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = strings.solutionsLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = CockpitColors.HealthyGreen
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    steps.forEachIndexed { idx, step ->
                        Text(
                            text = "${idx + 1}. $step",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextPrimary,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // 3. I/M Readiness & Emissions Monitors
        report.readiness?.let { readiness ->
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(title = strings.readinessSectionTitle)
            }
            item {
                CockpitCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (readiness.milOn) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = if (readiness.milOn) CockpitColors.CriticalRed else CockpitColors.HealthyGreen
                            )
                            Text(
                                text = if (readiness.milOn) strings.milOnText else strings.milOffText,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (readiness.milOn) CockpitColors.CriticalRed else CockpitColors.HealthyGreen
                            )
                        }
                    }

                    if (readiness.distanceSinceClearKm != null || readiness.warmupsSinceClear != null || readiness.distanceWithMilKm != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = CockpitColors.SurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val kmUnit = if (isAr) "كم" else "km"
                                readiness.distanceWithMilKm?.let {
                                    ReadinessStatRow(strings.distWithMilLabel, "$it $kmUnit")
                                }
                                readiness.distanceSinceClearKm?.let {
                                    ReadinessStatRow(strings.distSinceClearLabel, "$it $kmUnit")
                                }
                                readiness.warmupsSinceClear?.let {
                                    ReadinessStatRow(strings.warmupsLabel, "$it")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val supported = readiness.monitors.filter { it.supported }
                    supported.forEach { mon ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isAr) mon.nameAr else mon.nameEn,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CockpitColors.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (mon.completed) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassTop,
                                    contentDescription = null,
                                    tint = if (mon.completed) CockpitColors.HealthyGreen else CockpitColors.WarningAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (mon.completed) {
                                        if (isAr) "مكتمل" else "Ready"
                                    } else {
                                        if (isAr) "غير مكتمل" else "Incomplete"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (mon.completed) CockpitColors.HealthyGreen else CockpitColors.WarningAmber
                                )
                            }
                        }
                        HorizontalDivider(color = CockpitColors.SurfaceBorder.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = CockpitColors.TextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = CockpitColors.TextPrimary)
    }
}
