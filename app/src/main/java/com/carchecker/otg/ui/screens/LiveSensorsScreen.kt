package com.carchecker.otg.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.ConnectionMode
import com.carchecker.otg.model.SensorCategory
import com.carchecker.otg.ui.components.CockpitCard
import com.carchecker.otg.ui.components.SectionHeader
import com.carchecker.otg.ui.components.SparklineChart
import com.carchecker.otg.ui.components.StatusPill
import com.carchecker.otg.ui.i18n.AppStrings
import com.carchecker.otg.ui.state.DiagnosticUiState
import com.carchecker.otg.ui.theme.CockpitColors
import java.util.Locale

@Composable
fun LiveSensorsScreen(
    state: DiagnosticUiState,
    strings: AppStrings,
    onToggleStreaming: () -> Unit
) {
    val isAr = state.settings.language == AppLanguage.AR
    var selectedCategory by remember { mutableStateOf<SensorCategory?>(null) }

    val sensors = remember(state.liveSensors, selectedCategory) {
        if (selectedCategory == null) state.liveSensors
        else state.liveSensors.filter { it.category == selectedCategory }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SectionHeader(
            title = strings.liveHeader,
            trailing = if (state.connectionMode != ConnectionMode.DISCONNECTED) {
                {
                    Button(
                        onClick = onToggleStreaming,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isLiveStreaming) CockpitColors.CriticalRed else CockpitColors.AccentTeal
                        )
                    ) {
                        Icon(
                            imageVector = if (state.isLiveStreaming) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (state.isLiveStreaming) " ${strings.stopPollingBtn}" else " ${strings.startPollingBtn}",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else null
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text(strings.allCategories) }
                )
            }
            items(SensorCategory.entries) { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(categoryLabel(cat, isAr)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (sensors.isEmpty()) {
            CockpitCard {
                Text(
                    text = strings.connectFirstLiveHint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = CockpitColors.TextSecondary
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 165.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sensors, key = { it.pidHex }) { s ->
                    val alertColor = if (s.isOutOfRange) CockpitColors.WarningAmber else CockpitColors.AccentTeal
                    val valFormatted = String.format(Locale.US, "%.${s.decimals}f", s.value)
                    val minFormatted = String.format(Locale.US, "%.${s.decimals}f", s.minRecorded)
                    val maxFormatted = String.format(Locale.US, "%.${s.decimals}f", s.maxRecorded)

                    CockpitCard(
                        borderColor = if (s.isOutOfRange) CockpitColors.WarningAmber.copy(alpha = 0.7f) else CockpitColors.SurfaceBorder
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PID ${s.pidHex}",
                                style = MaterialTheme.typography.labelMedium,
                                color = CockpitColors.TextSecondary
                            )
                            if (s.isOutOfRange) {
                                StatusPill(text = if (isAr) "خارج النطاق" else "Alert", color = CockpitColors.WarningAmber)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAr) s.nameAr else s.nameEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.height(38.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = valFormatted,
                                style = MaterialTheme.typography.headlineMedium,
                                color = alertColor,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = s.unit,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CockpitColors.TextSecondary,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        SparklineChart(
                            points = s.history,
                            lineColor = alertColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${strings.minLabel}: $minFormatted",
                                style = MaterialTheme.typography.labelMedium,
                                color = CockpitColors.TextSecondary
                            )
                            Text(
                                text = "${strings.maxLabel}: $maxFormatted",
                                style = MaterialTheme.typography.labelMedium,
                                color = CockpitColors.TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun categoryLabel(cat: SensorCategory, isAr: Boolean): String = when (cat) {
    SensorCategory.ENGINE -> if (isAr) "المحرك" else "Engine"
    SensorCategory.FUEL -> if (isAr) "الوقود" else "Fuel"
    SensorCategory.AIR_INTAKE -> if (isAr) "الهواء" else "Air"
    SensorCategory.TEMPERATURE -> if (isAr) "الحرارة" else "Temps"
    SensorCategory.ELECTRICAL -> if (isAr) "الكهرباء" else "Electrical"
    SensorCategory.EMISSIONS -> if (isAr) "العادم O2" else "Emissions"
    SensorCategory.OTHER -> if (isAr) "أخرى" else "Other"
}
