package com.canonmulticam.pro.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.canonmulticam.pro.transport.NetworkBinder

@Composable
fun ZeroStateGuideCard(
    modifier: Modifier = Modifier,
    onManualIpConnect: (slotIndex: Int, ipAddress: String) -> Unit
) {
    val context = LocalContext.current
    val localIp by NetworkBinder.localWifiIpFlow.collectAsState()
    var showManualDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.widthIn(max = 420.dp).padding(16.dp),
        color = Color(0xE6161616),
        border = BorderStroke(1.dp, Color(0xFFA58B62)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (localIp != null) {
                    "جارٍ البحث التلقائي عن كاميرات Canon..."
                } else {
                    "نقطة الاتصال (Hotspot) غير نشطة"
                },
                color = Color(0xFFF5F3EE),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = if (localIp != null) {
                    "IP الهاتف: $localIp • فعّل (Camera Control API) أو أوصِل كابل USB OTG."
                } else {
                    "افتح نقطة اتصال الهاتف (Hotspot) أو أوصِل كاميرا Canon عبر كابل USB OTG للبدء فوراً."
                },
                color = Color(0xFFB8B5AE),
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = { showManualDialog = true },
                    border = BorderStroke(1.dp, Color(0xFFA58B62))
                ) {
                    Text(
                        text = "+ IP يدوي",
                        color = Color(0xFFA58B62),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (localIp == null) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA58B62))
                    ) {
                        Text(
                            text = "إعدادات الشبكة",
                            color = Color(0xFF111111),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualIpDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { slot, ip ->
                onManualIpConnect(slot, ip)
                showManualDialog = false
            }
        )
    }
}

@Composable
private fun ManualIpDialog(
    onDismiss: () -> Unit,
    onConfirm: (slotIndex: Int, ipAddress: String) -> Unit
) {
    var ipInput by remember { mutableStateOf("192.168.43.") }
    var selectedCamSlot by remember { mutableIntStateOf(0) }
    val octets = ipInput.split('.')
    val isValidIpv4 = octets.size == 4 && octets.all { part -> part.toIntOrNull() in 0..255 }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color(0xFFF5F3EE),
        title = { Text("ربط كاميرا عبر عنوان IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it.trim() },
                    singleLine = true,
                    label = { Text("عنوان IP الكاميرا") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF5F3EE),
                        unfocusedTextColor = Color(0xFFF5F3EE),
                        focusedBorderColor = Color(0xFFA58B62)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0..3) {
                        FilterChip(
                            selected = selectedCamSlot == i,
                            onClick = { selectedCamSlot = i },
                            label = { Text("CAM ${i + 1}") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValidIpv4) {
                        onConfirm(selectedCamSlot, ipInput)
                    }
                },
                enabled = isValidIpv4,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA58B62))
            ) {
                Text("اتصال", color = Color(0xFF111111))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color(0xFFB8B5AE))
            }
        }
    )
}
