package com.canonmulticam.pro.transport

import android.content.Context
import android.hardware.usb.UsbDevice
import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object CameraDriverFactory {

    @Volatile
    private var appContext: Context? = null
    private val usbDevices = ConcurrentHashMap<String, UsbDevice>()

    fun initUsbRegistry(context: Context) {
        appContext = context.applicationContext
    }

    fun registerUsbDevice(device: UsbDevice): String {
        val uri = "usb://${device.deviceName}"
        usbDevices[uri] = device
        return uri
    }

    fun unregisterUsbDevice(device: UsbDevice) {
        usbDevices.remove("usb://${device.deviceName}")
    }

    /**
     * Probes the camera and returns the first driver whose handshake succeeds.
     *
     * When [preferred] is set (a reconnect to a camera whose protocol is already known) only that
     * protocol is tried, which avoids walking the whole CCAPI -> Browser Remote -> PTP/IP chain
     * (several 2.5 s timeouts) on every reconnect attempt.
     */
    suspend fun connectAndLockProtocol(
        targetAddress: String,
        preferred: ProtocolType? = null
    ): Result<Pair<ICameraDriver, CameraHandshakeInfo>> = withContext(Dispatchers.IO) {
        if (targetAddress.startsWith("usb://")) {
            val ctx = appContext
                ?: return@withContext Result.failure(IllegalStateException("Context not initialized"))
            val usbDevice = usbDevices[targetAddress]
                ?: return@withContext Result.failure(IllegalStateException("USB device detached"))
            val usbDriver = UsbCanonCameraDriver(ctx, usbDevice)
            val usbRes = usbDriver.handshake()
            if (usbRes.isSuccess) {
                val info = usbRes.getOrNull()
                if (info != null) return@withContext Result.success(usbDriver to info)
            }
            usbDriver.close()
            return@withContext Result.failure(
                usbRes.exceptionOrNull() ?: IllegalStateException("USB Handshake failed")
            )
        }

        var firstFailure: Throwable? = null
        val order = if (preferred == null) {
            listOf(ProtocolType.CCAPI, ProtocolType.BROWSER_REMOTE, ProtocolType.PTP_IP)
        } else {
            listOf(preferred)
        }
        for (protocol in order) {
            val driver: ICameraDriver = when (protocol) {
                ProtocolType.CCAPI -> CcapiCameraDriver(targetAddress)
                ProtocolType.BROWSER_REMOTE -> BrowserRemoteCameraDriver(targetAddress)
                ProtocolType.PTP_IP -> PtpIpCameraDriver(targetAddress)
                ProtocolType.USB_PTP -> continue
            }
            val res = driver.handshake()
            val info = res.getOrNull()
            if (res.isSuccess && info != null) {
                return@withContext Result.success(driver to info)
            }
            if (firstFailure == null) firstFailure = res.exceptionOrNull()
            driver.close()
        }

        Result.failure(
            firstFailure
                ?: IllegalStateException("Camera unreachable on CCAPI, BrowserRemote & PTP/IP")
        )
    }
}
