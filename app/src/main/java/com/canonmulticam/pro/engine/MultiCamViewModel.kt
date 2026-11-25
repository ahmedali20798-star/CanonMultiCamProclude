package com.canonmulticam.pro.engine

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.canonmulticam.pro.data.CameraLabelStore
import com.canonmulticam.pro.data.TakeLogger
import com.canonmulticam.pro.domain.CameraSlotState
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LinkState
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.StreamQuality
import com.canonmulticam.pro.gl.CubeLutData
import com.canonmulticam.pro.gl.CubeLutParser
import com.canonmulticam.pro.transport.DiscoveredCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReferenceArray

@Suppress("TooManyFunctions")
class MultiCamViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val labelStore = CameraLabelStore(appContext)
    val thermalGuard = ThermalGuard(appContext)
    val takeLogger = TakeLogger()

    @Volatile var frameDeliveryCallback: ((slotIndex: Int, slot: LatestFrameSlot) -> Unit)? = null
    @Volatile var slotDisconnectedCallback: ((slotIndex: Int) -> Unit)? = null

    val histogramBins = AtomicReferenceArray<FloatArray>(4)

    private val discoveryLock = Any()
    private val identityOwners = HashMap<String, Int>()

    private val _selectedSlotIndex = MutableStateFlow(0)
    val selectedSlotIndex: StateFlow<Int> = _selectedSlotIndex.asStateFlow()

    private val _soloSlotIndex = MutableStateFlow<Int?>(null)
    val soloSlotIndex: StateFlow<Int?> = _soloSlotIndex.asStateFlow()

    private val _isGroupRecording = MutableStateFlow(false)
    val isGroupRecording: StateFlow<Boolean> = _isGroupRecording.asStateFlow()

    private val _recordingStartTimeMs = MutableStateFlow<Long?>(null)
    val recordingStartTimeMs: StateFlow<Long?> = _recordingStartTimeMs.asStateFlow()

    private val _recordFailureBanner = MutableStateFlow<String?>(null)
    val recordFailureBanner: StateFlow<String?> = _recordFailureBanner.asStateFlow()

    private val _canUndoMatch = MutableStateFlow(false)
    val canUndoMatch: StateFlow<Boolean> = _canUndoMatch.asStateFlow()

    private var preMatchSnapshot: Map<Int, ExposureSettings> = emptyMap()
    private var isAppInBackground = false
    private var exposurePollJob: Job? = null

    val actors: List<CameraSessionActor> = List(4) { idx ->
        CameraSessionActor(
            slotIndex = idx,
            labelStore = labelStore,
            thermalLevelProvider = {
                val base = thermalGuard.thermalLevel.value
                val connectedCount = slotsState.value.count { it.isConnected }
                if (connectedCount >= 3 && base == ThermalLevel.NOMINAL) {
                    ThermalLevel.MODERATE
                } else {
                    base
                }
            },
            onUnexpectedRecordStop = { failedSlot ->
                triggerEmergencyHaptic()
                val header = actors[failedSlot].state.value.displayHeader
                _recordFailureBanner.value = "تحذير حرج: توقف التسجيل فجأة في $header!"
            },
            onDisconnectedCallback = { disconnectedSlot ->
                synchronized(discoveryLock) {
                    identityOwners.entries.removeAll { it.value == disconnectedSlot }
                }
                histogramBins.set(disconnectedSlot, null)
                slotDisconnectedCallback?.invoke(disconnectedSlot)
            },
            onFrameUpdated = { slotIdx, slot ->
                frameDeliveryCallback?.invoke(slotIdx, slot)
            },
            claimIdentity = { slotIdx, uuid -> claimCameraIdentity(slotIdx, uuid) }
        )
    }

    val slotsState: StateFlow<List<CameraSlotState>> = combine(
        actors[0].state,
        actors[1].state,
        actors[2].state,
        actors[3].state
    ) { s0, s1, s2, s3 -> listOf(s0, s1, s2, s3) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = actors.map { it.state.value }
        )

    init {
        thermalGuard.start()
        startExposureSyncLoop()
    }

    /** IPs/addresses currently owned by a slot (connecting, live or reconnecting). */
    fun ownedAddresses(): Set<String> =
        actors.mapNotNull { it.state.value.ipAddress }.toSet()

    fun onCameraDiscovered(camera: DiscoveredCamera) {
        // Serialised: SSDP, the subnet sweep and USB can all report the same camera at once.
        // The slot is claimed synchronously inside connect(), so the second report finds it.
        synchronized(discoveryLock) {
            val existing = actors.firstOrNull {
                it.state.value.ipAddress == camera.ipAddress ||
                    (it.state.value.boundUuid != null && it.state.value.boundUuid == camera.uuid)
            }
            if (existing != null) {
                // connect() is a no-op while a session for this address is connecting, live or
                // reconnecting, so discovery can never restart an in-flight handshake.
                existing.connect(camera.ipAddress, computeTargetQuality(existing.slotIndex))
                return
            }

            val freeActor = actors.firstOrNull {
                it.state.value.link == LinkState.IDLE && it.state.value.ipAddress == null
            } ?: return

            freeActor.connect(camera.ipAddress, computeTargetQuality(freeActor.slotIndex))
        }
    }

    /**
     * Called by a session after its handshake, with the camera's real serial. Guarantees one
     * camera never occupies two slots (e.g. after a DHCP address change).
     */
    private fun claimCameraIdentity(slotIndex: Int, uuid: String): Boolean =
        synchronized(discoveryLock) {
            val owner = identityOwners[uuid]
            if (owner == null || owner == slotIndex) {
                identityOwners[uuid] = slotIndex
                return@synchronized true
            }
            val ownerState = actors[owner].state.value
            val ownerHealthy = ownerState.link == LinkState.LIVE && !ownerState.isSignalStale
            if (ownerHealthy) {
                false
            } else {
                // Previous owner is dead/reconnecting on a stale address: hand the camera over.
                actors[owner].disconnect()
                identityOwners[uuid] = slotIndex
                true
            }
        }

    fun onCameraDetachedByUuid(uuid: String) {
        actors.firstOrNull { it.state.value.boundUuid == uuid }?.disconnect()
    }

    fun bindOrReconnectCamera(slotIndex: Int, ipAddress: String) {
        if (slotIndex !in 0..3) return
        actors[slotIndex].connect(ipAddress, computeTargetQuality(slotIndex), force = true)
    }

    fun selectSlot(slotIndex: Int) {
        if (slotIndex in 0..3) _selectedSlotIndex.value = slotIndex
    }

    fun toggleSoloZoom(slotIndex: Int) {
        val nextSolo = if (_soloSlotIndex.value == slotIndex) null else slotIndex
        _soloSlotIndex.value = nextSolo
        if (nextSolo != null) _selectedSlotIndex.value = nextSolo
        applyDynamicStreamQualities()
    }

    fun setAppBackgrounded(backgrounded: Boolean) {
        isAppInBackground = backgrounded
        applyDynamicStreamQualities()
    }

    private fun applyDynamicStreamQualities() {
        actors.forEach { actor ->
            actor.updateStreamQuality(computeTargetQuality(actor.slotIndex))
        }
    }

    private fun computeTargetQuality(slotIndex: Int): StreamQuality {
        if (isAppInBackground) return StreamQuality.PAUSED
        val solo = _soloSlotIndex.value ?: return StreamQuality.LOW_QUAD
        return if (solo == slotIndex) StreamQuality.FULL_SOLO else StreamQuality.PAUSED
    }

    fun toggleGroupRecord() {
        val targetRecord = !_isGroupRecording.value
        viewModelScope.launch {
            val activeActors = actors.filter { it.state.value.isConnected }
            if (activeActors.isEmpty()) return@launch

            val results = activeActors.map { actor ->
                async { actor.slotIndex to actor.setRecordingWithAck(targetRecord) }
            }.awaitAll()

            val failedSlots = results.filter { !it.second }.map { it.first }
            if (targetRecord) {
                if (failedSlots.size < activeActors.size) {
                    _isGroupRecording.value = true
                    _recordingStartTimeMs.value = System.currentTimeMillis()
                    val camNames = activeActors.map { it.state.value.displayHeader }
                    takeLogger.startTake(camNames)
                }
            } else {
                _isGroupRecording.value = false
                _recordingStartTimeMs.value = null
                takeLogger.stopCurrentTake()
            }

            if (failedSlots.isNotEmpty()) {
                triggerEmergencyHaptic()
                val failedNames = failedSlots.joinToString(", ") {
                    actors[it].state.value.displayHeader
                }
                _recordFailureBanner.value =
                    "تنبيه: فشل أمر ${if (targetRecord) "التسجيل" else "الإيقاف"} في: $failedNames"
            } else {
                _recordFailureBanner.value = null
            }
        }
    }

    fun dismissFailureBanner() {
        _recordFailureBanner.value = null
    }

    fun onSlateTriggered() {
        if (_isGroupRecording.value) {
            takeLogger.incrementSlateOnCurrentTake()
        }
    }

    fun shareTakeLogCsv() {
        val csvContent = takeLogger.exportPremiereMarkersCsv()
        runCatching {
            val logsDir = File(appContext.cacheDir, "take_logs").apply { mkdirs() }
            val outFile = File(logsDir, "CanonMultiCam_TakeLog_${System.currentTimeMillis()}.csv")
            outFile.writeText(csvContent)

            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                outFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "مشاركة سجل اللقطات CSV").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
        }
    }

    fun onTouchAf(slotIndex: Int, normX: Float, normY: Float) {
        if (slotIndex in 0..3) {
            actors[slotIndex].triggerTouchAf(normX, normY)
        }
    }

    fun renameCamera(slotIndex: Int, newName: String) {
        if (slotIndex in 0..3) {
            actors[slotIndex].renameCamera(newName)
        }
    }

    fun adjustSlotExposure(slotIndex: Int, newIso: Int? = null, newKelvin: Int? = null) {
        viewModelScope.launch {
            val actor = actors.getOrNull(slotIndex) ?: return@launch
            val current = actor.state.value.exposure
                ?: ExposureSettings("f4.0", "1/50", 800, 5600)
            val updated = current.copy(
                iso = (newIso ?: current.iso).coerceIn(100, 12800),
                kelvin = (newKelvin ?: current.kelvin).coerceIn(2500, 10000)
            )
            actor.syncExposureFrom(updated)
            actor.readExposure()
        }
    }

    fun importCubeLutAsync(
        slotIndex: Int,
        uri: Uri,
        onLutReady: (slotIndex: Int, lut: CubeLutData) -> Unit
    ) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    CubeLutParser.parse(stream).getOrNull()
                }
            }
            if (parsed != null && slotIndex in 0..3) {
                actors[slotIndex].enableLutBit()
                onLutReady(slotIndex, parsed)
            }
        }
    }

    fun matchAllToMaster(masterSlotIndex: Int) {
        viewModelScope.launch {
            val masterExposure = actors[masterSlotIndex].readExposure() ?: return@launch
            preMatchSnapshot = actors.mapNotNull { actor ->
                val currentExp = actor.state.value.exposure ?: return@mapNotNull null
                actor.slotIndex to currentExp
            }.toMap()
            _canUndoMatch.value = preMatchSnapshot.isNotEmpty()

            actors.filter { it.slotIndex != masterSlotIndex && it.state.value.isConnected }
                .forEach { slave -> slave.syncExposureFrom(masterExposure) }
        }
    }

    fun undoMatchAll() {
        val snapshot = preMatchSnapshot
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            snapshot.forEach { (slotIdx, oldExposure) ->
                if (actors[slotIdx].state.value.isConnected) {
                    actors[slotIdx].syncExposureFrom(oldExposure)
                }
            }
            preMatchSnapshot = emptyMap()
            _canUndoMatch.value = false
        }
    }

    private fun startExposureSyncLoop() {
        exposurePollJob = viewModelScope.launch {
            while (isActive) {
                // Parallel, and readExposure() skips stale/reconnecting cameras, so one dead
                // camera can no longer stall exposure polling for the others.
                actors.filter { it.state.value.isConnected }
                    .map { actor -> async { runCatching { actor.readExposure() }.getOrNull() } }
                    .awaitAll()
                delay(2500L)
            }
        }
    }

    private fun triggerEmergencyHaptic() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = appContext.getSystemService(VibratorManager::class.java)
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 400), -1)
            )
        }
    }

    override fun onCleared() {
        exposurePollJob?.cancel()
        thermalGuard.stop()
        actors.forEach { it.destroy() }
        super.onCleared()
    }
}
