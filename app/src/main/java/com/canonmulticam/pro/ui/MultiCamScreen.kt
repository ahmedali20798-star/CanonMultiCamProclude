package com.canonmulticam.pro.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.canonmulticam.pro.data.TakeRecord
import com.canonmulticam.pro.domain.AnamorphicRatio
import com.canonmulticam.pro.domain.CameraSlotState
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LinkState
import com.canonmulticam.pro.domain.PeakingColor
import com.canonmulticam.pro.domain.RecordAckState
import com.canonmulticam.pro.engine.MultiCamViewModel
import com.canonmulticam.pro.engine.ThermalLevel
import com.canonmulticam.pro.gl.FrameDecoderPool
import com.canonmulticam.pro.gl.QuadGlRenderer
import com.canonmulticam.pro.gl.ShaderToolFlags
import kotlinx.coroutines.delay
import java.util.Locale
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.roundToInt

@Composable
fun MultiCamScreen(viewModel: MultiCamViewModel) {
    val slots by viewModel.slotsState.collectAsState()
    val selectedSlot by viewModel.selectedSlotIndex.collectAsState()
    val soloSlot by viewModel.soloSlotIndex.collectAsState()
    val failureBanner by viewModel.recordFailureBanner.collectAsState()

    val renderer = remember { QuadGlRenderer() }
    var glSurfaceViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }
    var renamingSlotIndex by remember { mutableStateOf<Int?>(null) }
    var exposureDeckSlotIndex by remember { mutableStateOf<Int?>(null) }
    var isHudLocked by remember { mutableStateOf(false) }
    var isSlating by remember { mutableStateOf(false) }
    var currentPeakingColor by remember { mutableStateOf(PeakingColor.RED) }
    var zebraThreshold by remember { mutableFloatStateOf(0.92f) }

    val activeSlotIndex = soloSlot ?: selectedSlot
    val activeSlot = slots[activeSlotIndex]
    val anyCameraConnected = slots.any { it.isConnected }

    val currentActiveSlot by rememberUpdatedState(activeSlotIndex)
    val lutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCubeLutAsync(currentActiveSlot, uri) { slotIdx, parsedLut ->
                renderer.setSlotCubeLut(slotIdx, parsedLut)
                glSurfaceViewRef?.requestRender()
            }
        }
    }

    val lastGlRenderRequestMs = remember { AtomicLong(0L) }
    val decoderPool = remember {
        FrameDecoderPool(
            onFrameDecoded = { slotIdx, decodedFrame ->
                renderer.submitDecodedFrame(slotIdx, decodedFrame)
                val now = SystemClock.elapsedRealtime()
                val prev = lastGlRenderRequestMs.get()
                if (now - prev >= 30L && lastGlRenderRequestMs.compareAndSet(prev, now)) {
                    glSurfaceViewRef?.requestRender()
                }
            },
            onHistogramUpdated = { slotIdx, bins ->
                viewModel.histogramBins.set(slotIdx, bins)
            }
        )
    }

    LaunchedEffect(slots, soloSlot, currentPeakingColor, zebraThreshold) {
        renderer.soloSlot = soloSlot
        decoderPool.activeSoloSlot = soloSlot
        decoderPool.activeSelectedSlot = activeSlotIndex
        renderer.peakingColor = currentPeakingColor
        renderer.zebraThreshold = zebraThreshold
        slots.forEach { s ->
            renderer.setSlotTools(s.slotIndex, s.activeToolsMask, s.anamorphic)
        }
        glSurfaceViewRef?.requestRender()
    }

    BindGlLifecycleEffects(viewModel, decoderPool, renderer, glSurfaceViewRef)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF111111))) {
        GlSurfaceHost(
            renderer = renderer,
            decoderPool = decoderPool,
            viewModel = viewModel,
            onSurfaceReady = { glSurfaceViewRef = it }
        )

        Column(modifier = Modifier.fillMaxSize()) {
            failureBanner?.let { msg ->
                FailureAlertStrip(msg = msg, onDismiss = { viewModel.dismissFailureBanner() })
            }

            QuadOrSoloViewport(
                slots = slots,
                soloSlot = soloSlot,
                activeSlotIndex = activeSlotIndex,
                histogramSource = viewModel.histogramBins,
                renderer = renderer,
                viewModel = viewModel,
                onOpenRename = { renamingSlotIndex = it },
                onOpenExposureDeck = { idx ->
                    exposureDeckSlotIndex = if (exposureDeckSlotIndex == idx) null else idx
                }
            )

            exposureDeckSlotIndex?.let { deckIdx ->
                val deckSlot = slots.getOrNull(deckIdx)
                if (deckSlot != null && deckSlot.isConnected) {
                    QuickExposureControlDeck(
                        slot = deckSlot,
                        onAdjustIso = { viewModel.adjustSlotExposure(deckIdx, newIso = it) },
                        onAdjustKelvin = { viewModel.adjustSlotExposure(deckIdx, newKelvin = it) },
                        onClose = { exposureDeckSlotIndex = null }
                    )
                }
            }

            ConnectedBottomBar(
                viewModel = viewModel,
                renderer = renderer,
                glSurfaceViewRef = glSurfaceViewRef,
                activeSlotIndex = activeSlotIndex,
                activeSlot = activeSlot,
                currentPeakingColor = currentPeakingColor,
                zebraThreshold = zebraThreshold,
                onPeakingColorChange = { currentPeakingColor = it },
                onZebraThresholdChange = { zebraThreshold = it },
                onImportCube = { lutPickerLauncher.launch(arrayOf("*/*")) },
                onTriggerSlate = { isSlating = true; viewModel.onSlateTriggered() },
                onLockHud = { isHudLocked = true }
            )
        }

        ScreenOverlaysHost(
            anyCameraConnected = anyCameraConnected,
            isHudLocked = isHudLocked,
            isSlating = isSlating,
            renamingSlotIndex = renamingSlotIndex,
            slots = slots,
            viewModel = viewModel,
            onUnlockHud = { isHudLocked = false },
            onSlateComplete = { isSlating = false },
            onDismissRename = { renamingSlotIndex = null }
        )
    }
}

@Composable
private fun BindGlLifecycleEffects(
    viewModel: MultiCamViewModel,
    decoderPool: FrameDecoderPool,
    renderer: QuadGlRenderer,
    glSurfaceViewRef: GLSurfaceView?
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.setAppBackgrounded(true)
                    glSurfaceViewRef?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setAppBackgrounded(false)
                    glSurfaceViewRef?.onResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.frameDeliveryCallback = null
            viewModel.slotDisconnectedCallback = null
            decoderPool.destroy()
            glSurfaceViewRef?.queueEvent { renderer.releaseGlResources() }
        }
    }
}

@Composable
private fun GlSurfaceHost(
    renderer: QuadGlRenderer,
    decoderPool: FrameDecoderPool,
    viewModel: MultiCamViewModel,
    onSurfaceReady: (GLSurfaceView) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                onSurfaceReady(this)
                viewModel.frameDeliveryCallback = { idx, encodedSlot ->
                    decoderPool.submitEncodedFrame(idx, encodedSlot)
                }
                viewModel.slotDisconnectedCallback = { idx ->
                    renderer.clearSlotFrame(idx)
                    requestRender()
                }
            }
        }
    )
}

@Composable
private fun FailureAlertStrip(msg: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFD32F2F))
            .clickable { onDismiss() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = msg,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge
        )
        Text("إغلاق ✕", color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ScreenOverlaysHost(
    anyCameraConnected: Boolean,
    isHudLocked: Boolean,
    isSlating: Boolean,
    renamingSlotIndex: Int?,
    slots: List<CameraSlotState>,
    viewModel: MultiCamViewModel,
    onUnlockHud: () -> Unit,
    onSlateComplete: () -> Unit,
    onDismissRename: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (!anyCameraConnected) {
            ZeroStateGuideCard(
                modifier = Modifier.align(Alignment.Center),
                onManualIpConnect = { slotIdx, ip ->
                    viewModel.bindOrReconnectCamera(slotIdx, ip)
                }
            )
        }
        if (isHudLocked) {
            HudLockOverlay(onUnlock = onUnlockHud)
        }
        DigitalClapperOverlay(isFlashing = isSlating, onFlashComplete = onSlateComplete)
        renamingSlotIndex?.let { targetIdx ->
            val targetSlot = slots[targetIdx]
            CameraLabelDialog(
                slotIndex = targetIdx,
                currentLabel = targetSlot.customLabel ?: "",
                modelName = targetSlot.modelName,
                onDismiss = onDismissRename,
                onConfirm = { newName ->
                    viewModel.renameCamera(targetIdx, newName)
                    onDismissRename()
                }
            )
        }
    }
}

@Composable
private fun QuickExposureControlDeck(
    slot: CameraSlotState,
    onAdjustIso: (Int) -> Unit,
    onAdjustKelvin: (Int) -> Unit,
    onClose: () -> Unit
) {
    val exp = slot.exposure ?: ExposureSettings("f4.0", "1/50", 800, 5600)
    val isoSteps = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800)
    val kelvinSteps = listOf(3200, 4000, 4800, 5600, 6500, 7500)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF0181818))
            .border(1.dp, Color(0x66A58B62))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "${slot.displayHeader}:",
            color = Color(0xFFA58B62),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ISO", color = Color(0xFFB8B5AE), style = MaterialTheme.typography.labelSmall)
            isoSteps.forEach { isoVal ->
                FilterChip(
                    selected = exp.iso == isoVal,
                    onClick = { onAdjustIso(isoVal) },
                    label = { Text("$isoVal", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFA58B62),
                        selectedLabelColor = Color(0xFF111111),
                        labelColor = Color(0xFFF5F3EE)
                    )
                )
            }

            Text(" | WB", color = Color(0xFFB8B5AE), style = MaterialTheme.typography.labelSmall)
            kelvinSteps.forEach { kVal ->
                FilterChip(
                    selected = exp.kelvin == kVal,
                    onClick = { onAdjustKelvin(kVal) },
                    label = { Text("${kVal}K", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFA58B62),
                        selectedLabelColor = Color(0xFF111111),
                        labelColor = Color(0xFFF5F3EE)
                    )
                )
            }
        }

        OutlinedButton(
            onClick = onClose,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            border = BorderStroke(1.dp, Color(0xFFB8B5AE))
        ) {
            Text("✕", color = Color(0xFFF5F3EE), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ColumnScope.QuadOrSoloViewport(
    slots: List<CameraSlotState>,
    soloSlot: Int?,
    activeSlotIndex: Int,
    histogramSource: AtomicReferenceArray<FloatArray>,
    renderer: QuadGlRenderer,
    viewModel: MultiCamViewModel,
    onOpenRename: (Int) -> Unit,
    onOpenExposureDeck: (Int) -> Unit
) {
    val activeSolo = soloSlot
    if (activeSolo != null) {
        val s = slots[activeSolo]
        CameraCellOverlay(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            slot = s,
            isSelected = true,
            histogramSource = histogramSource,
            onDoubleTap = { viewModel.toggleSoloZoom(s.slotIndex) },
            onSingleTap = { lx, ly, cw, ch ->
                renderer.mapCellTouchToSensor(lx, ly, cw, ch, s.slotIndex)?.let { (nx, ny) ->
                    viewModel.onTouchAf(s.slotIndex, nx, ny)
                } ?: viewModel.selectSlot(s.slotIndex)
            },
            onOpenRename = { onOpenRename(s.slotIndex) },
            onOpenExposureDeck = { onOpenExposureDeck(s.slotIndex) }
        )
    } else {
        for (row in 0..1) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0..1) {
                    val idx = row * 2 + col
                    val s = slots[idx]
                    CameraCellOverlay(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        slot = s,
                        isSelected = (idx == activeSlotIndex),
                        histogramSource = histogramSource,
                        onDoubleTap = { viewModel.toggleSoloZoom(idx) },
                        onSingleTap = { lx, ly, cw, ch ->
                            viewModel.selectSlot(idx)
                            renderer.mapCellTouchToSensor(lx, ly, cw, ch, idx)?.let { (nx, ny) ->
                                viewModel.onTouchAf(idx, nx, ny)
                            }
                        },
                        onOpenRename = { onOpenRename(idx) },
                        onOpenExposureDeck = { onOpenExposureDeck(idx) }
                    )
                }
            }
        }
    }
}


@Composable
private fun ConnectedBottomBar(
    viewModel: MultiCamViewModel,
    renderer: QuadGlRenderer,
    glSurfaceViewRef: GLSurfaceView?,
    activeSlotIndex: Int,
    activeSlot: CameraSlotState,
    currentPeakingColor: PeakingColor,
    zebraThreshold: Float,
    onPeakingColorChange: (PeakingColor) -> Unit,
    onZebraThresholdChange: (Float) -> Unit,
    onImportCube: () -> Unit,
    onTriggerSlate: () -> Unit,
    onLockHud: () -> Unit
) {
    val isGroupRec by viewModel.isGroupRecording.collectAsState()
    val recStartTime by viewModel.recordingStartTimeMs.collectAsState()
    val canUndoMatch by viewModel.canUndoMatch.collectAsState()
    val thermalLevel by viewModel.thermalGuard.thermalLevel.collectAsState()
    val takes by viewModel.takeLogger.takes.collectAsState()

    BottomControlBar(
        activeSlotIndex = activeSlotIndex,
        activeSlot = activeSlot,
        isGroupRec = isGroupRec,
        recStartTime = recStartTime,
        takes = takes,
        thermalLevel = thermalLevel,
        currentPeakingColor = currentPeakingColor,
        zebraThreshold = zebraThreshold,
        canUndoMatch = canUndoMatch,
        onToggleGroupRecord = { viewModel.toggleGroupRecord() },
        onMarkTakeGood = { viewModel.takeLogger.markCurrentTakeGood() },
        onShareCsv = { viewModel.shareTakeLogCsv() },
        onTriggerSlate = onTriggerSlate,
        onToggleToolMask = { bit ->
            viewModel.actors[activeSlotIndex].updateToolsMask(activeSlot.activeToolsMask xor bit)
        },
        onImportCube = onImportCube,
        onBypassChanged = { bypass ->
            renderer.bypassAllLuts = bypass
            glSurfaceViewRef?.requestRender()
        },
        onCyclePeakingColor = {
            val next = PeakingColor.entries[
                (currentPeakingColor.ordinal + 1) % PeakingColor.entries.size
            ]
            onPeakingColorChange(next)
            if ((activeSlot.activeToolsMask and ShaderToolFlags.PEAKING) == 0) {
                viewModel.actors[activeSlotIndex].updateToolsMask(
                    activeSlot.activeToolsMask or ShaderToolFlags.PEAKING
                )
            }
        },
        onCycleZebraThreshold = {
            onZebraThresholdChange(if (zebraThreshold > 0.80f) 0.70f else 0.92f)
            if ((activeSlot.activeToolsMask and ShaderToolFlags.ZEBRA) == 0) {
                viewModel.actors[activeSlotIndex].updateToolsMask(
                    activeSlot.activeToolsMask or ShaderToolFlags.ZEBRA
                )
            }
        },
        onCycleAnamorphic = {
            val next = AnamorphicRatio.entries[
                (activeSlot.anamorphic.ordinal + 1) % AnamorphicRatio.entries.size
            ]
            viewModel.actors[activeSlotIndex].updateAnamorphic(next)
        },
        onMatchAll = { viewModel.matchAllToMaster(activeSlotIndex) },
        onUndoMatch = { viewModel.undoMatchAll() },
        onLockHud = onLockHud
    )
}

@Composable
private fun BottomControlBar(
    activeSlotIndex: Int,
    activeSlot: CameraSlotState,
    isGroupRec: Boolean,
    recStartTime: Long?,
    takes: List<TakeRecord>,
    thermalLevel: ThermalLevel,
    currentPeakingColor: PeakingColor,
    zebraThreshold: Float,
    canUndoMatch: Boolean,
    onToggleGroupRecord: () -> Unit,
    onMarkTakeGood: () -> Unit,
    onShareCsv: () -> Unit,
    onTriggerSlate: () -> Unit,
    onToggleToolMask: (Int) -> Unit,
    onImportCube: () -> Unit,
    onBypassChanged: (Boolean) -> Unit,
    onCyclePeakingColor: () -> Unit,
    onCycleZebraThreshold: () -> Unit,
    onCycleAnamorphic: () -> Unit,
    onMatchAll: () -> Unit,
    onUndoMatch: () -> Unit,
    onLockHud: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xEB111111))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(
            onClick = onToggleGroupRecord,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGroupRec) Color(0xFFD32F2F) else Color(0xFF2B2B2B)
            )
        ) {
            Text(
                text = if (isGroupRec) "STOP ALL" else "REC ALL",
                color = Color(0xFFF5F3EE),
                fontWeight = FontWeight.Bold
            )
        }

        RecordingStatusSection(isGroupRec, recStartTime, takes, onMarkTakeGood, onShareCsv)

        OutlinedButton(
            onClick = onTriggerSlate,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            border = BorderStroke(1.dp, Color(0xFFF5F3EE))
        ) {
            Text("SLATE", color = Color(0xFFF5F3EE), style = MaterialTheme.typography.labelSmall)
        }

        Text(
            text = "C${activeSlotIndex + 1}|${thermalLevel.label}",
            color = Color(0xFFA58B62),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )

        CinemaToolsScrollRow(
            modifier = Modifier.weight(1f),
            activeSlot = activeSlot,
            currentPeakingColor = currentPeakingColor,
            zebraThreshold = zebraThreshold,
            onToggleToolMask = onToggleToolMask,
            onImportCube = onImportCube,
            onBypassChanged = onBypassChanged,
            onCyclePeakingColor = onCyclePeakingColor,
            onCycleZebraThreshold = onCycleZebraThreshold,
            onCycleAnamorphic = onCycleAnamorphic
        )

        OutlinedButton(
            onClick = onMatchAll,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            border = BorderStroke(1.dp, Color(0xFFA58B62))
        ) {
            Text("MATCH", color = Color(0xFFF5F3EE), style = MaterialTheme.typography.labelSmall)
        }

        if (canUndoMatch) {
            OutlinedButton(
                onClick = onUndoMatch,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                border = BorderStroke(1.dp, Color(0xFFFFB300))
            ) {
                Text("UNDO", color = Color(0xFFFFB300), style = MaterialTheme.typography.labelSmall)
            }
        }

        OutlinedButton(
            onClick = onLockHud,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            border = BorderStroke(1.dp, Color(0xFFB8B5AE))
        ) {
            Text("LOCK", color = Color(0xFFF5F3EE), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaToolsScrollRow(
    modifier: Modifier,
    activeSlot: CameraSlotState,
    currentPeakingColor: PeakingColor,
    zebraThreshold: Float,
    onToggleToolMask: (Int) -> Unit,
    onImportCube: () -> Unit,
    onBypassChanged: (Boolean) -> Unit,
    onCyclePeakingColor: () -> Unit,
    onCycleZebraThreshold: () -> Unit,
    onCycleAnamorphic: () -> Unit
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolToggleChip("LUT", (activeSlot.activeToolsMask and ShaderToolFlags.LUT) != 0) {
            onToggleToolMask(ShaderToolFlags.LUT)
        }
        OutlinedButton(
            onClick = onImportCube,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            border = BorderStroke(1.dp, Color(0xFFA58B62))
        ) {
            Text("+CUBE", color = Color(0xFFA58B62), style = MaterialTheme.typography.labelSmall)
        }
        Box(
            modifier = Modifier
                .border(1.dp, Color(0xFFB8B5AE), MaterialTheme.shapes.small)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onBypassChanged(true)
                            tryAwaitRelease()
                            onBypassChanged(false)
                        }
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("RAW LOG", color = Color(0xFFF5F3EE), style = MaterialTheme.typography.labelSmall)
        }

        val isPeakingOn = (activeSlot.activeToolsMask and ShaderToolFlags.PEAKING) != 0
        Surface(
            color = if (isPeakingOn) Color(0xFFA58B62) else Color.Transparent,
            border = BorderStroke(1.dp, if (isPeakingOn) Color(0xFFA58B62) else Color(0x66B8B5AE)),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.combinedClickable(
                onClick = { onToggleToolMask(ShaderToolFlags.PEAKING) },
                onLongClick = onCyclePeakingColor
            )
        ) {
            Text(
                text = currentPeakingColor.label,
                color = if (isPeakingOn) Color(0xFF111111) else Color(0xFFF5F3EE),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        ToolToggleChip("FALSE", (activeSlot.activeToolsMask and ShaderToolFlags.FALSE_COLOR) != 0) {
            onToggleToolMask(ShaderToolFlags.FALSE_COLOR)
        }

        val isZebraOn = (activeSlot.activeToolsMask and ShaderToolFlags.ZEBRA) != 0
        val zebraLabel = if (zebraThreshold < 0.80f) "ZEBRA 70%" else "ZEBRA 95%"
        Surface(
            color = if (isZebraOn) Color(0xFFA58B62) else Color.Transparent,
            border = BorderStroke(1.dp, if (isZebraOn) Color(0xFFA58B62) else Color(0x66B8B5AE)),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.combinedClickable(
                onClick = { onToggleToolMask(ShaderToolFlags.ZEBRA) },
                onLongClick = onCycleZebraThreshold
            )
        ) {
            Text(
                text = zebraLabel,
                color = if (isZebraOn) Color(0xFF111111) else Color(0xFFF5F3EE),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        ToolToggleChip("3x3", (activeSlot.activeToolsMask and ShaderToolFlags.GUIDE_THIRDS) != 0) {
            onToggleToolMask(ShaderToolFlags.GUIDE_THIRDS)
        }
        ToolToggleChip("9:16", (activeSlot.activeToolsMask and ShaderToolFlags.GUIDE_9_16) != 0) {
            onToggleToolMask(ShaderToolFlags.GUIDE_9_16)
        }
        ToolToggleChip("2.39", (activeSlot.activeToolsMask and ShaderToolFlags.GUIDE_2_39) != 0) {
            onToggleToolMask(ShaderToolFlags.GUIDE_2_39)
        }
        OutlinedButton(
            onClick = onCycleAnamorphic,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            border = BorderStroke(1.dp, Color(0xFFA58B62))
        ) {
            Text(
                text = activeSlot.anamorphic.label,
                color = Color(0xFFF5F3EE),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun RecordingStatusSection(
    isGroupRec: Boolean,
    recStartTime: Long?,
    takes: List<TakeRecord>,
    onMarkTakeGood: () -> Unit,
    onShareCsv: () -> Unit
) {
    var elapsedRecSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recStartTime) {
        val startMs = recStartTime
        if (startMs == null) {
            elapsedRecSec = 0L
        } else {
            while (true) {
                elapsedRecSec = (System.currentTimeMillis() - startMs) / 1000L
                delay(1000L)
            }
        }
    }

    val currentTake = takes.lastOrNull()
    if (isGroupRec && currentTake != null) {
        val hrs = elapsedRecSec / 3600
        val mins = (elapsedRecSec % 3600) / 60
        val secs = elapsedRecSec % 60
        Text(
            text = String.format(Locale.US, "T%02d %02d:%02d:%02d", currentTake.takeNumber, hrs, mins, secs),
            color = Color(0xFFE53935),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium
        )
        OutlinedButton(
            onClick = onMarkTakeGood,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            border = BorderStroke(
                1.dp,
                if (currentTake.isGoodTake) Color(0xFFFFD54F) else Color(0x66B8B5AE)
            )
        ) {
            Text(
                text = if (currentTake.isGoodTake) "★ GOOD" else "☆ GOOD",
                color = if (currentTake.isGoodTake) Color(0xFFFFD54F) else Color(0xFFF5F3EE),
                style = MaterialTheme.typography.labelSmall
            )
        }
    } else if (takes.isNotEmpty()) {
        OutlinedButton(
            onClick = onShareCsv,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            border = BorderStroke(1.dp, Color(0xFFA58B62))
        ) {
            Text(
                text = "CSV (${takes.size})",
                color = Color(0xFFA58B62),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun HudLockOverlay(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onUnlock() }) }
            .padding(16.dp)
    ) {
        Surface(
            color = Color(0xCC111111),
            border = BorderStroke(1.dp, Color(0xFFA58B62)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Text(
                text = "الشاشة مقفلة ضد اللمس العشوائي — اضغط مطولاً لفتح القفل",
                color = Color(0xFFF5F3EE),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ToolToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = active,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFA58B62),
            selectedLabelColor = Color(0xFF111111),
            labelColor = Color(0xFFF5F3EE)
        )
    )
}

@Composable
private fun CameraCellOverlay(
    modifier: Modifier,
    slot: CameraSlotState,
    isSelected: Boolean,
    histogramSource: AtomicReferenceArray<FloatArray>,
    onDoubleTap: () -> Unit,
    onSingleTap: (localX: Float, localY: Float, cellW: Float, cellH: Float) -> Unit,
    onOpenRename: () -> Unit,
    onOpenExposureDeck: () -> Unit
) {
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }
    var cellWidthPx by remember { mutableFloatStateOf(0f) }
    var cellHeightPx by remember { mutableFloatStateOf(0f) }
    val reticleAlpha by animateFloatAsState(if (focusTapOffset != null) 1f else 0f, label = "afAlpha")
    val latestOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val latestOnSingleTap by rememberUpdatedState(onSingleTap)

    LaunchedEffect(focusTapOffset) {
        if (focusTapOffset != null) {
            delay(1400L)
            focusTapOffset = null
        }
    }

    val isRecording = slot.recordState == RecordAckState.RECORDING
    val borderColor = when {
        isRecording -> Color(0xFFE53935)
        slot.recordState == RecordAckState.PENDING -> Color(0xFFFB8C00)
        slot.recordState == RecordAckState.ERROR -> Color(0xFFFFB300)
        isSelected -> Color(0xFFA58B62)
        else -> Color(0x33B8B5AE)
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                cellWidthPx = size.width.toFloat()
                cellHeightPx = size.height.toFloat()
            }
            .border(if (isSelected || isRecording) 2.5.dp else 1.5.dp, borderColor)
            .pointerInput(slot.slotIndex) {
                detectTapGestures(
                    onDoubleTap = { latestOnDoubleTap() },
                    onTap = { offset ->
                        focusTapOffset = offset
                        latestOnSingleTap(offset.x, offset.y, cellWidthPx, cellHeightPx)
                    }
                )
            }
            .padding(8.dp)
    ) {
        CameraCellHeaderBar(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            slot = slot,
            isSelected = isSelected,
            onOpenRename = onOpenRename
        )

        slot.exposure?.let { exp ->
            CameraCellExposureBadge(
                exposure = exp,
                isDrifted = slot.isExposureDrifted,
                onClick = onOpenExposureDeck,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }

        if (isSelected && slot.isConnected) {
            LumaHistogramWidget(
                slotIndex = slot.slotIndex,
                histogramSource = histogramSource,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }

        focusTapOffset?.let { pos ->
            val halfBoxPx = with(LocalDensity.current) { 24.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (pos.x - halfBoxPx).roundToInt(),
                            (pos.y - halfBoxPx).roundToInt()
                        )
                    }
                    .size(48.dp)
                    .border(2.dp, Color(0xFFA58B62).copy(alpha = reticleAlpha))
            )
        }

        if (slot.link == LinkState.CONNECTING) {
            Surface(color = Color(0xFFEF6C00), modifier = Modifier.align(Alignment.Center)) {
                Text(
                    text = "CONNECTING...",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        } else if (slot.isSignalStale) {
            Surface(color = Color(0xFFD32F2F), modifier = Modifier.align(Alignment.Center)) {
                Text(
                    text = if (slot.link == LinkState.RECONNECTING && slot.reconnectAttempt > 0) {
                        "SIGNAL LOST - RECONNECTING (${slot.reconnectAttempt})"
                    } else {
                        "SIGNAL STALE - RECONNECTING"
                    },
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun CameraCellHeaderBar(
    modifier: Modifier,
    slot: CameraSlotState,
    isSelected: Boolean,
    onOpenRename: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = if (isSelected) Color(0xFFA58B62) else Color(0x99111111),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.clickable { onOpenRename() }
        ) {
            Text(
                text = "${slot.displayHeader} ✎",
                color = if (isSelected) Color(0xFF111111) else Color(0xFFF5F3EE),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        if (slot.isConnected) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (slot.latencyMs > 0L) {
                    Surface(
                        color = if (slot.latencyMs > 180L) Color(0xFFEF6C00) else Color(0xAA111111),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "${slot.latencyMs}ms",
                            color = Color(0xFFF5F3EE),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                slot.remainingCardMinutes?.let { mins ->
                    Surface(
                        color = if (mins <= 15) Color(0xFFD32F2F) else Color(0xAA111111),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "SD ${mins}m",
                            color = Color(0xFFF5F3EE),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    color = if (slot.isBatteryLow) Color(0xFFD32F2F) else Color(0xAA111111),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "BAT ${slot.batteryLabel}",
                        color = Color(0xFFF5F3EE),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraCellExposureBadge(
    exposure: ExposureSettings,
    isDrifted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val driftTag = if (isDrifted) " • DRIFT!" else ""
    Text(
        text = "${exposure.shutterSpeed} • ${exposure.aperture} • ISO ${exposure.iso} • ${exposure.kelvin}K$driftTag ⚙",
        color = if (isDrifted) Color(0xFF111111) else Color(0xFFE0DDD5),
        fontWeight = if (isDrifted) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clickable { onClick() }
            .background(if (isDrifted) Color(0xFFFFB300) else Color(0xAA111111))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}
