package com.canonmulticam.pro.domain

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

enum class ProtocolType { CCAPI, BROWSER_REMOTE, PTP_IP, USB_PTP }

enum class StreamQuality {
    LOW_QUAD,
    FULL_SOLO,
    PAUSED
}

enum class LinkState {
    IDLE,
    CONNECTING,
    LIVE,
    RECONNECTING
}

enum class RecordAckState {
    IDLE,
    PENDING,
    RECORDING,
    ERROR
}

enum class AnamorphicRatio(val squeezeFactor: Float, val label: String) {
    NONE(1.0f, "1.0x"),
    X1_33(1.33f, "1.33x"),
    X1_5(1.5f, "1.5x"),
    X2_0(2.0f, "2.0x")
}

enum class PeakingColor(val label: String, val r: Float, val g: Float, val b: Float) {
    RED("PEAK R", 1.0f, 0.15f, 0.15f),
    GREEN("PEAK G", 0.15f, 1.0f, 0.25f),
    BLUE("PEAK B", 0.20f, 0.65f, 1.0f),
    WHITE("PEAK W", 0.98f, 0.98f, 0.98f)
}

data class ExposureSettings(
    val aperture: String,
    val shutterSpeed: String,
    val iso: Int,
    val kelvin: Int
)

data class CameraHandshakeInfo(
    val uuid: String,
    val modelName: String,
    val protocol: ProtocolType,
    val supportedApertures: List<String> = emptyList(),
    val supportedShutters: List<String> = emptyList()
)

data class CameraSlotState(
    val slotIndex: Int,
    val boundUuid: String? = null,
    val ipAddress: String? = null,
    val modelName: String = "CAM ${slotIndex + 1}",
    val customLabel: String? = null,
    val protocol: ProtocolType? = null,
    val isConnected: Boolean = false,
    val isSignalStale: Boolean = false,
    val link: LinkState = LinkState.IDLE,
    val reconnectAttempt: Int = 0,
    val latencyMs: Long = 0L,
    val recordState: RecordAckState = RecordAckState.IDLE,
    val batteryLabel: String = "--",
    val isBatteryLow: Boolean = false,
    val remainingCardMinutes: Int? = null,
    val exposure: ExposureSettings? = null,
    val isExposureDrifted: Boolean = false,
    val activeToolsMask: Int = 0,
    val anamorphic: AnamorphicRatio = AnamorphicRatio.NONE
) {
    val displayHeader: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: modelName
}

class LatestFrameSlot(initialCapacity: Int = 512 * 1024) {
    private val lock = ReentrantReadWriteLock()
    private var buffer = ByteArray(initialCapacity)
    private var validLength = 0
    private var sequenceId = 0L

    fun writeFrame(source: ByteArray, length: Int) {
        if (length <= 0) return
        lock.write {
            if (length > buffer.size) {
                buffer = ByteArray(length + 128 * 1024)
            }
            System.arraycopy(source, 0, buffer, 0, length)
            validLength = length
            sequenceId++
        }
    }

    fun <T> readLatestFrame(lastConsumedSeq: Long, consumer: (ByteArray, Int, Long) -> T): T? {
        lock.read {
            if (validLength <= 0 || sequenceId == lastConsumedSeq) return null
            return consumer(buffer, validLength, sequenceId)
        }
    }
}
