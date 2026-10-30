package com.chronoccd.studio

import com.chronoccd.studio.data.NostalgicDigicamCatalog
import com.chronoccd.studio.data.TimestampConfig
import com.chronoccd.studio.data.TimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigicamIspPipelineTest {

    @Test
    fun catalogHasFiveDistinct2000sDigicams() {
        assertEquals(5, NostalgicDigicamCatalog.size)
        val ids = NostalgicDigicamCatalog.map { it.id }.toSet()
        assertEquals(setOf("ixus_70", "cyber_t1", "super_f30", "cool_e32", "exilim_s2"), ids)

        for (camera in NostalgicDigicamCatalog) {
            assertEquals(9, camera.ispSpec.colorMatrix3x3.size)
            assertTrue(camera.releaseYear in 2001..2008)
            assertTrue(camera.ispSpec.sensorPitchScale in 0.65f..0.95f)
            assertTrue(camera.ispSpec.jpegQuality in 60..90)
            assertTrue(camera.ispSpec.finalExportJpegQuality >= 92)
            assertTrue(camera.ispSpec.blackClipLinear in 0.03f..0.05f)
            assertTrue(camera.ispSpec.exposureBiasGain in 1.4f..1.8f)
            assertTrue(camera.ispSpec.whiteClipLinear in 0.72f..0.82f)
            assertTrue(camera.ispSpec.sigmaLumaFloor in 0.014f..0.030f)
            assertTrue(camera.ispSpec.shadowChromaSpeckle in 0.025f..0.045f)
        }
        // Verify each mode has its own distinct internal resolution, sharpening, noise floors, and JPEG quality
        assertEquals(5, NostalgicDigicamCatalog.map { it.ispSpec.sensorLongEdgePx }.toSet().size)
        assertEquals(5, NostalgicDigicamCatalog.map { it.ispSpec.usmCrispness }.toSet().size)
        assertEquals(5, NostalgicDigicamCatalog.map { it.ispSpec.jpegQuality }.toSet().size)
        assertEquals(5, NostalgicDigicamCatalog.map { it.ispSpec.sigmaLumaFloor }.toSet().size)
        assertEquals(5, NostalgicDigicamCatalog.map { it.ispSpec.shadowChromaSpeckle }.toSet().size)
    }

    @Test
    fun quartzTimestampFormatsMatchAuthentic2000sPatterns() {
        val base = TimestampConfig(
            enabled = true,
            useNostalgicYearOverride = true,
            nostalgicYear = 2006,
            customMonth = 10,
            customDay = 29,
            customHour = 17,
            customMinute = 49
        )

        assertEquals(
            "'06 10 29",
            base.copy(format = TimestampFormat.CLASSIC_JP).formattedString()
        )
        assertEquals(
            "2006.10.29",
            base.copy(format = TimestampFormat.CYBER_DOT).formattedString()
        )
        assertEquals(
            "OCT 29 2006",
            base.copy(format = TimestampFormat.US_QUARTZ).formattedString()
        )
        assertEquals(
            "29.10.2006 17:49",
            base.copy(format = TimestampFormat.FULL_DATETIME).formattedString()
        )
    }
}
