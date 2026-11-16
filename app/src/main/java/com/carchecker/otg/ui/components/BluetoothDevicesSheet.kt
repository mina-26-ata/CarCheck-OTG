package com.carchecker.otg.ui.components

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carchecker.otg.model.BluetoothAdapterInfo
import com.carchecker.otg.model.BluetoothTransportType
import com.carchecker.otg.ui.theme.CockpitColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothDevicesBottomSheet(
    devices: List<BluetoothAdapterInfo>,
    isScanning: Boolean,
    hasPermissions: Boolean,
    isBluetoothEnabled: Boolean,
    requiredPermissions: Array<String>,
    isAr: Boolean,
    onRefreshBluetooth: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (BluetoothAdapterInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshBluetooth()
        onStartScan()
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        onRefreshBluetooth()
        if (grantedMap.values.all { it }) {
            onStartScan()
        }
    }

    LaunchedEffect(hasPermissions, isBluetoothEnabled) {
        onRefreshBluetooth()
        if (hasPermissions && isBluetoothEnabled) {
            onStartScan()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            onStopScan()
            onDismiss()
        },
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
                        imageVector = Icons.Rounded.BluetoothSearching,
                        contentDescription = null,
                        tint = CockpitColors.AccentCyan
                    )
                    Text(
                        text = if (isAr) "اتصال البلوتوث (ELM327 / BLE)" else "Bluetooth OBD-II Adapters",
                        style = MaterialTheme.typography.titleLarge,
                        color = CockpitColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = {
                    onStopScan()
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = CockpitColors.TextSecondary)
                }
            }

            Text(
                text = if (isAr) {
                    "ركّب قطعة ELM327 في فيشة السيارة أسفل الدركسيون وشغّل السويتش (ON)، ثم اختر القطعة (مثل OBDII أو ELM327 أو V-LINK)."
                } else {
                    "Plug the ELM327 dongle into the OBD-II port under the steering wheel, turn ignition ON, then tap the device below."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = CockpitColors.TextSecondary
            )

            if (!hasPermissions) {
                Button(
                    onClick = { permLauncher.launch(requiredPermissions) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CockpitColors.AccentCyan,
                        contentColor = CockpitColors.BackgroundDeep
                    )
                ) {
                    Text(
                        text = if (isAr) "السماح بصلاحية البلوتوث للبحث والاتصال" else "Grant Bluetooth Permission",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (!isBluetoothEnabled) {
                Button(
                    onClick = {
                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CockpitColors.WarningAmber,
                        contentColor = CockpitColors.BackgroundDeep
                    )
                ) {
                    Text(
                        text = if (isAr) "تشغيل البلوتوث الآن" else "Turn On Bluetooth",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                if (isScanning) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                        color = CockpitColors.AccentCyan,
                        trackColor = CockpitColors.SurfaceElevated
                    )
                }

                OutlinedButton(
                    onClick = { if (isScanning) onStopScan() else onStartScan() },
                    border = BorderStroke(1.dp, CockpitColors.AccentCyan.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isScanning) {
                            if (isAr) "جاري البحث عن قطع OBD-II... (اضغط للإيقاف)" else "Scanning for OBD-II Adapters... (Tap to Stop)"
                        } else {
                            if (isAr) "إعادة البحث عن أجهزة البلوتوث (Classic + BLE)" else "Scan for Bluetooth Adapters (Classic + BLE)"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (devices.isEmpty()) {
                    Surface(
                        color = CockpitColors.SurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isAr) {
                                "لم يتم العثور على أجهزة بعد. تأكد من تشغيل البلوتوث واقتراب الهاتف من قطعة السيارة ثم اضغط إعادة البحث."
                            } else {
                                "No Bluetooth devices discovered yet. Keep the phone near the OBD-II dongle and tap Scan."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = CockpitColors.TextSecondary,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(devices, key = { it.address }) { dev ->
                            val accent = if (dev.looksLikeObd) CockpitColors.HealthyGreen else CockpitColors.AccentCyan
                            Surface(
                                onClick = {
                                    onStopScan()
                                    onDismiss()
                                    onConnectDevice(dev)
                                },
                                color = CockpitColors.SurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (dev.looksLikeObd) CockpitColors.HealthyGreen.copy(alpha = 0.6f)
                                    else CockpitColors.SurfaceBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Bluetooth,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dev.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = CockpitColors.TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val modeText = when (dev.transportType) {
                                            BluetoothTransportType.BLE_GATT -> "BLE 4.0+"
                                            BluetoothTransportType.CLASSIC_SPP -> "Classic SPP"
                                            BluetoothTransportType.DUAL -> "Classic + BLE"
                                        }
                                        val meta = buildString {
                                            append(modeText)
                                            append(" • ")
                                            append(dev.address)
                                            if (dev.rssi != null) {
                                                append(" • ${dev.rssi} dBm")
                                            }
                                        }
                                        Text(
                                            text = meta,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = CockpitColors.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (dev.looksLikeObd) {
                                        StatusPill(
                                            text = if (isAr) "قطعة فحص" else "OBD-II",
                                            color = CockpitColors.HealthyGreen
                                        )
                                    } else if (dev.isPaired) {
                                        StatusPill(
                                            text = if (isAr) "مقترن" else "Paired",
                                            color = CockpitColors.AccentCyan
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
