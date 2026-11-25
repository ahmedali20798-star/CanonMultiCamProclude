package com.canonmulticam.pro.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredCamera(
    val uuid: String,
    val ipAddress: String,
    val port: Int,
    val modelName: String,
    val ccapiBaseUrl: String
)

/**
 * Finds cameras on the Wi-Fi network.
 *
 * - SSDP (cheap, one UDP packet) runs every cycle while a slot is free.
 * - The subnet sweep is a fallback: it runs only while NO slot owns an address, backs off
 *   (10s -> 60s) when it keeps finding nothing, and never runs while a camera is streaming,
 *   so it cannot compete with live-view traffic on the camera's weak Wi-Fi.
 * - Everything pauses while the app is in the background or Wi-Fi is down.
 */
class CameraDiscoveryService(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    @Volatile private var paused = false
    private val sweepRequested = AtomicBoolean(false)

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** Forces one subnet sweep on the next cycle (e.g. a "rescan" button). */
    fun requestSweep() {
        sweepRequested.set(true)
    }

    fun startDiscovery(
        connectedCountProvider: () -> Int,
        knownAddressesProvider: () -> Set<String> = { emptySet() },
        onCameraFound: (DiscoveredCamera) -> Unit
    ) {
        scanJob?.cancel()
        scanJob = scope.launch {
            var emptySweeps = 0
            var nextSweepAtMs = 0L
            while (isActive) {
                if (paused || NetworkBinder.localWifiIpFlow.value == null) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                if (connectedCountProvider() < MAX_CAMERAS) {
                    val known = knownAddressesProvider()
                    runSsdpProbe(known, onCameraFound)

                    val now = SystemClock.elapsedRealtime()
                    val manual = sweepRequested.getAndSet(false)
                    val automatic = known.isEmpty() && now >= nextSweepAtMs
                    if (manual || automatic) {
                        val found = runSubnetSweep(known, onCameraFound)
                        emptySweeps = if (found > 0) 0 else emptySweeps + 1
                        val backoff = SWEEP_BASE_MS shl emptySweeps.coerceAtMost(SWEEP_MAX_SHIFT)
                        nextSweepAtMs = SystemClock.elapsedRealtime() + minOf(backoff, SWEEP_CAP_MS)
                    }
                }
                delay(CYCLE_MS)
            }
        }
    }

    private fun runSsdpProbe(known: Set<String>, onCameraFound: (DiscoveredCamera) -> Unit) {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("CanonMultiCamSSDP")?.apply {
            setReferenceCounted(true)
            acquire()
        }
        try {
            val query = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)

            DatagramSocket().use { socket ->
                NetworkBinder.bindDatagramSocket(socket)
                socket.soTimeout = SSDP_WINDOW_MS.toInt()
                val group = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(query, query.size, group, 1900))

                val seen = HashSet<String>()
                val buf = ByteArray(2048)
                val endTime = SystemClock.elapsedRealtime() + SSDP_WINDOW_MS
                while (SystemClock.elapsedRealtime() < endTime) {
                    val packet = DatagramPacket(buf, buf.size)
                    if (runCatching { socket.receive(packet) }.isFailure) break
                    handleSsdpReply(packet, known, seen, onCameraFound)
                }
            }
        } catch (_: Exception) {
            // No Wi-Fi / socket error: the next cycle retries.
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun handleSsdpReply(
        packet: DatagramPacket,
        known: Set<String>,
        seen: MutableSet<String>,
        onCameraFound: (DiscoveredCamera) -> Unit
    ) {
        val senderIp = packet.address?.hostAddress ?: return
        if (senderIp in known || !seen.add(senderIp)) return
        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
        if (msg.contains("Canon", ignoreCase = true) || msg.contains("ccapi", ignoreCase = true)) {
            onCameraFound(cameraAt(senderIp, "ssdp_$senderIp"))
        }
    }

    /** @return number of cameras reported. */
    private suspend fun runSubnetSweep(
        known: Set<String>,
        onCameraFound: (DiscoveredCamera) -> Unit
    ): Int {
        val localIp = NetworkBinder.localWifiIpFlow.value ?: return 0
        val prefix = localIp.substringBeforeLast('.', "")
        if (prefix.isBlank()) return 0
        val selfOctet = localIp.substringAfterLast('.').toIntOrNull()
        val gateway = NetworkBinder.gatewayIpFlow.value

        // 1..254: a camera in access-point mode IS the gateway, typically 192.168.1.1.
        val candidates = (1..LAST_HOST)
            .filter { it != selfOctet }
            .map { "$prefix.$it" }
            .filter { it !in known }
            .sortedBy { if (it == gateway) 0 else 1 }

        var found = 0
        for (batch in candidates.chunked(SWEEP_PARALLELISM)) {
            if (paused || !scanJobActive()) break
            val hits = coroutineScope {
                batch.map { ip -> async { if (probeCamera(ip)) ip else null } }.awaitAll()
            }.filterNotNull()
            for (ip in hits) {
                found++
                onCameraFound(cameraAt(ip, "subnet_$ip"))
            }
        }
        return found
    }

    private fun scanJobActive(): Boolean = scanJob?.isActive == true

    private fun cameraAt(ip: String, uuid: String) = DiscoveredCamera(
        uuid = uuid,
        ipAddress = ip,
        port = CCAPI_PORT,
        modelName = "Canon EOS",
        ccapiBaseUrl = "http://$ip:$CCAPI_PORT/ccapi"
    )

    /** Cheap TCP pre-check first; HTTP is only attempted on hosts that actually listen. */
    private fun probeCamera(ip: String): Boolean {
        if (isPortOpen(ip, CCAPI_PORT) && httpOk("http://$ip:$CCAPI_PORT/ccapi")) return true
        if (isPortOpen(ip, HTTP_PORT) && httpOk("http://$ip/api/cam/getprop?r=0")) return true
        return isPortOpen(ip, PTP_IP_PORT)
    }

    private fun isPortOpen(ip: String, port: Int): Boolean =
        runCatching { NetworkBinder.openBoundTcpSocket(ip, port, PORT_PROBE_TIMEOUT_MS).use { true } }
            .getOrDefault(false)

    private fun httpOk(url: String): Boolean = runCatching {
        val req = Request.Builder().url(url).get().build()
        NetworkBinder.discoveryHttpClient.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun destroy() {
        scanJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val MAX_CAMERAS = 4
        const val CYCLE_MS = 4_500L
        const val IDLE_POLL_MS = 1_000L
        const val SSDP_WINDOW_MS = 1_200L
        const val SWEEP_BASE_MS = 10_000L
        const val SWEEP_CAP_MS = 60_000L
        const val SWEEP_MAX_SHIFT = 3
        const val SWEEP_PARALLELISM = 32
        const val PORT_PROBE_TIMEOUT_MS = 300
        const val LAST_HOST = 254
        const val CCAPI_PORT = 8080
        const val HTTP_PORT = 80
        const val PTP_IP_PORT = 15740
    }
}
