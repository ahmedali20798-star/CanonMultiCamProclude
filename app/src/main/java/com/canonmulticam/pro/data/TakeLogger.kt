package com.canonmulticam.pro.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TakeRecord(
    val takeNumber: Int,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val activeCameras: List<String>,
    val slateCount: Int = 0,
    val isGoodTake: Boolean = false
)

class TakeLogger {
    private val _takes = MutableStateFlow<List<TakeRecord>>(emptyList())
    val takes: StateFlow<List<TakeRecord>> = _takes.asStateFlow()

    fun startTake(cameraNames: List<String>) {
        val nextNum = (_takes.value.lastOrNull()?.takeNumber ?: 0) + 1
        val trimmed = if (_takes.value.size >= 500) _takes.value.drop(1) else _takes.value
        _takes.value = trimmed + TakeRecord(
            takeNumber = nextNum,
            startTimeMs = System.currentTimeMillis(),
            activeCameras = cameraNames
        )
    }

    fun stopCurrentTake() {
        val current = _takes.value
        if (current.isEmpty()) return
        val last = current.last()
        if (last.endTimeMs == null) {
            _takes.value = current.dropLast(1) + last.copy(endTimeMs = System.currentTimeMillis())
        }
    }

    fun incrementSlateOnCurrentTake() {
        val current = _takes.value
        if (current.isEmpty()) return
        val last = current.last()
        _takes.value = current.dropLast(1) + last.copy(slateCount = last.slateCount + 1)
    }

    fun markCurrentTakeGood() {
        val current = _takes.value
        if (current.isEmpty()) return
        val last = current.last()
        _takes.value = current.dropLast(1) + last.copy(isGoodTake = !last.isGoodTake)
    }

    fun exportPremiereMarkersCsv(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return buildString {
            appendLine("Marker Name,Description,In,Out,Duration,Marker Type")
            _takes.value.forEach { t ->
                val inTc = fmt.format(Date(t.startTimeMs))
                val outTc = fmt.format(Date(t.endTimeMs ?: System.currentTimeMillis()))
                val status = if (t.isGoodTake) "GOOD TAKE" else "TAKE"
                val desc = "${t.activeCameras.joinToString(" + ")} | Slates: ${t.slateCount}"
                appendLine("Take ${t.takeNumber} ($status),\"$desc\",$inTc,$outTc,00:00:01,Comment")
            }
        }
    }
}
