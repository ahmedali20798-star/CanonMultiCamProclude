package com.canonmulticam.pro.transport

import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.ProtocolType
import com.canonmulticam.pro.domain.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

@Suppress("TooManyFunctions")
class PtpIpCameraDriver(override val ipAddress: String) : ICameraDriver {

    private val mutex = Mutex()
    private val txId = AtomicInteger(1)
    private var cmdSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var cmdIn: DataInputStream? = null
    private var cmdOut: DataOutputStream? = null
    private var recording = false
    private var currentExposure = ExposureSettings("f4.0", "1/50", 800, 5600)
    private var frameBuffer = ByteArray(1024 * 1024)

    companion object {
        private const val PTP_PORT = 15740
        private const val PKT_INIT_CMD_REQ = 0x00000001
        private const val PKT_INIT_CMD_ACK = 0x00000002
        private const val PKT_INIT_EVENT_REQ = 0x00000003
        private const val PKT_CMD_REQ = 0x00000006
        private const val PKT_CMD_RESP = 0x00000007
        private const val PKT_START_DATA = 0x00000009
        private const val PKT_DATA = 0x0000000A
        private const val PKT_END_DATA = 0x0000000C

        private const val OP_OPEN_SESSION = 0x1002
        private const val OP_EOS_SET_DEVICE_PROP_VALUE = 0x9110
        private const val OP_EOS_SET_REMOTE_MODE = 0x9114
        private const val OP_EOS_SET_EVENT_MODE = 0x9115
        private const val OP_EOS_GET_VIEWFINDER = 0x9153
        private const val OP_EOS_SET_TOUCH_AF = 0x915B

        private const val PROP_EOS_ISO = 0xD103
        private const val PROP_EOS_COLOR_TEMP = 0xD10A
        private const val PROP_EOS_EVF_OUTPUT = 0xD1B0
        private const val PROP_EOS_MOVIE_RECORD = 0xD1B8
    }

    override suspend fun handshake(): Result<CameraHandshakeInfo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                close()
                val cSock = NetworkBinder.openBoundTcpSocket(ipAddress, PTP_PORT, 2000)
                val outS = DataOutputStream(cSock.getOutputStream())
                val inS = DataInputStream(cSock.getInputStream())

                val initPayload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(40)
                    putInt(PKT_INIT_CMD_REQ)
                    putLong(0x0011223344556677L)
                    putLong(0x8899AABBCCDDEEFFL)
                    putShort('C'.code.toShort())
                    putShort(0)
                    putInt(0x00010000)
                }.array()
                outS.write(initPayload)
                outS.flush()

                val respLen = Integer.reverseBytes(inS.readInt())
                val respType = Integer.reverseBytes(inS.readInt())
                check(respType == PKT_INIT_CMD_ACK && respLen >= 12) {
                    "PTP/IP InitCmdAck invalid: $respType"
                }
                val connNumber = Integer.reverseBytes(inS.readInt())
                val remaining = (respLen - 12).coerceAtLeast(0)
                if (remaining > 0) inS.skipBytes(remaining)

                val eSock = NetworkBinder.openBoundTcpSocket(ipAddress, PTP_PORT, 2000)
                val eOut = DataOutputStream(eSock.getOutputStream())
                val evInit = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(12)
                    putInt(PKT_INIT_EVENT_REQ)
                    putInt(connNumber)
                }.array()
                eOut.write(evInit)
                eOut.flush()

                cmdSocket = cSock
                eventSocket = eSock
                cmdIn = inS
                cmdOut = outS

                sendOperation(OP_OPEN_SESSION, intArrayOf(1))
                sendOperation(OP_EOS_SET_REMOTE_MODE, intArrayOf(1))
                sendOperation(OP_EOS_SET_EVENT_MODE, intArrayOf(1))
                setEosProp(PROP_EOS_EVF_OUTPUT, 3)

                CameraHandshakeInfo(
                    uuid = "ptpip_$ipAddress",
                    modelName = "Canon PTP/IP",
                    protocol = ProtocolType.PTP_IP
                )
            }.onFailure { close() }
        }
    }

    override suspend fun setStreamQuality(quality: StreamQuality): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val outDev = if (quality == StreamQuality.PAUSED) 1 else 3
                    setEosProp(PROP_EOS_EVF_OUTPUT, outDev)
                }
            }
        }

    override suspend fun pullNextLiveFrame(slot: LatestFrameSlot): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val outS = cmdOut ?: error("PTP/IP closed")
                    val inS = cmdIn ?: error("PTP/IP closed")
                    val id = txId.getAndIncrement()
                    writeCmdPacket(outS, OP_EOS_GET_VIEWFINDER, id, intArrayOf(0x00100000))

                    var offset = 0
                    while (true) {
                        val len = Integer.reverseBytes(inS.readInt())
                        val type = Integer.reverseBytes(inS.readInt())
                        val payloadLen = (len - 8).coerceAtLeast(0)
                        when (type) {
                            PKT_START_DATA -> inS.skipBytes(payloadLen)
                            PKT_DATA, PKT_END_DATA -> {
                                inS.skipBytes(4)
                                val chunkLen = (payloadLen - 4).coerceAtLeast(0)
                                if (offset + chunkLen <= frameBuffer.size && chunkLen > 0) {
                                    inS.readFully(frameBuffer, offset, chunkLen)
                                    offset += chunkLen
                                } else if (chunkLen > 0) {
                                    inS.skipBytes(chunkLen)
                                }
                            }
                            PKT_CMD_RESP -> {
                                inS.skipBytes(payloadLen)
                                if (offset > 512) {
                                    val start = findJpegStart(frameBuffer, offset)
                                    if (start >= 0) {
                                        val clean = offset - start
                                        if (start > 0) {
                                            System.arraycopy(frameBuffer, start, frameBuffer, 0, clean)
                                        }
                                        slot.writeFrame(frameBuffer, clean)
                                    }
                                }
                                break
                            }
                            else -> if (payloadLen > 0) inS.skipBytes(payloadLen)
                        }
                    }
                }
            }
        }

    override suspend fun setRecording(record: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    setEosProp(PROP_EOS_MOVIE_RECORD, if (record) 4 else 0)
                    recording = record
                }
            }
        }

    override suspend fun queryActualRecordingState(): Result<Boolean> =
        Result.success(recording)

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val x = (normX.coerceIn(0f, 1f) * 10000).toInt()
                    val y = (normY.coerceIn(0f, 1f) * 10000).toInt()
                    sendOperation(OP_EOS_SET_TOUCH_AF, intArrayOf(x, y))
                }
            }
        }

    override suspend fun getCurrentExposure(): Result<ExposureSettings> =
        Result.success(currentExposure)

    override suspend fun applyExposure(
        target: ExposureSettings,
        supportedApertures: List<String>,
        supportedShutters: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                setEosProp(PROP_EOS_ISO, UsbCanonCameraDriver.isoToCanonCode(target.iso))
                setEosProp(PROP_EOS_COLOR_TEMP, target.kelvin)
                currentExposure = target
            }
        }
    }

    override suspend fun getBatteryStatus(): Result<Pair<String, Boolean>> =
        Result.success("PTP/IP" to false)

    override suspend fun getRemainingRecordMinutes(): Result<Int?> =
        Result.success(null)

    private fun setEosProp(propCode: Int, value: Int) {
        val outS = cmdOut ?: error("PTP/IP closed")
        val inS = cmdIn ?: error("PTP/IP closed")
        val id = txId.getAndIncrement()
        writeCmdPacket(outS, OP_EOS_SET_DEVICE_PROP_VALUE, id, intArrayOf(propCode))

        val startPkt = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(20)
            putInt(PKT_START_DATA)
            putInt(id)
            putLong(12L)
        }.array()
        outS.write(startPkt)

        val endDataPkt = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(24)
            putInt(PKT_END_DATA)
            putInt(id)
            putInt(12)
            putInt(propCode)
            putInt(value)
        }.array()
        outS.write(endDataPkt)
        outS.flush()

        while (true) {
            val len = Integer.reverseBytes(inS.readInt())
            val type = Integer.reverseBytes(inS.readInt())
            val rest = (len - 8).coerceAtLeast(0)
            if (rest > 0) inS.skipBytes(rest)
            if (type == PKT_CMD_RESP) break
        }
    }

    private fun sendOperation(opCode: Int, params: IntArray) {
        val outS = cmdOut ?: error("PTP/IP closed")
        val inS = cmdIn ?: error("PTP/IP closed")
        writeCmdPacket(outS, opCode, txId.getAndIncrement(), params)
        while (true) {
            val len = Integer.reverseBytes(inS.readInt())
            val type = Integer.reverseBytes(inS.readInt())
            val rest = (len - 8).coerceAtLeast(0)
            if (rest > 0) inS.skipBytes(rest)
            if (type == PKT_CMD_RESP) break
        }
    }

    private fun writeCmdPacket(outS: DataOutputStream, opCode: Int, id: Int, params: IntArray) {
        val totalLen = 18 + params.size * 4
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(totalLen)
            putInt(PKT_CMD_REQ)
            putInt(1)
            putShort(opCode.toShort())
            putInt(id)
            for (p in params) putInt(p)
        }
        outS.write(buf.array())
        outS.flush()
    }

    private fun findJpegStart(buf: ByteArray, length: Int): Int {
        val limit = (length - 1).coerceAtMost(64)
        for (i in 0 until limit) {
            if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xFF) == 0xD8) {
                return i
            }
        }
        return -1
    }

    override fun close() {
        runCatching { cmdSocket?.close() }
        runCatching { eventSocket?.close() }
        cmdSocket = null
        eventSocket = null
        cmdIn = null
        cmdOut = null
    }
}
