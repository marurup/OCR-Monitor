package dk.urupit.ocrmonitor.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import dk.urupit.ocrmonitor.core.AutoFocusMode
import dk.urupit.ocrmonitor.core.CameraCapabilities
import dk.urupit.ocrmonitor.core.HardwareLevel
import dk.urupit.ocrmonitor.core.LensFacing
import dk.urupit.ocrmonitor.core.PixelSize

/**
 * Reads what each camera claims it can do.
 *
 * Uses Camera2 directly rather than CameraX interop because reading
 * characteristics needs no open camera and no CAMERA permission, so the
 * report is available before the user grants anything.
 *
 * The answer that matters is the hardware level: a 2017 MediaTek device is
 * expected to report LEGACY, which decides how much the reader can rely on
 * camera control versus fixed lighting and burst median.
 */
object CameraCapabilityProbe {

    fun probeAll(context: Context): List<CameraCapabilities> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()

        return runCatching { manager.cameraIdList.toList() }
            .getOrDefault(emptyList())
            .mapNotNull { id -> runCatching { probe(manager, id) }.getOrNull() }
            // Rear cameras first: that is what a dock will use.
            .sortedBy { if (it.lensFacing == LensFacing.BACK) 0 else 1 }
    }

    private fun probe(manager: CameraManager, cameraId: String): CameraCapabilities {
        val c = manager.getCameraCharacteristics(cameraId)

        val compensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val compensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

        return CameraCapabilities(
            cameraId = cameraId,
            lensFacing = lensFacing(c),
            hardwareLevel = hardwareLevel(c),
            hasFlashUnit = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
            supportsAutoExposureLock = aeLockAvailable(c),
            supportsAutoWhiteBalanceLock = awbLockAvailable(c),
            exposureCompensationRange =
                (compensationRange?.lower ?: 0)..(compensationRange?.upper ?: 0),
            exposureCompensationStepEv = compensationStep?.toDouble() ?: 0.0,
            supportsManualSensor = hasCapability(
                c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
            ),
            minimumFocusDistanceDiopters =
                c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            autoFocusModes = autoFocusModes(c),
            largestJpegSize = largestJpegSize(c),
            sensorOrientationDegrees = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
        )
    }

    private fun lensFacing(c: CameraCharacteristics): LensFacing =
        when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
            CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
            CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
            else -> LensFacing.UNKNOWN
        }

    private fun hardwareLevel(c: CameraCharacteristics): HardwareLevel =
        when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
            // LEVEL_3 and EXTERNAL postdate API 21, so compare numerically
            // rather than referencing constants that may not exist at runtime.
            3 -> HardwareLevel.LEVEL_3
            4 -> HardwareLevel.EXTERNAL
            else -> HardwareLevel.UNKNOWN
        }

    private fun aeLockAvailable(c: CameraCharacteristics): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        } else {
            // Before API 23 the characteristic does not exist. LIMITED and
            // above are required to support AE lock, so infer it.
            hardwareLevel(c) != HardwareLevel.LEGACY
        }

    private fun awbLockAvailable(c: CameraCharacteristics): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false
        } else {
            hardwareLevel(c) != HardwareLevel.LEGACY
        }

    private fun hasCapability(c: CameraCharacteristics, capability: Int): Boolean =
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.any { it == capability } ?: false

    private fun autoFocusModes(c: CameraCharacteristics): Set<AutoFocusMode> =
        c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.map { mode ->
                when (mode) {
                    CameraMetadata.CONTROL_AF_MODE_OFF -> AutoFocusMode.OFF
                    CameraMetadata.CONTROL_AF_MODE_AUTO -> AutoFocusMode.AUTO
                    CameraMetadata.CONTROL_AF_MODE_MACRO -> AutoFocusMode.MACRO
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> AutoFocusMode.CONTINUOUS_VIDEO
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> AutoFocusMode.CONTINUOUS_PICTURE
                    CameraMetadata.CONTROL_AF_MODE_EDOF -> AutoFocusMode.EDOF
                    else -> AutoFocusMode.UNKNOWN
                }
            }?.toSet() ?: emptySet()

    private fun largestJpegSize(c: CameraCharacteristics): PixelSize? {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        return map.getOutputSizes(android.graphics.ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.let { PixelSize(it.width, it.height) }
    }
}
