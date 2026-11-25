package com.canonmulticam.pro.transport

import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.ProtocolType
import com.canonmulticam.pro.domain.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream

@Suppress("TooManyFunctions")
class BrowserRemoteCameraDriver(override val ipAddress: String) : ICameraDriver {

    private val baseUrl = "http://$ipAddress/api/cam"
    private val client = NetworkBinder.wifiBoundHttpClient
    private var scratchBuffer = ByteArray(512 * 1024)
    private var lastRecordingState = false

    override suspend fun handshake(): Result<CameraHandshakeInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val info = getJson("$baseUrl/getprop?r=0")
            val model = info.optString("model", "Canon Cinema (R5C/C200)")
            val serial = info.optString("serial", "br_$ipAddress")
            CameraHandshakeInfo(
                uuid = serial,
                modelName = model,
                protocol = ProtocolType.BROWSER_REMOTE
            )
        }
    }

    override suspend fun setStreamQuality(quality: StreamQuality): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = if (quality == StreamQuality.PAUSED) "stop" else "start"
                getJson("$baseUrl/lv?cmd=$cmd")
                Unit
            }
        }

    override suspend fun pullNextLiveFrame(slot: LatestFrameSlot): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/lv?cmd=get")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "BrowserRemote LV HTTP ${resp.code}" }
                    val stream = resp.body?.byteStream() ?: error("Empty LV stream")
                    val bytesRead = readStreamIntoScratch(stream)
                    if (bytesRead > 512) {
                        slot.writeFrame(scratchBuffer, bytesRead)
                    }
                }
            }
        }

    override suspend fun setRecording(record: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val action = if (record) "start" else "stop"
            getJson("$baseUrl/rec?cmd=$action")
            lastRecordingState = record
        }
    }

    override suspend fun queryActualRecordingState(): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prop = getJson("$baseUrl/getprop?r=1")
                val recStatus = prop.optString("rec", "")
                if (recStatus.isNotEmpty()) {
                    lastRecordingState = recStatus.equals("rec", ignoreCase = true) ||
                        recStatus == "1"
                }
                lastRecordingState
            }
        }

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val x = (normX.coerceIn(0f, 1f) * 1000).toInt()
                val y = (normY.coerceIn(0f, 1f) * 1000).toInt()
                getJson("$baseUrl/af?x=$x&y=$y")
                Unit
            }
        }

    override suspend fun getCurrentExposure(): Result<ExposureSettings> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prop = getJson("$baseUrl/getprop?r=1")
                val av = prop.optString("av", "f4.0")
                val tv = prop.optString("tv", "1/50")
                val iso = prop.optString("iso", "800").toIntOrNull() ?: 800
                val wb = prop.optString("wb", "5600").removeSuffix("K").toIntOrNull() ?: 5600
                ExposureSettings(av, tv, iso, wb)
            }
        }

    override suspend fun applyExposure(
        target: ExposureSettings,
        supportedApertures: List<String>,
        supportedShutters: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            getJson("$baseUrl/setprop?iso=${target.iso}")
            getJson("$baseUrl/setprop?wb=${target.kelvin}")
            Unit
        }
    }

    override suspend fun getBatteryStatus(): Result<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prop = getJson("$baseUrl/getprop?r=1")
                val bat = prop.optString("bat", "CINEMA")
                bat to bat.equals("low", ignoreCase = true)
            }
        }

    override suspend fun getRemainingRecordMinutes(): Result<Int?> =
        Result.success(null)

    private fun readStreamIntoScratch(input: InputStream): Int {
        var total = 0
        while (true) {
            if (total >= scratchBuffer.size) {
                if (scratchBuffer.size >= 4 * 1024 * 1024) break
                scratchBuffer = scratchBuffer.copyOf(scratchBuffer.size * 2)
            }
            val n = input.read(scratchBuffer, total, scratchBuffer.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} on $url" }
            val text = resp.body?.string().orEmpty()
            return if (text.startsWith("{")) JSONObject(text) else JSONObject()
        }
    }

    override fun close() = Unit
}
