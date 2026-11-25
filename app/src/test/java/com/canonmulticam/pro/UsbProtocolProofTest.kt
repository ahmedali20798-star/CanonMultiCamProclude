package com.canonmulticam.pro

import com.canonmulticam.pro.transport.CameraDriverFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbProtocolProofTest {

    @Test
    fun `prove CameraDriverFactory safely rejects unregistered usb target without crashing`() =
        runBlocking {
            val result = CameraDriverFactory.connectAndLockProtocol("usb:///dev/bus/usb/001/002")
            assertTrue(result.isFailure)
        }
}
