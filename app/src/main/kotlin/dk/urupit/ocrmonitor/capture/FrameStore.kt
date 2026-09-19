package dk.urupit.ocrmonitor.capture

import android.content.Context
import android.util.Log
import dk.urupit.ocrmonitor.core.FrameRetention
import dk.urupit.ocrmonitor.core.RetentionPolicy
import dk.urupit.ocrmonitor.core.StoredFrame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where captured frames live.
 *
 * Deliberately in external app storage so frames can be pulled off over USB
 * without adb or root: the whole point of the spine is to collect real images
 * of the F3600 panel and the water meter to tune the reader against.
 */
class FrameStore(private val context: Context) {

    private val directory: File
        get() = (context.getExternalFilesDir(FRAMES_DIR) ?: File(context.filesDir, FRAMES_DIR))
            .also { if (!it.exists()) it.mkdirs() }

    fun newFrameFile(capturedAt: Long): File = File(directory, fileName(capturedAt))

    fun list(): List<StoredFrame> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".jpg") }
            ?.map { StoredFrame(it.name, it.lastModified(), it.length()) }
            ?.sortedBy { it.capturedAt }
            ?: emptyList()

    fun latest(): File? = list().maxByOrNull { it.capturedAt }?.let { File(directory, it.name) }

    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    /** Applies the retention policy. Returns how many files were removed. */
    fun prune(policy: RetentionPolicy, now: Long = System.currentTimeMillis()): Int {
        val doomed = FrameRetention.selectForDeletion(list(), policy, now)
        var removed = 0
        for (frame in doomed) {
            val file = File(directory, frame.name)
            if (file.delete()) {
                removed++
            } else {
                Log.w(TAG, "Could not delete ${frame.name}")
            }
        }
        return removed
    }

    private fun fileName(capturedAt: Long): String =
        "frame-${TIMESTAMP_FORMAT.format(Date(capturedAt))}.jpg"

    companion object {
        private const val TAG = "FrameStore"
        private const val FRAMES_DIR = "frames"

        // Sorts lexicographically in capture order, which makes the directory
        // readable when pulled off the phone.
        private val TIMESTAMP_FORMAT =
            SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
