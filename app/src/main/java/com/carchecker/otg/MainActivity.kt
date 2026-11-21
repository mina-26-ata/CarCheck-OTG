package com.carchecker.otg

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.ConnectionMode
import com.carchecker.otg.model.ObservationSeverity
import com.carchecker.otg.ui.i18n.AppStrings
import com.carchecker.otg.ui.screens.DashboardScreen
import com.carchecker.otg.ui.screens.FaultsScreen
import com.carchecker.otg.ui.screens.LiveSensorsScreen
import com.carchecker.otg.ui.screens.ObservationsScreen
import com.carchecker.otg.ui.screens.VehicleAndSettingsScreen
import com.carchecker.otg.ui.state.DiagnosticViewModel
import com.carchecker.otg.ui.theme.CarCheckTheme
import com.carchecker.otg.ui.theme.CockpitColors

class MainActivity : ComponentActivity() {

    private val viewModel: DiagnosticViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarCheckTheme {
                CarCheckerRootScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.refreshUsbDevices()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarCheckerRootScreen(viewModel: DiagnosticViewModel) {
    val state by viewModel.uiState.collectAsState()
    val strings = AppStrings.of(state.settings.language)
    val layoutDir = if (state.settings.language == AppLanguage.AR) LayoutDirection.Rtl else LayoutDirection.Ltr
    var selectedTab by remember { mutableIntStateOf(0) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(selectedTab) {
        viewModel.setActiveTab(selectedTab)
    }

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                containerColor = CockpitColors.Surface,
                title = { Text(strings.helpTitle, color = CockpitColors.AccentCyan, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(strings.helpBody, color = CockpitColors.TextPrimary)
                        Text(
                            text = "${strings.appVersionLabel}: v${BuildConfig.VERSION_NAME}",
                            color = CockpitColors.AccentCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.checkForUpdates()
                        }
                    ) {
                        Text(strings.checkUpdatesBtn, color = CockpitColors.HealthyGreen, fontWeight = FontWeight.Bold)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHelpDialog = false }) {
                        Text(if (state.settings.language == AppLanguage.AR) "حسناً" else "Got it", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        Scaffold(
            containerColor = CockpitColors.Background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val dotColor = when (state.connectionMode) {
                                    ConnectionMode.USB_OTG, ConnectionMode.BLUETOOTH -> CockpitColors.HealthyGreen
                                    ConnectionMode.SIMULATION -> CockpitColors.AccentCyan
                                    ConnectionMode.DISCONNECTED -> CockpitColors.TextSecondary.copy(alpha = 0.5f)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Text(
                                    text = strings.appTitle,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = CockpitColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    val nextLang = if (state.settings.language == AppLanguage.AR) AppLanguage.EN else AppLanguage.AR
                                    viewModel.setLanguage(nextLang)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = strings.languageLabel,
                                    tint = CockpitColors.AccentCyan
                                )
                            }
                            IconButton(onClick = { showHelpDialog = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                                    contentDescription = strings.helpTitle,
                                    tint = CockpitColors.HealthyGreen
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = CockpitColors.BackgroundDeep
                        )
                    )
                    HorizontalDivider(color = CockpitColors.HudGlowBorder.copy(alpha = 0.45f))
                }
            },
            bottomBar = {
                val faultCount = state.currentReport?.dtcs?.size ?: 0
                val obsAlertCount = state.currentReport?.observations?.count {
                    it.severity == ObservationSeverity.CRITICAL ||
                        it.severity == ObservationSeverity.WARNING
                } ?: 0

                Column {
                    HorizontalDivider(color = CockpitColors.HudGlowBorder.copy(alpha = 0.45f))
                    NavigationBar(containerColor = CockpitColors.BackgroundDeep) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
                            label = { Text(strings.navDashboard, maxLines = 1) },
                            colors = navItemColors()
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (faultCount > 0) Badge { Text("$faultCount") }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Build, contentDescription = null)
                                }
                            },
                            label = { Text(strings.navFaults, maxLines = 1) },
                            colors = navItemColors()
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (obsAlertCount > 0) Badge { Text("$obsAlertCount") }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Warning, contentDescription = null)
                                }
                            },
                            label = { Text(strings.navObservations, maxLines = 1) },
                            colors = navItemColors()
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Rounded.Insights, contentDescription = null) },
                            label = { Text(strings.navLive, maxLines = 1) },
                            colors = navItemColors()
                        )
                        NavigationBarItem(
                            selected = selectedTab == 4,
                            onClick = { selectedTab = 4 },
                            icon = { Icon(Icons.Rounded.DirectionsCar, contentDescription = null) },
                            label = { Text(strings.navVehicle, maxLines = 1) },
                            colors = navItemColors()
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                CockpitColors.BackgroundDeep,
                                CockpitColors.Background,
                                CockpitColors.BackgroundDeep
                            )
                        )
                    )
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> DashboardScreen(
                        state = state,
                        strings = strings,
                        onConnectUsb = viewModel::connectUsb,
                        onRefreshUsb = viewModel::refreshUsbDevices,
                        bluetoothRequiredPermissions = viewModel.requiredBluetoothPermissions(),
                        onRefreshBluetooth = viewModel::refreshBluetoothState,
                        onStartBluetoothScan = viewModel::startBluetoothScan,
                        onStopBluetoothScan = viewModel::stopBluetoothScan,
                        onConnectBluetooth = viewModel::connectBluetooth,
                        onDisconnect = viewModel::disconnect,
                        onStartSimulation = { viewModel.startSimulation() },
                        onRunFullScan = viewModel::runFullScan,
                        onToggleLiveStreaming = viewModel::toggleLiveStreaming,
                        onClearDtcs = viewModel::clearDtcs,
                        onSelectZone = viewModel::selectCarZone,
                        onLoadHistoryReport = viewModel::selectHistoryReport,
                        onDeleteHistoryReport = viewModel::deleteHistoryReport,
                        onNavigateToFaults = { selectedTab = 1 },
                        onNavigateToObservations = { selectedTab = 2 }
                    )
                    1 -> FaultsScreen(
                        state = state,
                        strings = strings,
                        onSearchLibrary = viewModel::searchLibrary,
                        onBuildFaultAiInsight = viewModel::buildFaultAiInsight,
                        onClearDtcs = viewModel::clearDtcs
                    )
                    2 -> ObservationsScreen(
                        state = state,
                        strings = strings,
                        onAskLocalAi = { q -> viewModel.askLocalAi(q) },
                        onClearAiChat = viewModel::clearAiChat,
                        onRunAiAnalysis = viewModel::runAiAnalysis
                    )
                    3 -> LiveSensorsScreen(
                        state = state,
                        strings = strings,
                        onToggleStreaming = viewModel::toggleLiveStreaming
                    )
                    else -> VehicleAndSettingsScreen(
                        state = state,
                        strings = strings,
                        onSetLanguage = viewModel::setLanguage,
                        onSetUnit = viewModel::setUnit,
                        onSetBaudRate = viewModel::setPreferredBaudRate,
                        onSetDemoProfile = viewModel::setDemoProfile,
                        onSaveGeminiConfig = viewModel::saveGeminiConfig,
                        onCheckForUpdates = viewModel::checkForUpdates,
                        onLoadHistoryReport = {
                            viewModel.selectHistoryReport(it)
                            selectedTab = 0
                        },
                        onDeleteHistoryReport = viewModel::deleteHistoryReport
                    )
                }
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = CockpitColors.AccentCyan,
    selectedTextColor = CockpitColors.AccentCyan,
    indicatorColor = CockpitColors.AccentCyan.copy(alpha = 0.16f),
    unselectedIconColor = CockpitColors.TextSecondary,
    unselectedTextColor = CockpitColors.TextSecondary
)
