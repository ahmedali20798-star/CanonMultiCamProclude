package com.canonmulticam.pro.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReferenceArray

@Composable
fun LumaHistogramWidget(
    slotIndex: Int,
    histogramSource: AtomicReferenceArray<FloatArray>,
    modifier: Modifier = Modifier
) {
    var bins by remember { mutableStateOf(FloatArray(64)) }
    LaunchedEffect(slotIndex) {
        while (true) {
            histogramSource.get(slotIndex)?.let { bins = it }
            delay(120L)
        }
    }

    Surface(
        modifier = modifier.width(96.dp).height(44.dp),
        color = Color(0xAA111111),
        border = BorderStroke(1.dp, Color(0x66B8B5AE))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
            val barW = size.width / bins.size
            bins.forEachIndexed { idx, v ->
                val barH = size.height * v.coerceIn(0f, 1f)
                drawRect(
                    color = if (idx >= 60) Color(0xFFE53935) else Color(0xFFF5F3EE),
                    topLeft = Offset(idx * barW, size.height - barH),
                    size = Size(barW.coerceAtLeast(1f), barH)
                )
            }
        }
    }
}

@Composable
fun DigitalClapperOverlay(
    isFlashing: Boolean,
    onFlashComplete: () -> Unit
) {
    val alpha by animateFloatAsState(if (isFlashing) 0.92f else 0f, label = "slateFlash")
    LaunchedEffect(isFlashing) {
        if (isFlashing) {
            delay(180L)
            onFlashComplete()
        }
    }
    if (alpha > 0.01f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F3EE).copy(alpha = alpha)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SYNC SLATE",
                color = Color(0xFF111111),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
fun CameraLabelDialog(
    slotIndex: Int,
    currentLabel: String,
    modelName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color(0xFFF5F3EE),
        title = { Text("تسمية الكاميرا ${slotIndex + 1} ($modelName)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(24) },
                    singleLine = true,
                    label = { Text("الاسم المخصص (مثال: A-CAM WIDE)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF5F3EE),
                        unfocusedTextColor = Color(0xFFF5F3EE),
                        focusedBorderColor = Color(0xFFA58B62)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA58B62))
            ) {
                Text("حفظ", color = Color(0xFF111111))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color(0xFFB8B5AE))
            }
        }
    )
}
