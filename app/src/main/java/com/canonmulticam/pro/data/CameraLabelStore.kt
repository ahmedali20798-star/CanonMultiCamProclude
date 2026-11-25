package com.canonmulticam.pro.data

import android.content.Context

class CameraLabelStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("canon_cam_labels", Context.MODE_PRIVATE)

    fun getCustomLabel(uuid: String): String? = prefs.getString("label_$uuid", null)

    fun saveCustomLabel(uuid: String, label: String) {
        val clean = label.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove("label_$uuid").apply()
        } else {
            prefs.edit().putString("label_$uuid", clean).apply()
        }
    }
}
