package com.canonmulticam.pro.transport

import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.StreamQuality

interface ICameraDriver {
    val ipAddress: String
    suspend fun handshake(): Result<CameraHandshakeInfo>
    suspend fun setStreamQuality(quality: StreamQuality): Result<Unit>
    suspend fun pullNextLiveFrame(slot: LatestFrameSlot): Result<Unit>
    suspend fun setRecording(record: Boolean): Result<Unit>
    suspend fun queryActualRecordingState(): Result<Boolean>
    suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit>
    suspend fun getCurrentExposure(): Result<ExposureSettings>
    suspend fun applyExposure(
        target: ExposureSettings,
        supportedApertures: List<String>,
        supportedShutters: List<String>
    ): Result<Unit>
    suspend fun getBatteryStatus(): Result<Pair<String, Boolean>>
    suspend fun getRemainingRecordMinutes(): Result<Int?>
    fun close()
}
