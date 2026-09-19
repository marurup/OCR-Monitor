package dk.urupit.ocrmonitor

import android.content.Context
import dk.urupit.ocrmonitor.core.CaptureSchedule

/** The handful of settings the spine needs. Profiles replace most of this later. */
class Prefs(context: Context) {

    private val store = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var captureIntervalMillis: Long
        get() = store.getLong(KEY_INTERVAL, CaptureSchedule.DEFAULT_INTERVAL_MILLIS)
        set(value) = store.edit().putLong(KEY_INTERVAL, value).apply()

    var useFrontCamera: Boolean
        get() = store.getBoolean(KEY_FRONT_CAMERA, false)
        set(value) = store.edit().putBoolean(KEY_FRONT_CAMERA, value).apply()

    private companion object {
        const val NAME = "ocr_monitor"
        const val KEY_INTERVAL = "capture_interval_millis"
        const val KEY_FRONT_CAMERA = "use_front_camera"
    }
}
