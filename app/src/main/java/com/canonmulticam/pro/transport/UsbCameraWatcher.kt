package com.canonmulticam.pro.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class UsbCameraWatcher(
    context: Context,
    private val onUsbCameraReady: (DiscoveredCamera) -> Unit,
    private val onUsbCameraDetached: (uuid: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        private const val ACTION_USB_PERMISSION = "com.canonmulticam.pro.USB_PERMISSION"
        private const val CANON_VENDOR_ID = 0x04A9
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            handleIntent(intent)
        }
    }

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let { inspectDevice(it) }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> device?.let {
                CameraDriverFactory.unregisterUsbDevice(it)
                onUsbCameraDetached(resolveStableUuid(it))
            }
            ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    publishUsbCamera(device)
                }
            }
        }
    }

    fun start() {
        CameraDriverFactory.initUsbRegistry(appContext)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        usbManager.deviceList.values.forEach { inspectDevice(it) }
    }

    private fun inspectDevice(device: UsbDevice) {
        if (!isCanonOrPtpDevice(device)) return
        if (usbManager.hasPermission(device)) {
            publishUsbCamera(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getBroadcast(
                appContext,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                flags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun publishUsbCamera(device: UsbDevice) {
        val uri = CameraDriverFactory.registerUsbDevice(device)
        val serial = resolveStableUuid(device)
        val product = runCatching { device.productName }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Canon USB"
        onUsbCameraReady(
            DiscoveredCamera(
                uuid = serial,
                ipAddress = uri,
                port = 0,
                modelName = "$product (USB)",
                ccapiBaseUrl = ""
            )
        )
    }

    private fun resolveStableUuid(device: UsbDevice): String {
        return runCatching { device.serialNumber }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "usb_${device.vendorId}_${device.productId}"
    }

    private fun isCanonOrPtpDevice(device: UsbDevice): Boolean {
        if (device.vendorId == CANON_VENDOR_ID) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return true
            }
        }
        return false
    }

    fun destroy() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }
}
