package dk.urupit.ocrmonitor.core

/**
 * What a camera can actually be made to do, and whether that is enough to read
 * a display unattended.
 *
 * The 2017 MediaTek reference device is expected to report [HardwareLevel.LEGACY],
 * where Camera2 is a shim over the old Camera1 API and manual control is
 * largely unavailable. Rather than guess, the app probes the device and
 * [assess] turns the raw characteristics into findings a human can act on.
 */
data class CameraCapabilities(
    val cameraId: String,
    val lensFacing: LensFacing,
    val hardwareLevel: HardwareLevel,
    val hasFlashUnit: Boolean,
    val supportsAutoExposureLock: Boolean,
    val supportsAutoWhiteBalanceLock: Boolean,
    val exposureCompensationRange: IntRange,
    val exposureCompensationStepEv: Double,
    val supportsManualSensor: Boolean,
    /** Closest focusable distance in diopters (1/m). 0.0 means a fixed-focus lens. */
    val minimumFocusDistanceDiopters: Float,
    val autoFocusModes: Set<AutoFocusMode>,
    val largestJpegSize: PixelSize?,
    val sensorOrientationDegrees: Int,
) {
    /** Closest focusable distance in metres, or null for a fixed-focus lens. */
    val minimumFocusDistanceMetres: Double?
        get() = if (minimumFocusDistanceDiopters <= 0f) null
        else 1.0 / minimumFocusDistanceDiopters.toDouble()

    val hasFixedFocusLens: Boolean
        get() = minimumFocusDistanceDiopters <= 0f &&
            autoFocusModes.none { it != AutoFocusMode.OFF }

    fun assess(): CameraAssessment {
        val findings = mutableListOf<Finding>()

        if (hasFixedFocusLens) {
            findings += Finding(
                Severity.BLOCKING,
                "Fixed-focus lens",
                "This camera cannot focus close. A fixed-focus module is typically " +
                    "sharp from roughly half a metre to infinity, so a display " +
                    "filling the frame at 15-20 cm will be blurred. Either mount " +
                    "further back and crop, or use a different phone.",
            )
        } else {
            val closest = minimumFocusDistanceMetres
            if (closest != null && closest > 0.25) {
                findings += Finding(
                    Severity.WARNING,
                    "Limited close focus",
                    "Closest focus is about ${"%.2f".format(closest)} m. Mount at " +
                        "least that far from the display.",
                )
            }
        }

        when (hardwareLevel) {
            HardwareLevel.LEGACY -> findings += Finding(
                Severity.WARNING,
                "LEGACY camera stack",
                "Camera2 is emulated over the old Camera1 API. Per-frame capture " +
                    "works, but manual exposure, focus distance and torch control " +
                    "may be ignored. Lean on median-combined bursts and fixed " +
                    "ambient lighting instead of camera control.",
            )
            HardwareLevel.EXTERNAL -> findings += Finding(
                Severity.WARNING,
                "External camera",
                "Capability reporting for external cameras is unreliable; verify " +
                    "behaviour before trusting it unattended.",
            )
            HardwareLevel.UNKNOWN -> findings += Finding(
                Severity.WARNING,
                "Unknown hardware level",
                "The device did not report a Camera2 hardware level.",
            )
            HardwareLevel.LIMITED, HardwareLevel.FULL, HardwareLevel.LEVEL_3 -> Unit
        }

        if (!supportsAutoExposureLock) {
            findings += Finding(
                Severity.WARNING,
                "No auto-exposure lock",
                "Exposure may drift between captures, changing digit contrast frame " +
                    "to frame. Adaptive thresholding and burst median absorb some of " +
                    "this; stable ambient light absorbs the rest.",
            )
        }

        if (!supportsAutoWhiteBalanceLock) {
            findings += Finding(
                Severity.INFO,
                "No white-balance lock",
                "Colour may shift between captures. Harmless for segment detection, " +
                    "which works on luminance, but it matters for LED-colour regions.",
            )
        }

        if (!hasFlashUnit) {
            findings += Finding(
                Severity.INFO,
                "No torch",
                "The scene must be lit externally. A dim meter cupboard will need a " +
                    "small permanent lamp.",
            )
        }

        if (!supportsManualSensor) {
            findings += Finding(
                Severity.INFO,
                "No manual sensor control",
                "Exposure time and ISO cannot be pinned. Expect some frame-to-frame " +
                    "variation in brightness.",
            )
        }

        if (lensFacing == LensFacing.FRONT) {
            findings += Finding(
                Severity.INFO,
                "Front-facing camera",
                "Front modules are usually lower resolution and fixed-focus. Prefer " +
                    "the rear camera where there is a choice.",
            )
        }

        largestJpegSize?.let { size ->
            if (size.megapixels < 2.0) {
                findings += Finding(
                    Severity.WARNING,
                    "Low capture resolution",
                    "Largest JPEG is ${size.width}x${size.height} " +
                        "(${"%.1f".format(size.megapixels)} MP). Small digits may not " +
                        "survive segmentation.",
                )
            }
        }

        return CameraAssessment(cameraId, findings.sortedBy { it.severity.ordinal }.reversed())
    }
}

enum class LensFacing { BACK, FRONT, EXTERNAL, UNKNOWN }

enum class HardwareLevel { LEGACY, LIMITED, FULL, LEVEL_3, EXTERNAL, UNKNOWN }

enum class AutoFocusMode { OFF, AUTO, MACRO, CONTINUOUS_VIDEO, CONTINUOUS_PICTURE, EDOF, UNKNOWN }

data class PixelSize(val width: Int, val height: Int) {
    val megapixels: Double get() = width.toDouble() * height.toDouble() / 1_000_000.0
    override fun toString(): String = "${width}x$height"
}

enum class Severity { INFO, WARNING, BLOCKING }

data class Finding(val severity: Severity, val title: String, val detail: String)

data class CameraAssessment(val cameraId: String, val findings: List<Finding>) {
    val verdict: Verdict
        get() = when {
            findings.any { it.severity == Severity.BLOCKING } -> Verdict.UNSUITABLE
            findings.any { it.severity == Severity.WARNING } -> Verdict.USABLE_WITH_CARE
            else -> Verdict.SUITABLE
        }

    enum class Verdict { SUITABLE, USABLE_WITH_CARE, UNSUITABLE }
}
