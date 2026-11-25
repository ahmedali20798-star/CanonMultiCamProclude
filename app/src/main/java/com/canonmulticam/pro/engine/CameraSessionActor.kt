package com.canonmulticam.pro.engine

import android.os.SystemClock
import android.util.Log
import com.canonmulticam.pro.data.CameraLabelStore
import com.canonmulticam.pro.domain.AnamorphicRatio
import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.CameraSlotState
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.LinkState
import com.canonmulticam.pro.domain.ProtocolType
import com.canonmulticam.pro.domain.RecordAckState
import com.canonmulticam.pro.domain.StreamQuality
import com.canonmulticam.pro.gl.ShaderToolFlags
import com.canonmulticam.pro.transport.CameraDriverFactory
import com.canonmulticam.pro.transport.ICameraDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

@Suppress("TooManyFunctions")
class CameraSessionActor(
    val slotIndex: Int,
    private val labelStore: CameraLabelStore,
    private val thermalLevelProvider: () -> ThermalLevel,
    private val onUnexpectedRecordStop: (Int) -> Unit,
    private val onDisconnectedCallback: (Int) -> Unit,
    private val onFrameUpdated: (Int, LatestFrameSlot) -> Unit,
    /** Returns false when another slot already owns this camera (duplicate session). */
    private val claimIdentity: (slotIndex: Int, uuid: String) -> Boolean = { _, _ -> true }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameSlot = LatestFrameSlot()

    private val _state = MutableStateFlow(CameraSlotState(slotIndex = slotIndex))
    val state: StateFlow<CameraSlotState> = _state.asStateFlow()

    private val sessionLock = Any()

    @Volatile private var generation = 0
    @Volatile private var driver: ICameraDriver? = null
    private var sessionJob: Job? = null

    @Volatile private var supportedApertures: List<String> = emptyList()
    @Volatile private var supportedShutters: List<String> = emptyList()
    @Volatile private var currentQuality: StreamQuality = StreamQuality.LOW_QUAD

    private enum class LoopEnd { LINK_LOST, CANCELLED }

    /**
     * Starts (or restarts) a supervised session for [targetAddress].
     *
     * Idempotent: if a session for the same address is already running (connecting, live or
     * reconnecting) this is a no-op unless [force] is set, so repeated discovery hits can no
     * longer cancel a handshake that is still in progress. The slot is claimed synchronously,
     * before the coroutine starts, so two discovery sources cannot grab two slots for one camera.
     *
     * @return true if a new session was started.
     */
    fun connect(targetAddress: String, initialQuality: StreamQuality, force: Boolean = false): Boolean {
        var gen = 0
        var oldJob: Job? = null
        synchronized(sessionLock) {
            val current = _state.value
            if (!force && current.ipAddress == targetAddress && sessionJob?.isActive == true) {
                return false
            }
            gen = ++generation
            oldJob = sessionJob
            val staleDriver = driver
            driver = null
            currentQuality = initialQuality
            _state.value = current.copy(
                ipAddress = targetAddress,
                link = LinkState.CONNECTING,
                isConnected = false,
                isSignalStale = false,
                reconnectAttempt = 0
            )
            val myGen = gen
            sessionJob = scope.launch { superviseSession(targetAddress, myGen, staleDriver) }
        }
        oldJob?.cancel()
        return true
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun superviseSession(target: String, gen: Int, staleDriver: ICameraDriver?) {
        if (staleDriver != null) {
            // Free the camera's session slot BEFORE the new handshake (PTP/IP and USB allow one).
            runCatching { staleDriver.close() }
            delay(PORT_RELEASE_DELAY_MS)
        }
        val isUsb = target.startsWith("usb://")
        var failedAttempts = 0
        var everLive = false
        var downSinceMs = 0L
        var preferred: ProtocolType? = null

        while (isCurrent(gen)) {
            val result = try {
                CameraDriverFactory.connectAndLockProtocol(target, preferred)
            } catch (ce: CancellationException) {
                throw ce
            } catch (expected: Exception) {
                Result.failure(expected)
            }
            val established = result.getOrNull()
            if (!isCurrent(gen)) {
                // Superseded while the blocking handshake was in flight: do not leak the session.
                established?.first?.let { runCatching { it.close() } }
                return
            }

            if (established != null) {
                val (drv, info) = established
                if (!claimIdentity(slotIndex, info.uuid)) {
                    Log.w(TAG, "Slot $slotIndex: ${info.uuid} already owned by another slot, releasing")
                    runCatching { drv.close() }
                    releaseIfCurrent(gen)
                    return
                }
                if (!installDriver(gen, drv, info, target)) {
                    runCatching { drv.close() }
                    return
                }
                failedAttempts = 0
                downSinceMs = 0L
                everLive = true
                preferred = info.protocol

                drv.setStreamQuality(currentQuality)
                val ended = try {
                    runLiveViewAndTelemetryLoop(drv, gen)
                } catch (ce: CancellationException) {
                    runCatching { drv.close() }
                    throw ce
                } catch (expected: Exception) {
                    Log.w(TAG, "Slot $slotIndex: live loop failed", expected)
                    LoopEnd.LINK_LOST
                }
                detachDriver(gen, drv)
                runCatching { drv.close() }
                if (ended == LoopEnd.CANCELLED) return

                Log.w(TAG, "Slot $slotIndex: link lost, reconnecting to $target")
                downSinceMs = SystemClock.elapsedRealtime()
                updateIfCurrent(gen) {
                    it.copy(link = LinkState.RECONNECTING, isConnected = true, isSignalStale = true)
                }
                delay(PORT_RELEASE_DELAY_MS)
                continue
            }

            failedAttempts++
            if (preferred != null && failedAttempts >= PREFERRED_PROTOCOL_RETRIES) {
                preferred = null
            }
            val downFor = if (downSinceMs == 0L) 0L else SystemClock.elapsedRealtime() - downSinceMs
            if (ReconnectPolicy.shouldGiveUp(isUsb, everLive, failedAttempts, downFor)) {
                Log.w(TAG, "Slot $slotIndex: giving up on $target after $failedAttempts attempts")
                releaseIfCurrent(gen)
                return
            }
            updateIfCurrent(gen) { it.copy(reconnectAttempt = failedAttempts) }
            delay(ReconnectPolicy.backoffMs(failedAttempts, Random.nextDouble(JITTER_MAX)))
        }
    }

    private fun installDriver(
        gen: Int,
        drv: ICameraDriver,
        info: CameraHandshakeInfo,
        target: String
    ): Boolean {
        val savedLabel = labelStore.getCustomLabel(info.uuid)
        synchronized(sessionLock) {
            if (generation != gen) return false
            driver = drv
            supportedApertures = info.supportedApertures
            supportedShutters = info.supportedShutters
            _state.update {
                it.copy(
                    boundUuid = info.uuid,
                    ipAddress = target,
                    modelName = info.modelName,
                    customLabel = savedLabel,
                    protocol = info.protocol,
                    isConnected = true,
                    isSignalStale = false,
                    link = LinkState.LIVE,
                    reconnectAttempt = 0
                )
            }
        }
        return true
    }

    private fun detachDriver(gen: Int, drv: ICameraDriver) {
        synchronized(sessionLock) {
            if (generation == gen && driver === drv) driver = null
        }
    }

    private fun isCurrent(gen: Int): Boolean = generation == gen

    private fun updateIfCurrent(gen: Int, transform: (CameraSlotState) -> CameraSlotState) {
        synchronized(sessionLock) {
            if (generation == gen) _state.update(transform)
        }
    }

    private fun releaseIfCurrent(gen: Int) {
        if (isCurrent(gen)) disconnect()
    }

    private suspend fun runLiveViewAndTelemetryLoop(drv: ICameraDriver, gen: Int): LoopEnd {
        var consecutiveErrors = 0
        var loopTick = 0
        var lastUiTelemetryEmitMs = 0L
        var lastGoodMs = SystemClock.elapsedRealtime()
        while (isCurrent(gen)) {
            if (currentQuality == StreamQuality.PAUSED) {
                lastGoodMs = SystemClock.elapsedRealtime()
                delay(PAUSED_POLL_MS)
                continue
            }

            val t0 = SystemClock.elapsedRealtime()
            val frameRes = drv.pullNextLiveFrame(frameSlot)
            val now = SystemClock.elapsedRealtime()
            if (!isCurrent(gen)) return LoopEnd.CANCELLED

            if (frameRes.isSuccess) {
                consecutiveErrors = 0
                lastGoodMs = now
                onFrameUpdated(slotIndex, frameSlot)
                if (_state.value.isSignalStale || now - lastUiTelemetryEmitMs >= 1000L) {
                    lastUiTelemetryEmitMs = now
                    updateIfCurrent(gen) { it.copy(isSignalStale = false, latencyMs = now - t0) }
                }
            } else {
                consecutiveErrors++
                val downMs = now - lastGoodMs
                if (ReconnectPolicy.isStale(downMs) && !_state.value.isSignalStale) {
                    updateIfCurrent(gen) { it.copy(isSignalStale = true) }
                }
                if (ReconnectPolicy.shouldReconnect(downMs, consecutiveErrors)) {
                    return LoopEnd.LINK_LOST
                }
            }

            if (consecutiveErrors == 0 && loopTick % TELEMETRY_EVERY_TICKS == 0) {
                pollTelemetry(drv)
            }
            loopTick++
            delay(
                if (consecutiveErrors == 0) {
                    thermalLevelProvider().frameDelayMs
                } else {
                    ReconnectPolicy.errorDelayMs(consecutiveErrors)
                }
            )
        }
        return LoopEnd.CANCELLED
    }

    private suspend fun pollTelemetry(drv: ICameraDriver) {
        drv.getBatteryStatus().onSuccess { (batLabel, low) ->
            _state.update { it.copy(batteryLabel = batLabel, isBatteryLow = low) }
        }
        drv.getRemainingRecordMinutes().onSuccess { mins ->
            _state.update { it.copy(remainingCardMinutes = mins) }
        }
        if (_state.value.recordState == RecordAckState.RECORDING) {
            drv.queryActualRecordingState().onSuccess { stillRecording ->
                if (!stillRecording) {
                    _state.update { it.copy(recordState = RecordAckState.ERROR) }
                    onUnexpectedRecordStop(slotIndex)
                }
            }
        }
    }

    suspend fun setRecordingWithAck(record: Boolean): Boolean {
        val drv = driver ?: return false
        _state.update { it.copy(recordState = RecordAckState.PENDING) }
        val res = drv.setRecording(record)
        return if (res.isSuccess) {
            _state.update {
                it.copy(
                    recordState = if (record) RecordAckState.RECORDING else RecordAckState.IDLE
                )
            }
            true
        } else {
            _state.update { it.copy(recordState = RecordAckState.ERROR) }
            false
        }
    }

    fun triggerTouchAf(normX: Float, normY: Float) {
        scope.launch { driver?.triggerTouchAf(normX, normY) }
    }

    suspend fun readExposure(): ExposureSettings? {
        if (_state.value.isSignalStale) return null
        val exp = driver?.getCurrentExposure()?.getOrNull() ?: return null
        _state.update { it.copy(exposure = exp) }
        return exp
    }

    suspend fun syncExposureFrom(master: ExposureSettings) {
        val drv = driver ?: return
        drv.applyExposure(master, supportedApertures, supportedShutters).onSuccess {
            _state.update { it.copy(exposure = master, isExposureDrifted = false) }
        }
    }

    fun updateStreamQuality(quality: StreamQuality) {
        currentQuality = quality
        scope.launch { driver?.setStreamQuality(quality) }
    }

    fun updateToolsMask(newMask: Int) {
        _state.update { it.copy(activeToolsMask = newMask) }
    }

    fun enableLutBit() {
        _state.update { it.copy(activeToolsMask = it.activeToolsMask or ShaderToolFlags.LUT) }
    }

    fun updateAnamorphic(ratio: AnamorphicRatio) {
        _state.update { it.copy(anamorphic = ratio) }
    }

    fun renameCamera(newName: String) {
        val uuid = _state.value.boundUuid ?: return
        labelStore.saveCustomLabel(uuid, newName)
        _state.update { it.copy(customLabel = newName.trim().ifEmpty { null }) }
    }

    fun disconnect() {
        var job: Job? = null
        var drv: ICameraDriver? = null
        synchronized(sessionLock) {
            generation++
            job = sessionJob
            sessionJob = null
            drv = driver
            driver = null
            _state.value = CameraSlotState(slotIndex = slotIndex)
        }
        job?.cancel()
        runCatching { drv?.close() }
        onDisconnectedCallback(slotIndex)
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    private companion object {
        const val TAG = "CameraSessionActor"
        const val PORT_RELEASE_DELAY_MS = 300L
        const val PAUSED_POLL_MS = 200L
        const val TELEMETRY_EVERY_TICKS = 30
        const val PREFERRED_PROTOCOL_RETRIES = 3
        const val JITTER_MAX = 0.25
    }
}
