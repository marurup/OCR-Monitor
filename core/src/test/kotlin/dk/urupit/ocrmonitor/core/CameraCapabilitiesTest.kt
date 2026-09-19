package dk.urupit.ocrmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilitiesTest {
    private fun capabilities(
        hardwareLevel: HardwareLevel = HardwareLevel.FULL,
        hasFlash: Boolean = true,
        aeLock: Boolean = true,
        awbLock: Boolean = true,
        manualSensor: Boolean = true,
        minFocusDiopters: Float = 10f,
        afModes: Set<AutoFocusMode> = setOf(AutoFocusMode.AUTO, AutoFocusMode.CONTINUOUS_PICTURE),
        jpeg: PixelSize? = PixelSize(4_160, 3_120),
        facing: LensFacing = LensFacing.BACK,
    ) = CameraCapabilities(
        cameraId = "0",
        lensFacing = facing,
        hardwareLevel = hardwareLevel,
        hasFlashUnit = hasFlash,
        supportsAutoExposureLock = aeLock,
        supportsAutoWhiteBalanceLock = awbLock,
        exposureCompensationRange = -12..12,
        exposureCompensationStepEv = 1.0 / 6.0,
        supportsManualSensor = manualSensor,
        minimumFocusDistanceDiopters = minFocusDiopters,
        autoFocusModes = afModes,
        largestJpegSize = jpeg,
        sensorOrientationDegrees = 90,
    )

    @Test
    fun `a capable rear camera is suitable`() {
        val assessment = capabilities().assess()
        assertEquals(CameraAssessment.Verdict.SUITABLE, assessment.verdict)
        assertTrue(assessment.findings.isEmpty())
    }

    @Test
    fun `fixed focus lens is blocking`() {
        // 0 diopters with no real AF mode means the lens is fixed at roughly
        // infinity, so it cannot resolve a display filling the frame close up.
        val assessment = capabilities(
            minFocusDiopters = 0f,
            afModes = setOf(AutoFocusMode.OFF),
        ).assess()
        assertEquals(CameraAssessment.Verdict.UNSUITABLE, assessment.verdict)
        assertTrue(assessment.findings.any { it.title == "Fixed-focus lens" })
    }

    @Test
    fun `legacy stack is usable with care, not blocking`() {
        val assessment = capabilities(hardwareLevel = HardwareLevel.LEGACY).assess()
        assertEquals(CameraAssessment.Verdict.USABLE_WITH_CARE, assessment.verdict)
        assertTrue(assessment.findings.any { it.title == "LEGACY camera stack" })
    }

    @Test
    fun `expected profile of the reference dock phone`() {
        // Ulefone Power 2 (2017, MT6750T): LEGACY stack with no manual control
        // is the anticipated worst case. It should still be workable, because
        // burst median and stable lighting cover what the camera cannot.
        val assessment = capabilities(
            hardwareLevel = HardwareLevel.LEGACY,
            aeLock = false,
            awbLock = false,
            manualSensor = false,
        ).assess()
        assertEquals(CameraAssessment.Verdict.USABLE_WITH_CARE, assessment.verdict)
    }

    @Test
    fun `missing torch is only informational`() {
        val assessment = capabilities(hasFlash = false).assess()
        assertEquals(CameraAssessment.Verdict.SUITABLE, assessment.verdict)
        assertTrue(assessment.findings.any { it.title == "No torch" })
    }

    @Test
    fun `low resolution is a warning`() {
        val assessment = capabilities(jpeg = PixelSize(640, 480)).assess()
        assertEquals(CameraAssessment.Verdict.USABLE_WITH_CARE, assessment.verdict)
    }

    @Test
    fun `distant minimum focus warns about mounting distance`() {
        // 2 diopters is a 50 cm closest focus.
        val assessment = capabilities(minFocusDiopters = 2f).assess()
        assertTrue(assessment.findings.any { it.title == "Limited close focus" })
    }

    @Test
    fun `converts diopters to metres`() {
        assertEquals(0.1, capabilities(minFocusDiopters = 10f).minimumFocusDistanceMetres!!, 1e-9)
        assertEquals(null, capabilities(minFocusDiopters = 0f).minimumFocusDistanceMetres)
    }

    @Test
    fun `findings are ordered most severe first`() {
        val assessment = capabilities(
            hardwareLevel = HardwareLevel.LEGACY,
            hasFlash = false,
            minFocusDiopters = 0f,
            afModes = setOf(AutoFocusMode.OFF),
        ).assess()
        val severities = assessment.findings.map { it.severity.ordinal }
        assertEquals(severities.sortedDescending(), severities)
    }
}
