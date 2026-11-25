package com.chronoccd.studio.data

import androidx.compose.ui.graphics.Color
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TimestampFormat(val label: String, val example: String) {
    CLASSIC_JP("'YY MM DD", "'07 10 29"),
    CYBER_DOT("YYYY.MM.DD", "2007.10.29"),
    US_QUARTZ("MMM DD YYYY", "OCT 29 2007"),
    FULL_DATETIME("DD.MM.YYYY HH:mm", "29.10.2007 17:49")
}

enum class TimestampLedColor(
    val label: String,
    val coreHex: Int,
    val innerGlowHex: Int,
    val outerBleedHex: Int
) {
    RED_ORANGE(
        label = "Red-Orange LED",
        coreHex = 0xFFFF621A.toInt(),
        innerGlowHex = 0xFFE63900.toInt(),
        outerBleedHex = 0xFFB81D00.toInt()
    ),
    QUARTZ_AMBER(
        label = "Quartz Amber",
        coreHex = 0xFFFFAE00.toInt(),
        innerGlowHex = 0xFFE67E00.toInt(),
        outerBleedHex = 0xFFB35300.toInt()
    ),
    CYBER_YELLOW(
        label = "Cyber Yellow",
        coreHex = 0xFFFFDF00.toInt(),
        innerGlowHex = 0xFFE69B00.toInt(),
        outerBleedHex = 0xFFB36B00.toInt()
    ),
    MATRIX_GREEN(
        label = "LCD Green",
        coreHex = 0xFF4CE639.toInt(),
        innerGlowHex = 0xFF19B326.toInt(),
        outerBleedHex = 0xFF0B7014.toInt()
    )
}

enum class TimestampPosition(val label: String) {
    BOTTOM_RIGHT("Bottom Right"),
    BOTTOM_LEFT("Bottom Left")
}

data class TimestampConfig(
    val enabled: Boolean = true,
    val format: TimestampFormat = TimestampFormat.CLASSIC_JP,
    val ledColor: TimestampLedColor = TimestampLedColor.RED_ORANGE,
    val position: TimestampPosition = TimestampPosition.BOTTOM_RIGHT,
    val useNostalgicYearOverride: Boolean = true,
    val nostalgicYear: Int = 2007,
    val customMonth: Int? = null,
    val customDay: Int? = null,
    val customHour: Int? = null,
    val customMinute: Int? = null,
    val glowIntensity: Float = 0.55f,
    val scaleMultiplier: Float = 0.92f
) {
    fun formattedString(now: LocalDateTime = LocalDateTime.now()): String {
        val year = if (useNostalgicYearOverride) nostalgicYear else now.year
        val month = (customMonth ?: now.monthValue).coerceIn(1, 12)
        val maxDayInMonth = YearMonth.of(year, month).lengthOfMonth()
        val safeDay = (customDay ?: now.dayOfMonth).coerceIn(1, maxDayInMonth)
        val hour = (customHour ?: now.hour).coerceIn(0, 23)
        val minute = (customMinute ?: now.minute).coerceIn(0, 59)

        val dt = LocalDateTime.of(year, month, safeDay, hour, minute)

        return when (format) {
            TimestampFormat.CLASSIC_JP -> {
                val yy = (year % 100).toString().padStart(2, '0')
                val mm = month.toString().padStart(2, '0')
                val dd = safeDay.toString().padStart(2, '0')
                "'$yy $mm $dd"
            }
            TimestampFormat.CYBER_DOT -> {
                dt.format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US))
            }
            TimestampFormat.US_QUARTZ -> {
                dt.format(DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.US)).uppercase(Locale.US)
            }
            TimestampFormat.FULL_DATETIME -> {
                dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.US))
            }
        }
    }
}

/**
 * Per-Mode 2000s Digicam Hardware Specification.
 *
 * Each camera look has its own distinct internal working resolution (from VGA 640x480 up to 4MP
 * 2048x1536), optical low-pass filter strength, noise level, USM sharpening strength/halo limit,
 * and internal JPEG compression quality (58..86), with the entire chain (including date stamp
 * and JPEG encode/decode) executed at that internal size before upscaling to 1080p at the very end.
 */
data class DigicamIspSpec(
    val sensorLongEdgePx: Int = 1600,           // Internal resolution long edge: 640 (VGA), 960 (0.8MP), 1280 (1.3MP), 1600 (2MP), 2048 (4MP)
    val sensorPitchScale: Float,                // 0.65..0.95
    val olpfLowPassStrength: Float = 0.65f,     // Gaussian low-pass sigma before downsampling to internal size
    val cornerSoftness: Float = 0.24f,          // STEP 6: Mild corner softness in all modes (0.18..0.30)
    val caKr: Float = 0.00120f,                 // STEP 6: Lateral CA R scale = 1 + k_r*r^2 (k_r > 0, 1-2 px corner shift)
    val caKb: Float = 0.00130f,                 // STEP 6: Lateral CA B scale = 1 - k_b*r^2 (k_b > 0, 1-2 px corner shift)
    val edgeCaFringingPx: Float = 1.25f,        // STEP 6: Max corner shift in internal resolution pixels (1.0..2.0 px)
    val barrelDistortion: Float = 0.028f,       // STEP 6: Consistent barrel distortion in all modes
    val lensVignette: Float = 0.06f,            // STEP 6: Halved vignette strength (0.055..0.080)
    val veilingFlare: Float = 0.04f,            // 0.02..0.08 (subtle veiling haze from bright light sources)
    val pointLightBloomThreshold: Float = 0.82f,// STEP 6: Point-light bloom threshold luma > 0.82
    val pointLightBloomSigmaPx: Float = 3.0f,   // STEP 6: Point-light bloom Gaussian blur sigma ~3 px
    val blueVioletFringeStrength: Float = 0.15f,// STEP 6: Blue-violet fringing on dark side of edges (0.10..0.20)
    val blackClipLinear: Float = 0.035f,        // STEP 3: Linear black clip (0.03..0.05) applied first
    val exposureBiasGain: Float = 1.50f,        // STEP 3: Pre-clip exposure gain (1.4..1.8x per mode)
    val whiteClipLinear: Float = 0.78f,         // STEP 3: Linear white clip (0.72..0.82 per mode), no top shoulder
    val wbGainR: Float,                         // STEP 3: Per-channel WB gains applied BEFORE clamping to 1.0
    val wbGainG: Float,
    val wbGainB: Float,
    val colorMatrix3x3: FloatArray,             // Distinct per-brand 3x3 primary matrix
    val shadowCyanGreenTint: Float = 0.0f,      // -0.06..+0.08 (cyan/green shadow channel crosstalk)
    val highlightMagentaTilt: Float = 0.0f,     // 0.00..0.07 (slight magenta crosstalk near highlight clip)
    val saturationBoost: Float,                 // 1.08..1.32 (pushed reds, blues, greens)
    val shadowRollOff: Float,                   // 0.26..0.44 (crushed blacks rather than HDR shadow recovery)
    val highlightShoulder: Float,               // 0.72..0.82 (matches whiteClipLinear)
    val midtoneGamma: Float,                    // 0.82..0.92
    val toneContrast: Float,                    // 0.28..0.46 (punchy SOOC S-curve, maps 1.0 -> 1.0)
    val highlightBloomAmount: Float,            // 0.07..0.16 (CCD highlight bloom & faint vertical smear)
    val verticalSmearStrength: Float = 0.05f,   // 0.03..0.10 (CCD vertical charge bleed around intense lights)
    val fineLumaGrain: Float,                   // STEP 4: Shot noise ~ sqrt(luma) before the tone curve
    val sigmaLumaFloor: Float = 0.018f,         // STEP 4: Constant luma read noise floor (0.014 highest-res .. 0.030 lowest-res) after black crush
    val shadowChromaSpeckle: Float,             // STEP 4: 1/4-res bilinearly upsampled Cb/Cr blotch sigma (0.025 highest-res .. 0.045 lowest-res)
    val deClaritySoftness: Float,               // STEP 4: Smeary in-camera NR applied after the noise step
    val usmRadiusPx: Float = 1.25f,             // STEP 5: Luma USM radius 1.0..1.5 px at internal resolution
    val usmCrispness: Float,                    // STEP 5: Luma USM strength (halo amount reduced by ~40% in every mode)
    val usmHaloLimit: Float = 0.054f,           // STEP 5: Per-mode edge halo overshoot cap (reduced by ~40% in every mode)
    val jpegQuality: Int,                       // STEP 2: 60..86 (internal JPEG encode/decode quality)
    val finalExportJpegQuality: Int = 95        // STEP 2: Final saved file quality Q >= 92
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DigicamIspSpec) return false

        if (sensorLongEdgePx != other.sensorLongEdgePx) return false
        if (sensorPitchScale != other.sensorPitchScale) return false
        if (olpfLowPassStrength != other.olpfLowPassStrength) return false
        if (cornerSoftness != other.cornerSoftness) return false
        if (caKr != other.caKr) return false
        if (caKb != other.caKb) return false
        if (edgeCaFringingPx != other.edgeCaFringingPx) return false
        if (barrelDistortion != other.barrelDistortion) return false
        if (lensVignette != other.lensVignette) return false
        if (veilingFlare != other.veilingFlare) return false
        if (pointLightBloomThreshold != other.pointLightBloomThreshold) return false
        if (pointLightBloomSigmaPx != other.pointLightBloomSigmaPx) return false
        if (blueVioletFringeStrength != other.blueVioletFringeStrength) return false
        if (blackClipLinear != other.blackClipLinear) return false
        if (exposureBiasGain != other.exposureBiasGain) return false
        if (whiteClipLinear != other.whiteClipLinear) return false
        if (wbGainR != other.wbGainR) return false
        if (wbGainG != other.wbGainG) return false
        if (wbGainB != other.wbGainB) return false
        if (!colorMatrix3x3.contentEquals(other.colorMatrix3x3)) return false
        if (shadowCyanGreenTint != other.shadowCyanGreenTint) return false
        if (highlightMagentaTilt != other.highlightMagentaTilt) return false
        if (saturationBoost != other.saturationBoost) return false
        if (shadowRollOff != other.shadowRollOff) return false
        if (highlightShoulder != other.highlightShoulder) return false
        if (midtoneGamma != other.midtoneGamma) return false
        if (toneContrast != other.toneContrast) return false
        if (highlightBloomAmount != other.highlightBloomAmount) return false
        if (verticalSmearStrength != other.verticalSmearStrength) return false
        if (fineLumaGrain != other.fineLumaGrain) return false
        if (sigmaLumaFloor != other.sigmaLumaFloor) return false
        if (shadowChromaSpeckle != other.shadowChromaSpeckle) return false
        if (deClaritySoftness != other.deClaritySoftness) return false
        if (usmRadiusPx != other.usmRadiusPx) return false
        if (usmCrispness != other.usmCrispness) return false
        if (usmHaloLimit != other.usmHaloLimit) return false
        if (jpegQuality != other.jpegQuality) return false
        if (finalExportJpegQuality != other.finalExportJpegQuality) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sensorLongEdgePx
        result = 31 * result + sensorPitchScale.hashCode()
        result = 31 * result + olpfLowPassStrength.hashCode()
        result = 31 * result + cornerSoftness.hashCode()
        result = 31 * result + caKr.hashCode()
        result = 31 * result + caKb.hashCode()
        result = 31 * result + edgeCaFringingPx.hashCode()
        result = 31 * result + barrelDistortion.hashCode()
        result = 31 * result + lensVignette.hashCode()
        result = 31 * result + veilingFlare.hashCode()
        result = 31 * result + pointLightBloomThreshold.hashCode()
        result = 31 * result + pointLightBloomSigmaPx.hashCode()
        result = 31 * result + blueVioletFringeStrength.hashCode()
        result = 31 * result + blackClipLinear.hashCode()
        result = 31 * result + exposureBiasGain.hashCode()
        result = 31 * result + whiteClipLinear.hashCode()
        result = 31 * result + wbGainR.hashCode()
        result = 31 * result + wbGainG.hashCode()
        result = 31 * result + wbGainB.hashCode()
        result = 31 * result + colorMatrix3x3.contentHashCode()
        result = 31 * result + shadowCyanGreenTint.hashCode()
        result = 31 * result + highlightMagentaTilt.hashCode()
        result = 31 * result + saturationBoost.hashCode()
        result = 31 * result + shadowRollOff.hashCode()
        result = 31 * result + highlightShoulder.hashCode()
        result = 31 * result + midtoneGamma.hashCode()
        result = 31 * result + toneContrast.hashCode()
        result = 31 * result + highlightBloomAmount.hashCode()
        result = 31 * result + verticalSmearStrength.hashCode()
        result = 31 * result + fineLumaGrain.hashCode()
        result = 31 * result + sigmaLumaFloor.hashCode()
        result = 31 * result + shadowChromaSpeckle.hashCode()
        result = 31 * result + deClaritySoftness.hashCode()
        result = 31 * result + usmRadiusPx.hashCode()
        result = 31 * result + usmCrispness.hashCode()
        result = 31 * result + usmHaloLimit.hashCode()
        result = 31 * result + jpegQuality
        result = 31 * result + finalExportJpegQuality
        return result
    }
}

data class IspUserTuning(
    val intensity: Float = 1.0f,
    val sensorPitchMultiplier: Float = 1.0f,
    val xenonFlashEnabled: Boolean = false,
    val xenonFlashPower: Float = 0.80f,
    val bloomAndSmearMultiplier: Float = 1.0f,
    val ccdNoiseMultiplier: Float = 1.0f,
    val edgeHaloUsmMultiplier: Float = 1.0f,
    val chromaticAberrationMultiplier: Float = 1.0f,
    val jpegCrunchMultiplier: Float = 1.0f,
    val exportNativeInternalSize: Boolean = false, // STEP 2: Option to export at native internal size
    val useFullSensorResolution: Boolean = false  // Toggle between predetermined mode res and full sensor res
)

data class DigicamPersona(
    val id: String,
    val shortCode: String,
    val displayName: String,
    val inspiredByModel: String,
    val releaseYear: Int,
    val sensorBadge: String,
    val ispChipName: String,
    val megapixelLabel: String,
    val isoDefaultLabel: String,
    val tagline: String,
    val characteristicSummary: String,
    val accentColor: Color,
    val defaultTimestampFormat: TimestampFormat,
    val defaultTimestampColor: TimestampLedColor,
    val ispSpec: DigicamIspSpec
)

val NostalgicDigicamCatalog: List<DigicamPersona> = listOf(
    // 1. Canon IXUS 70 (2007) — 2 MP (1600x1200) internal size, balanced DIGIC III USM, moderate grain, JPEG Q79
    DigicamPersona(
        id = "ixus_70",
        shortCode = "IXUS 70",
        displayName = "Canon IXUS 70",
        inspiredByModel = "Canon PowerShot SD1000 / IXUS 70",
        releaseYear = 2007,
        sensorBadge = "2MP (1600×1200) CCD",
        ispChipName = "DIGIC III",
        megapixelLabel = "2 MP",
        isoDefaultLabel = "ISO 100",
        tagline = "2MP (1600×1200) • Natural warm skin, balanced USM & Q79 JPEG",
        characteristicSummary = "2MP internal working resolution (1600×1200): flattering warm-peach skin tones, medium-crisp DIGIC III sharpening, moderate grain, and Q79 JPEG compression.",
        accentColor = Color(0xFFE5534B),
        defaultTimestampFormat = TimestampFormat.CLASSIC_JP,
        defaultTimestampColor = TimestampLedColor.RED_ORANGE,
        ispSpec = DigicamIspSpec(
            sensorLongEdgePx = 1600,
            sensorPitchScale = 0.85f,
            olpfLowPassStrength = 0.55f,
            cornerSoftness = 0.21f,
            caKr = 0.00120f,
            caKb = 0.00130f,
            edgeCaFringingPx = 1.25f,
            barrelDistortion = 0.028f,
            lensVignette = 0.060f,
            veilingFlare = 0.03f,
            pointLightBloomThreshold = 0.82f,
            pointLightBloomSigmaPx = 3.0f,
            blueVioletFringeStrength = 0.125f,
            blackClipLinear = 0.035f,
            exposureBiasGain = 1.50f,
            whiteClipLinear = 0.745f,
            wbGainR = 1.075f,
            wbGainG = 1.000f,
            wbGainB = 0.925f,
            colorMatrix3x3 = floatArrayOf(
                 1.16f, -0.10f, -0.06f,
                -0.04f,  1.10f, -0.06f,
                -0.02f, -0.08f,  1.10f
            ),
            shadowCyanGreenTint = 0.01f,
            highlightMagentaTilt = 0.02f,
            saturationBoost = 0.887f,
            shadowRollOff = 0.32f,
            highlightShoulder = 0.745f,
            midtoneGamma = 0.86f,
            toneContrast = 0.275f,
            highlightBloomAmount = 0.08f,
            verticalSmearStrength = 0.04f,
            fineLumaGrain = 0.013f,
            sigmaLumaFloor = 0.018f,
            shadowChromaSpeckle = 0.030f,
            deClaritySoftness = 0.34f,
            usmRadiusPx = 1.12f,
            usmCrispness = 0.43f,
            usmHaloLimit = 0.054f,
            jpegQuality = 79
        )
    ),

    // 2. Sony Cyber-shot (2002/2005) — VGA (640x480) internal size, heavy USM halos, high noise, crunchy Q62 JPEG
    DigicamPersona(
        id = "cyber_t1",
        shortCode = "CYBER-T1",
        displayName = "Sony Cyber-shot",
        inspiredByModel = "Sony Cyber-shot DSC-P1 / T1 (VGA Mode)",
        releaseYear = 2003,
        sensorBadge = "VGA (640×480) CCD",
        ispChipName = "Real Imaging Processor",
        megapixelLabel = "VGA",
        isoDefaultLabel = "ISO 160",
        tagline = "VGA (640×480) • Cool Y2K contrast, heavy USM halos & crunchy Q62 JPEG",
        characteristicSummary = "True 640×480 VGA internal pipeline upscaled to 1080p: cool cobalt-cyan tones, aggressive edge-halo sharpening, high sensor noise, and chunky Q62 8×8 JPEG blocks.",
        accentColor = Color(0xFF3D64E6),
        defaultTimestampFormat = TimestampFormat.CYBER_DOT,
        defaultTimestampColor = TimestampLedColor.RED_ORANGE,
        ispSpec = DigicamIspSpec(
            sensorLongEdgePx = 640,
            sensorPitchScale = 0.72f,
            olpfLowPassStrength = 0.85f,
            cornerSoftness = 0.30f,
            caKr = 0.00475f,
            caKb = 0.00500f,
            edgeCaFringingPx = 2.00f,
            barrelDistortion = 0.028f,
            lensVignette = 0.075f,
            veilingFlare = 0.04f,
            pointLightBloomThreshold = 0.82f,
            pointLightBloomSigmaPx = 3.0f,
            blueVioletFringeStrength = 0.200f,
            blackClipLinear = 0.050f,
            exposureBiasGain = 1.80f,
            whiteClipLinear = 0.82f,
            wbGainR = 0.920f,
            wbGainG = 1.015f,
            wbGainB = 1.125f,
            colorMatrix3x3 = floatArrayOf(
                 1.14f, -0.09f, -0.05f,
                -0.05f,  1.12f, -0.07f,
                -0.06f, -0.10f,  1.16f
            ),
            shadowCyanGreenTint = 0.055f,
            highlightMagentaTilt = 0.035f,
            saturationBoost = 0.949f,
            shadowRollOff = 0.40f,
            highlightShoulder = 0.82f,
            midtoneGamma = 0.82f,
            toneContrast = 0.356f,
            highlightBloomAmount = 0.11f,
            verticalSmearStrength = 0.07f,
            fineLumaGrain = 0.022f,
            sigmaLumaFloor = 0.030f,
            shadowChromaSpeckle = 0.045f,
            deClaritySoftness = 0.30f,
            usmRadiusPx = 1.50f,
            usmCrispness = 0.71f,
            usmHaloLimit = 0.084f,
            jpegQuality = 62
        )
    ),

    // 3. Fujifilm FinePix F30 (2006) — 4 MP (2048x1536) internal size, low noise, soft gentle USM, clean Q86 JPEG
    DigicamPersona(
        id = "super_f30",
        shortCode = "SUPER-F30",
        displayName = "Fujifilm FinePix F30",
        inspiredByModel = "Fujifilm FinePix F30 (Super CCD HR)",
        releaseYear = 2006,
        sensorBadge = "4MP (2048×1536) Super CCD",
        ispChipName = "RP Processor II",
        megapixelLabel = "4 MP",
        isoDefaultLabel = "ISO 200",
        tagline = "4MP (2048×1536) • Lush Fujichrome greens, low noise & smooth Q86 JPEG",
        characteristicSummary = "High 4MP (2048×1536) Super CCD internal pipeline: vibrant Fujichrome emerald greens, very low noise, gentle halo-free sharpening, and clean Q86 JPEG encoding.",
        accentColor = Color(0xFF1E8E5A),
        defaultTimestampFormat = TimestampFormat.CLASSIC_JP,
        defaultTimestampColor = TimestampLedColor.QUARTZ_AMBER,
        ispSpec = DigicamIspSpec(
            sensorLongEdgePx = 2048,
            sensorPitchScale = 0.90f,
            olpfLowPassStrength = 0.40f,
            cornerSoftness = 0.18f,
            caKr = 0.00078f,
            caKb = 0.00084f,
            edgeCaFringingPx = 1.00f,
            barrelDistortion = 0.028f,
            lensVignette = 0.055f,
            veilingFlare = 0.03f,
            pointLightBloomThreshold = 0.82f,
            pointLightBloomSigmaPx = 3.0f,
            blueVioletFringeStrength = 0.100f,
            blackClipLinear = 0.030f,
            exposureBiasGain = 1.40f,
            whiteClipLinear = 0.72f,
            wbGainR = 0.955f,
            wbGainG = 1.065f,
            wbGainB = 1.025f,
            colorMatrix3x3 = floatArrayOf(
                 1.12f, -0.08f, -0.04f,
                -0.08f,  1.22f, -0.14f,
                -0.04f, -0.10f,  1.14f
            ),
            shadowCyanGreenTint = 0.065f,
            highlightMagentaTilt = 0.01f,
            saturationBoost = 0.964f,
            shadowRollOff = 0.28f,
            highlightShoulder = 0.72f,
            midtoneGamma = 0.91f,
            toneContrast = 0.259f,
            highlightBloomAmount = 0.07f,
            verticalSmearStrength = 0.03f,
            fineLumaGrain = 0.010f,
            sigmaLumaFloor = 0.014f,
            shadowChromaSpeckle = 0.025f,
            deClaritySoftness = 0.40f,
            usmRadiusPx = 1.00f,
            usmCrispness = 0.22f,
            usmHaloLimit = 0.030f,
            jpegQuality = 86
        )
    ),

    // 4. Nikon Coolpix 4300 (2003) — 1.3 MP (1280x960) internal size, smeary NR, soft lens, medium-low USM, Q73 JPEG
    DigicamPersona(
        id = "cool_e32",
        shortCode = "COOL-E32",
        displayName = "Nikon Coolpix 4300",
        inspiredByModel = "Nikon Coolpix 4300 / E3200",
        releaseYear = 2003,
        sensorBadge = "1.3MP (1280×960) CCD",
        ispChipName = "Early Nikon ASIC",
        megapixelLabel = "1.3 MP",
        isoDefaultLabel = "ISO 100",
        tagline = "1.3MP (1280×960) • Warm quirky color, smeary NR, soft corners & Q73 JPEG",
        characteristicSummary = "1.3MP (1280×960) internal pipeline: warm ochre-olive Nikon color science, heavy watercolor NR smearing, mushy lens corners, soft sharpening, and Q73 JPEG.",
        accentColor = Color(0xFFE68A00),
        defaultTimestampFormat = TimestampFormat.US_QUARTZ,
        defaultTimestampColor = TimestampLedColor.QUARTZ_AMBER,
        ispSpec = DigicamIspSpec(
            sensorLongEdgePx = 1280,
            sensorPitchScale = 0.78f,
            olpfLowPassStrength = 0.70f,
            cornerSoftness = 0.24f,
            caKr = 0.00181f,
            caKb = 0.00194f,
            edgeCaFringingPx = 1.50f,
            barrelDistortion = 0.028f,
            lensVignette = 0.080f,
            veilingFlare = 0.05f,
            pointLightBloomThreshold = 0.82f,
            pointLightBloomSigmaPx = 3.0f,
            blueVioletFringeStrength = 0.150f,
            blackClipLinear = 0.040f,
            exposureBiasGain = 1.60f,
            whiteClipLinear = 0.77f,
            wbGainR = 1.105f,
            wbGainG = 1.035f,
            wbGainB = 0.855f,
            colorMatrix3x3 = floatArrayOf(
                 1.10f,  0.04f, -0.14f,
                 0.03f,  1.06f, -0.09f,
                -0.04f, -0.08f,  1.12f
            ),
            shadowCyanGreenTint = -0.04f,
            highlightMagentaTilt = 0.055f,
            saturationBoost = 0.842f,
            shadowRollOff = 0.36f,
            highlightShoulder = 0.77f,
            midtoneGamma = 0.84f,
            toneContrast = 0.292f,
            highlightBloomAmount = 0.10f,
            verticalSmearStrength = 0.06f,
            fineLumaGrain = 0.016f,
            sigmaLumaFloor = 0.022f,
            shadowChromaSpeckle = 0.035f,
            deClaritySoftness = 0.58f,
            usmRadiusPx = 1.25f,
            usmCrispness = 0.26f,
            usmHaloLimit = 0.036f,
            jpegQuality = 73
        )
    ),

    // 5. Kodak EasyShare C643 (2005) — 0.8 MP SVGA (960x720) internal size, golden warm, strong USM, gritty Q66 JPEG
    DigicamPersona(
        id = "exilim_s2",
        shortCode = "KODAK-C6",
        displayName = "Kodak EasyShare",
        inspiredByModel = "Kodak EasyShare C643 / CX4230",
        releaseYear = 2004,
        sensorBadge = "0.8MP (960×720) CCD",
        ispChipName = "Kodak Color Science",
        megapixelLabel = "0.8 MP",
        isoDefaultLabel = "ISO 200",
        tagline = "0.8MP (960×720) • Golden-warm saturated color, strong USM & Q66 JPEG",
        characteristicSummary = "0.8MP (960×720) internal pipeline: golden-amber Kodak Color Science, pushed reds/yellows, early highlight clip, strong consumer sharpening, and crunchy Q66 JPEG.",
        accentColor = Color(0xFFF5A623),
        defaultTimestampFormat = TimestampFormat.FULL_DATETIME,
        defaultTimestampColor = TimestampLedColor.RED_ORANGE,
        ispSpec = DigicamIspSpec(
            sensorLongEdgePx = 960,
            sensorPitchScale = 0.75f,
            olpfLowPassStrength = 0.78f,
            cornerSoftness = 0.27f,
            caKr = 0.00283f,
            caKb = 0.00300f,
            edgeCaFringingPx = 1.75f,
            barrelDistortion = 0.028f,
            lensVignette = 0.080f,
            veilingFlare = 0.04f,
            pointLightBloomThreshold = 0.82f,
            pointLightBloomSigmaPx = 3.0f,
            blueVioletFringeStrength = 0.175f,
            blackClipLinear = 0.045f,
            exposureBiasGain = 1.70f,
            whiteClipLinear = 0.795f,
            wbGainR = 1.145f,
            wbGainG = 0.995f,
            wbGainB = 0.865f,
            colorMatrix3x3 = floatArrayOf(
                 1.22f, -0.08f, -0.14f,
                 0.02f,  1.12f, -0.14f,
                -0.06f, -0.12f,  1.18f
            ),
            shadowCyanGreenTint = -0.03f,
            highlightMagentaTilt = 0.04f,
            saturationBoost = 0.995f,
            shadowRollOff = 0.42f,
            highlightShoulder = 0.795f,
            midtoneGamma = 0.82f,
            toneContrast = 0.365f,
            highlightBloomAmount = 0.12f,
            verticalSmearStrength = 0.08f,
            fineLumaGrain = 0.019f,
            sigmaLumaFloor = 0.026f,
            shadowChromaSpeckle = 0.040f,
            deClaritySoftness = 0.48f,
            usmRadiusPx = 1.38f,
            usmCrispness = 0.56f,
            usmHaloLimit = 0.072f,
            jpegQuality = 66
        )
    )
)

data class CapturedPhotoItem(
    val id: String,
    val filePath: String,
    val personaId: String,
    val personaShortCode: String,
    val personaDisplayName: String,
    val timestampImprinted: String?,
    val capturedAtFormatted: String,
    val resolutionLabel: String,
    val jpegQuality: Int,
    val flashUsed: Boolean,
    val processingTimeMs: Long
)
