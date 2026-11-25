package com.canonmulticam.pro.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Pins all camera traffic to the Wi-Fi network (camera Wi-Fi usually has no internet, so Android
 * would otherwise route through mobile data) and tracks the Wi-Fi IP / gateway.
 */
object NetworkBinder {

    private var connectivityManager: ConnectivityManager? = null
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var activeWifiNetwork: Network? = null

    private val _localWifiIpFlow = MutableStateFlow<String?>(null)
    val localWifiIpFlow: StateFlow<String?> = _localWifiIpFlow.asStateFlow()

    private val _gatewayIpFlow = MutableStateFlow<String?>(null)
    val gatewayIpFlow: StateFlow<String?> = _gatewayIpFlow.asStateFlow()

    /**
     * The Wi-Fi network to bind to. Falls back to the system's active network when it is Wi-Fi.
     * Never returns a non-Wi-Fi network: callers fail fast instead of silently using mobile data.
     */
    private fun resolveWifiNetwork(): Network? {
        activeWifiNetwork?.let { return it }
        val cm = connectivityManager ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) active else null
    }

    /** Binds an unconnected socket to Wi-Fi. Throws IOException if there is no Wi-Fi network. */
    private fun bindOrFail(socket: Socket): Socket {
        val network = resolveWifiNetwork()
        if (network == null) {
            runCatching { socket.close() }
            throw IOException("No Wi-Fi network available")
        }
        try {
            network.bindSocket(socket)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        return socket
    }

    /** Binds a UDP socket (SSDP) to Wi-Fi so multicast does not leave via mobile data. */
    fun bindDatagramSocket(socket: DatagramSocket) {
        val network = resolveWifiNetwork() ?: throw IOException("No Wi-Fi network available")
        network.bindSocket(socket)
    }

    // Sockets are always bound BEFORE connecting; Socket(host, port) would connect first and
    // bind too late (via the default network).
    private val wifiSocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = bindOrFail(Socket())

        override fun createSocket(host: String, port: Int): Socket =
            bindOrFail(Socket()).also { it.connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = bindOrFail(Socket()).also {
            it.bind(InetSocketAddress(localHost, localPort))
            it.connect(InetSocketAddress(host, port))
        }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            bindOrFail(Socket()).also { it.connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = bindOrFail(Socket()).also {
            it.bind(InetSocketAddress(localAddress, localPort))
            it.connect(InetSocketAddress(address, port))
        }
    }

    val wifiBoundHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(6000, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
        .socketFactory(wifiSocketFactory)
        .build()

    val discoveryHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(450, TimeUnit.MILLISECONDS)
        .writeTimeout(350, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(4, 5, TimeUnit.SECONDS))
        .socketFactory(wifiSocketFactory)
        .build()

    /** Idempotent: safe to call from every Activity.onCreate; registers exactly one callback. */
    @Synchronized
    fun init(context: Context) {
        if (registeredCallback != null) return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeWifiNetwork = network
                updateLinkInfo(cm.getLinkProperties(network))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                activeWifiNetwork = network
                updateLinkInfo(linkProperties)
            }

            override fun onLost(network: Network) {
                if (activeWifiNetwork == network) {
                    activeWifiNetwork = null
                    _localWifiIpFlow.value = null
                    _gatewayIpFlow.value = null
                }
            }
        }
        cm.registerNetworkCallback(request, callback)
        registeredCallback = callback
    }

    fun openBoundTcpSocket(ip: String, port: Int, timeoutMs: Int = 2000): Socket {
        val socket = bindOrFail(Socket())
        try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = timeoutMs
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        return socket
    }

    private fun updateLinkInfo(props: LinkProperties?) {
        val ipv4 = props?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
        _localWifiIpFlow.value = ipv4
        _gatewayIpFlow.value = props?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
    }
}
