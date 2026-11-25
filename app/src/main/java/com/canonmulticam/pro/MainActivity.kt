package com.canonmulticam.pro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.canonmulticam.pro.engine.MultiCamViewModel
import com.canonmulticam.pro.transport.CameraDiscoveryService
import com.canonmulticam.pro.transport.NetworkBinder
import com.canonmulticam.pro.transport.UsbCameraWatcher
import com.canonmulticam.pro.ui.MultiCamScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MultiCamViewModel by viewModels()
    private var usbWatcher: UsbCameraWatcher? = null
    private var discoveryService: CameraDiscoveryService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startNetworkDiscovery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        NetworkBinder.init(this)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CanonMultiCamPro::ContinuousMonitorLock"
        ).apply {
            setReferenceCounted(false)
        }

        usbWatcher = UsbCameraWatcher(
            context = this,
            onUsbCameraReady = { discoveredCam ->
                viewModel.onCameraDiscovered(discoveredCam)
            },
            onUsbCameraDetached = { uuid ->
                viewModel.onCameraDetachedByUuid(uuid)
            }
        ).also { it.start() }

        usbWatcher?.handleIntent(intent)
        requestRequiredNetworkPermissions()

        setContent {
            MultiCamScreen(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        discoveryService?.resume()
    }

    override fun onStop() {
        discoveryService?.pause()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        usbWatcher?.handleIntent(intent)
    }

    private fun startNetworkDiscovery() {
        if (discoveryService == null) {
            discoveryService = CameraDiscoveryService(this).also { svc ->
                svc.startDiscovery(
                    connectedCountProvider = {
                        viewModel.slotsState.value.count { it.isConnected }
                    },
                    knownAddressesProvider = { viewModel.ownedAddresses() },
                    onCameraFound = { cam ->
                        viewModel.onCameraDiscovered(cam)
                    }
                )
            }
        }
    }

    private fun requestRequiredNetworkPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startNetworkDiscovery()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        discoveryService?.destroy()
        discoveryService = null
        usbWatcher?.destroy()
        usbWatcher = null
        super.onDestroy()
    }
}
