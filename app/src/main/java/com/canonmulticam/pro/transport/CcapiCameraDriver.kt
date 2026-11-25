package com.canonmulticam.pro.transport

import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.ProtocolType
import com.canonmulticam.pro.domain.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream

@Suppress("TooManyFunctions")
class CcapiCameraDriver(override val ipAddress: String) : ICameraDriver {

    private val baseUrl = "http://$ipAddress:8080/ccapi"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = NetworkBinder.wifiBoundHttpClient

    private var apiVersion = "ver100"
    private var liveViewVersion = "ver100"
    private var supportedApertures: List<String> = emptyList()
    private var supportedShutters: List<String> = emptyList()
    private var scratchBuffer = ByteArray(512 * 1024)
    private var cachedLiveViewRequest: Request? = null

    override suspend fun handshake(): Result<CameraHandshakeInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val rootJson = getJson(baseUrl)
            val versions = listOf("ver130", "ver120", "ver110", "ver100")
            apiVersion = versions.firstOrNull { rootJson.has(it) } ?: "ver100"

            val devInfo = getJson("$baseUrl/$apiVersion/deviceinformation")
            val model = devInfo.optString("productname", "Canon CCAPI")
            val serial = devInfo.optString("serialnumber", "ccapi_$ipAddress")

            val avJson = runCatching {
                getJson("$baseUrl/$apiVersion/shooting/settings/av")
            }.getOrNull()
            supportedApertures = avJson?.optJSONArray("ability")?.let { arr ->
                List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
            } ?: emptyList()

            val tvJson = runCatching {
                getJson("$baseUrl/$apiVersion/shooting/settings/tv")
            }.getOrNull()
            supportedShutters = tvJson?.optJSONArray("ability")?.let { arr ->
                List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
            } ?: emptyList()

            liveViewVersion = if (rootJson.optJSONArray("ver110") != null) "ver110" else "ver100"
            setStreamQuality(StreamQuality.LOW_QUAD)

            CameraHandshakeInfo(
                uuid = serial,
                modelName = model,
                protocol = ProtocolType.CCAPI,
                supportedApertures = supportedApertures,
                supportedShutters = supportedShutters
            )
        }
    }

    override suspend fun setStreamQuality(quality: StreamQuality): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (display, size) = when (quality) {
                    StreamQuality.LOW_QUAD -> "on" to "small"
                    StreamQuality.FULL_SOLO -> "on" to "medium"
                    StreamQuality.PAUSED -> "off" to "small"
                }
                val body = JSONObject()
                    .put("liveviewsize", size)
                    .put("cameradisplay", display)
                    .toString()
                postJson("$baseUrl/$liveViewVersion/shooting/liveview", body)
                Unit
            }
        }

    override suspend fun pullNextLiveFrame(slot: LatestFrameSlot): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = cachedLiveViewRequest ?: Request.Builder()
                    .url("$baseUrl/$liveViewVersion/shooting/liveview/flip")
                    .get()
                    .build()
                    .also { cachedLiveViewRequest = it }
                client.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "CCAPI LiveView HTTP ${resp.code}" }
                    val stream = resp.body?.byteStream() ?: error("Empty LiveView body")
                    val bytesRead = readStreamIntoScratch(stream)
                    if (bytesRead > 0) {
                        slot.writeFrame(scratchBuffer, bytesRead)
                    }
                    if (scratchBuffer.size > 2 * 1024 * 1024 && bytesRead < 512 * 1024) {
                        scratchBuffer = ByteArray(512 * 1024)
                    }
                }
            }
        }

    override suspend fun setRecording(record: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val action = if (record) "start" else "stop"
            val body = JSONObject().put("action", action).toString()
            postJson("$baseUrl/$apiVersion/shooting/control/recbutton", body)
            Unit
        }
    }

    override suspend fun queryActualRecordingState(): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val status = getJson("$baseUrl/$apiVersion/shooting/control/recbutton")
                status.optString("status", "stop") == "start"
            }
        }

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val x = (normX.coerceIn(0f, 1f) * 10000).toInt()
                val y = (normY.coerceIn(0f, 1f) * 10000).toInt()
                val body = JSONObject()
                    .put("positionx", x)
                    .put("positiony", y)
                    .toString()
                postJson("$baseUrl/$apiVersion/shooting/control/touchaf", body)
                Unit
            }
        }

    override suspend fun getCurrentExposure(): Result<ExposureSettings> =
        withContext(Dispatchers.IO) {
            runCatching {
                val av = runCatching {
                    getJson("$baseUrl/$apiVersion/shooting/settings/av").optString("value", "f4.0")
                }.getOrDefault("f4.0")
                val tv = runCatching {
                    getJson("$baseUrl/$apiVersion/shooting/settings/tv").optString("value", "1/50")
                }.getOrDefault("1/50")
                val isoStr = runCatching {
                    getJson("$baseUrl/$apiVersion/shooting/settings/iso").optString("value", "800")
                }.getOrDefault("800")
                val kelvin = runCatching {
                    getJson("$baseUrl/$apiVersion/shooting/settings/colortemperature")
                        .optInt("value", 5600)
                }.getOrDefault(5600)

                ExposureSettings(
                    aperture = av,
                    shutterSpeed = tv,
                    iso = isoStr.toIntOrNull() ?: 800,
                    kelvin = kelvin
                )
            }
        }

    override suspend fun applyExposure(
        target: ExposureSettings,
        supportedApertures: List<String>,
        supportedShutters: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            putJson(
                "$baseUrl/$apiVersion/shooting/settings/iso",
                JSONObject().put("value", target.iso.toString()).toString()
            )
            putJson(
                "$baseUrl/$apiVersion/shooting/settings/colortemperature",
                JSONObject().put("value", target.kelvin).toString()
            )
            if (target.aperture in supportedApertures) {
                putJson(
                    "$baseUrl/$apiVersion/shooting/settings/av",
                    JSONObject().put("value", target.aperture).toString()
                )
            }
            if (target.shutterSpeed in supportedShutters) {
                putJson(
                    "$baseUrl/$apiVersion/shooting/settings/tv",
                    JSONObject().put("value", target.shutterSpeed).toString()
                )
            }
            Unit
        }
    }

    override suspend fun getBatteryStatus(): Result<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bat = getJson("$baseUrl/$apiVersion/devicestatus/battery")
                val level = bat.optString("level", "unknown")
                val isLow = level.equals("low", ignoreCase = true) ||
                    (level.removeSuffix("%").toIntOrNull()?.let { it <= 20 } ?: false)
                level to isLow
            }
        }

    override suspend fun getRemainingRecordMinutes(): Result<Int?> = withContext(Dispatchers.IO) {
        runCatching {
            val storage = getJson("$baseUrl/$apiVersion/devicestatus/storage")
            val list = storage.optJSONArray("storagelist") ?: return@runCatching null
            if (list.length() == 0) return@runCatching null
            val sec = list.getJSONObject(0).optInt("recordabletime", -1)
            if (sec >= 0) sec / 60 else null
        }
    }

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
            check(resp.isSuccessful) { "HTTP ${resp.code} on GET $url" }
            val text = resp.body?.string() ?: "{}"
            return JSONObject(text)
        }
    }

    private fun postJson(url: String, payload: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} on POST $url" }
            val text = resp.body?.string().orEmpty()
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun putJson(url: String, payload: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .put(payload.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} on PUT $url" }
            val text = resp.body?.string().orEmpty()
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    override fun close() = Unit
}
