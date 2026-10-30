package com.chronoccd.studio.isp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.IspUserTuning
import com.chronoccd.studio.data.TimestampConfig
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class IspPipelineTelemetry(
    val effectiveSensorWidth: Int,
    val effectiveSensorHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val clippedHighlightFraction: Float,
    val peakVerticalSmear: Float,
    val effectiveJpegQuality: Int,
    val compressedSizeKb: Int,
    val processingTimeMs: Long
)

data class IspProcessedResult(
    val bitmap: Bitmap,
    val telemetry: IspPipelineTelemetry,
    val jpegBytes: ByteArray? = null
)

/**
 * Hardware-Ordered 2000s Compact Digital Camera (CCD + ASIC ISP) Pipeline.
 *
 * Implements the exact per-mode resolution & artifact architecture:
 * 1. Capture high-res still -> Optical Low-Pass Filter (OLPF) -> Downsample to per-mode internal size
 *    (VGA 640x480, 0.8MP 960x720, 1.3MP 1280x960, 2MP 1600x1200, or 4MP 2048x1536).
 * 2. Run the ENTIRE chain at that internal size:
 *    - Linear space conversion
 *    - Compact lens physics (barrel distortion, mushy corners, edge-gated purple/green CA, mild vignette)
 *    - Light-starved 8-10 stop CCD dynamic range, early highlight clip, bloom & vertical smear
 *    - Signal-dependent linear noise (20%) + AWB + per-camera 3x3 matrix + channel crosstalk
 *    - Punchy SOOC S-curve tone mapping
 *    - Smeary in-camera noise reduction (low-contrast watercolor smoothing)
 *    - Per-mode Unsharp Mask sharpening with visible edge halos
 *    - 4:2:0 chroma subsampling + crisp post-NR digital sensor grain
 *    - Classic 7-segment quartz date stamp (imprinted at internal sensor size!)
 *    - Actual JPEG encode/decode round-trip at per-mode quality (58..86) at internal sensor size!
 * 3. Upscale to 1080p (1440x1080 / 1080x1440) with bilinear filtering ONLY at the very end.
 */
object DigicamIspPipeline {

    private val SRGB_TO_LINEAR = FloatArray(256) { i ->
        val s = i / 255f
        if (s <= 0.04045f) {
            s / 12.92f
        } else {
            ((s + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private val LINEAR_IDENTITY = FloatArray(256) { i ->
        i / 255f
    }

    private val ispLock = Any()

    fun processBitmap(
        source: Bitmap,
        persona: DigicamPersona,
        tuning: IspUserTuning,
        timestampConfig: TimestampConfig,
        isPreviewMode: Boolean = false,
        linearTonemapActive: Boolean = false
    ): IspProcessedResult = synchronized(ispLock) {
        val startNs = System.nanoTime()
        val spec = persona.ispSpec
        val strength = tuning.intensity.coerceIn(0f, 1.25f)

        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)

        // Step 1: Determine Per-Mode Internal Sensor Resolution (VGA 640, 0.8MP 960, 1.3MP 1280, 2MP 1600, 4MP 2048)
        val internalLongEdge = (spec.sensorLongEdgePx * tuning.sensorPitchMultiplier)
            .roundToInt()
            .coerceIn(480, 2048)
        val internalScale = internalLongEdge.toFloat() / max(srcW, srcH).toFloat()
        val sensorW = (((srcW * internalScale).roundToInt().coerceAtLeast(64)) / 2) * 2
        val sensorH = (((srcH * internalScale).roundToInt().coerceAtLeast(64)) / 2) * 2

        // Multi-step pre-downsample + Optical Low-Pass Filter (OLPF) so high-res phone detail is low-passed before hitting the CCD grid
        val preW = min(srcW, sensorW * 2).coerceAtLeast(sensorW)
        val preH = min(srcH, sensorH * 2).coerceAtLeast(sensorH)
        val preBitmap = if (preW < srcW || preH < srcH) {
            Bitmap.createScaledBitmap(source, preW, preH, true)
        } else {
            source
        }
        val sensorBitmap = Bitmap.createScaledBitmap(preBitmap, sensorW, sensorH, true)
        if (preBitmap !== source && preBitmap !== sensorBitmap) {
            preBitmap.recycle()
        }

        val numPixels = sensorW * sensorH
        val inPixels = IntArray(numPixels)
        sensorBitmap.getPixels(inPixels, 0, sensorW, 0, 0, sensorW, sensorH)
        if (sensorBitmap !== source) {
            sensorBitmap.recycle()
        }

        // Decode input to linear light (sRGB inverse unless a linear tonemap is active)
        val inputToLinearLut = if (linearTonemapActive) LINEAR_IDENTITY else SRGB_TO_LINEAR
        val rLin = FloatArray(numPixels)
        val gLin = FloatArray(numPixels)
        val bLin = FloatArray(numPixels)
        val blurTmp = FloatArray(numPixels)
        var totalLumaSum = 0.0

        for (i in 0 until numPixels) {
            val c = inPixels[i]
            val rl = inputToLinearLut[(c ushr 16) and 0xFF]
            val gl = inputToLinearLut[(c ushr 8) and 0xFF]
            val bl = inputToLinearLut[c and 0xFF]
            rLin[i] = rl
            gLin[i] = gl
            bLin[i] = bl
            totalLumaSum += (0.2126f * rl + 0.7152f * gl + 0.0722f * bl)
        }

        // STEP 2: Gaussian Low-Pass Filter (OLPF) in linear space
        val olpfSigma = (0.75f + spec.olpfLowPassStrength * strength * 0.85f).coerceIn(0.60f, 1.55f)
        val rOlpfBlur = gaussianBlurSeparable(rLin, sensorW, sensorH, olpfSigma, blurTmp)
        val gOlpfBlur = gaussianBlurSeparable(gLin, sensorW, sensorH, olpfSigma, blurTmp)
        val bOlpfBlur = gaussianBlurSeparable(bLin, sensorW, sensorH, olpfSigma, blurTmp)

        val lumaLin = FloatArray(numPixels)
        for (i in 0 until numPixels) {
            val rl = rOlpfBlur[i]
            val gl = gOlpfBlur[i]
            val bl = bOlpfBlur[i]
            rLin[i] = rl
            gLin[i] = gl
            bLin[i] = bl
            lumaLin[i] = 0.2126f * rl + 0.7152f * gl + 0.0722f * bl
        }

        val meanSceneLuma = (totalLumaSum / numPixels.toDouble()).toFloat().coerceIn(0.02f, 0.95f)
        val lowLightIsoFactor = ((0.36f - meanSceneLuma) / 0.30f).coerceIn(0f, 1f)
        val effectiveIsoNoiseScale = 0.80f + 0.60f * lowLightIsoFactor

        val imageSignature = ((inPixels[numPixels / 2] xor inPixels[numPixels / 3]) and 0x7FFFFFFF)
        val shotDriftR = 1f + ((imageSignature % 19) - 9) * 0.0018f
        val shotDriftB = 1f - ((imageSignature % 17) - 8) * 0.0018f
        val shotExposureDrift = 1f + ((imageSignature % 13) - 6) * 0.003f

        // Scale spatial radii proportionally to internal sensor size
        val resScale = (max(sensorW, sensorH) / 1280f).coerceIn(0.5f, 1.6f)
        val cornerRadius = (3f * resScale).roundToInt().coerceIn(2, 5)
        val bloomRadius = (5f * resScale).roundToInt().coerceIn(3, 8)
        val nrRadius = (2f * resScale).roundToInt().coerceIn(1, 3)
        val usmRadius = (2f * resScale).roundToInt().coerceIn(1, 3)

        // Stage 2: Lens Effects in Linear Space at Internal Size (reuse rOlpfBlur/gOlpfBlur/bOlpfBlur buffers)
        val rBlurCorner = boxBlurSeparable(rLin, sensorW, sensorH, cornerRadius, blurTmp, rOlpfBlur)
        val gBlurCorner = boxBlurSeparable(gLin, sensorW, sensorH, cornerRadius, blurTmp, gOlpfBlur)
        val bBlurCorner = boxBlurSeparable(bLin, sensorW, sensorH, cornerRadius, blurTmp, bOlpfBlur)

        val cornerSoftWeight = (spec.cornerSoftness * strength).coerceIn(0f, 0.75f)
        val caFringePx = (spec.edgeCaFringingPx * resScale * tuning.chromaticAberrationMultiplier * strength).coerceIn(0f, 4.0f)
        val barrelK = (spec.barrelDistortion * strength).coerceIn(0f, 0.06f)
        val vignetteWeight = (spec.lensVignette * strength).coerceIn(0f, 0.22f)
        val veilingWeight = (spec.veilingFlare * strength).coerceIn(0f, 0.16f)

        val rLens = FloatArray(numPixels)
        val gLens = FloatArray(numPixels)
        val bLens = FloatArray(numPixels)

        val highlightClipLin = (spec.whiteClipLinear * (if (tuning.xenonFlashEnabled) 0.94f else 1.0f)).coerceIn(0.68f, 0.85f)
        val highlightExcess = FloatArray(numPixels)
        val columnSmearAccum = FloatArray(sensorW)

        val invW = 1f / sensorW.toFloat()
        val invH = 1f / sensorH.toFloat()
        val flashActive = tuning.xenonFlashEnabled

        for (y in 0 until sensorH) {
            val ny = (y + 0.5f) * invH - 0.5f
            val nySq = ny * ny
            val rowOffset = y * sensorW

            for (x in 0 until sensorW) {
                val nx = (x + 0.5f) * invW - 0.5f
                val rSq = nx * nx + nySq
                val idx = rowOffset + x

                val barrelScale = 1f - barrelK * (rSq * 2f)
                val sx = ((nx * barrelScale + 0.5f) * sensorW).toInt().coerceIn(0, sensorW - 1)
                val sy = ((ny * barrelScale + 0.5f) * sensorH).toInt().coerceIn(0, sensorH - 1)
                val sIdx = sy * sensorW + sx

                val sxPrev = (sx - 1).coerceAtLeast(0)
                val sxNext = (sx + 1).coerceAtMost(sensorW - 1)
                val syPrev = (sy - 1).coerceAtLeast(0)
                val syNext = (sy + 1).coerceAtMost(sensorH - 1)
                val edgeGrad = abs(lumaLin[sy * sensorW + sxNext] - lumaLin[sy * sensorW + sxPrev]) +
                    abs(lumaLin[syNext * sensorW + sx] - lumaLin[syPrev * sensorW + sx])
                val edgeGate = ((edgeGrad - 0.05f) * 4.5f).coerceIn(0f, 1f)

                val radialDist = sqrt(rSq * 2f)
                val caShiftR = nx * radialDist * caFringePx * (0.35f + 0.65f * edgeGate)
                val caShiftY = ny * radialDist * caFringePx * (0.35f + 0.65f * edgeGate)

                val rx = (sx + caShiftR.roundToInt()).coerceIn(0, sensorW - 1)
                val ry = (sy + caShiftY.roundToInt()).coerceIn(0, sensorH - 1)
                val bx = (sx - (caShiftR * 1.15f).roundToInt()).coerceIn(0, sensorW - 1)
                val by = (sy - (caShiftY * 1.15f).roundToInt()).coerceIn(0, sensorH - 1)

                var r = rLin[ry * sensorW + rx]
                var g = gLin[sIdx]
                var b = bLin[by * sensorW + bx]

                if (edgeGate > 0.1f && rSq > 0.08f) {
                    val fringeAmt = edgeGate * (rSq * 2.2f).coerceAtMost(1f) * 0.08f * strength
                    val chromaDiff = r - g
                    r = (r + chromaDiff * fringeAmt).coerceAtLeast(0f)
                    b = (b + chromaDiff * fringeAmt * 1.25f).coerceAtLeast(0f)
                    g = (g - chromaDiff * fringeAmt * 0.45f).coerceAtLeast(0f)
                }

                val cornerMask = ((rSq * 2.4f - 0.22f) / 0.78f).coerceIn(0f, 1f) * cornerSoftWeight
                if (cornerMask > 0.001f) {
                    r += (rBlurCorner[sIdx] - r) * cornerMask
                    g += (gBlurCorner[sIdx] - g) * cornerMask
                    b += (bBlurCorner[sIdx] - b) * cornerMask
                }

                if (flashActive) {
                    val flashFalloff = (rSq * 2.5f).coerceIn(0f, 1f)
                    val flashEnvelope = 1.06f - 0.76f * flashFalloff
                    r *= flashEnvelope * (0.96f + 0.14f * flashFalloff)
                    g *= flashEnvelope
                    b *= flashEnvelope * (1.06f - 0.18f * flashFalloff)
                } else {
                    val vig = 1f - vignetteWeight * (rSq * 2.1f).coerceIn(0f, 1f)
                    r *= vig
                    g *= vig
                    b *= vig
                }

                rLens[idx] = r
                gLens[idx] = g
                bLens[idx] = b

                val yL = (0.2126f * r + 0.7152f * g + 0.0722f * b) * spec.exposureBiasGain
                if (yL > highlightClipLin) {
                    val ex = ((yL - highlightClipLin) / (1.05f - highlightClipLin)).coerceIn(0f, 1.5f)
                    highlightExcess[idx] = ex
                    columnSmearAccum[x] += ex
                }
            }
        }

        // Stage 3: CCD Highlight Bloom & Veiling Glare
        val highlightBloomMap = boxBlurSeparable(highlightExcess, sensorW, sensorH, bloomRadius, blurTmp, lumaLin)
        val veilingMean = (highlightExcess.sum() / numPixels.toFloat()).coerceIn(0f, 0.25f) * veilingWeight

        // STEP 4: Generate 1/2-res Luma Read Noise and 1/4-res Chroma Read Noise (bilinearly upsampled)
        val halfW = (sensorW / 2).coerceAtLeast(2)
        val halfH = (sensorH / 2).coerceAtLeast(2)
        val halfLumaGrid = FloatArray(halfW * halfH) { i ->
            pseudoGaussian(i * 43 + imageSignature + 503)
        }
        val upsampledHalfLuma = upsampleBilinear(halfLumaGrid, halfW, halfH, sensorW, sensorH, blurTmp)

        val quarterW = (sensorW / 4).coerceAtLeast(2)
        val quarterH = (sensorH / 4).coerceAtLeast(2)
        val quarterCbGrid = FloatArray(quarterW * quarterH) { i ->
            pseudoGaussian(i * 31 + imageSignature + 17)
        }
        val quarterCrGrid = FloatArray(quarterW * quarterH) { i ->
            pseudoGaussian(i * 73 + imageSignature + 89)
        }
        val blotchyCbField = upsampleBilinear(quarterCbGrid, quarterW, quarterH, sensorW, sensorH, rBlurCorner)
        val blotchyCrField = upsampleBilinear(quarterCrGrid, quarterW, quarterH, sensorW, sensorH, gBlurCorner)

        // STEP 3: No top shoulder in tone curve — maps 1.0 -> 1.0
        val toneLut = buildDigicamToneLut(
            shadowRollOff = (spec.shadowRollOff + if (flashActive) 0.10f else 0f) * strength,
            midtoneGamma = 1f + (spec.midtoneGamma - 1f) * strength,
            contrast = (spec.toneContrast + if (flashActive) 0.06f else 0f) * strength
        )

        val m = spec.colorMatrix3x3
        val wbR = spec.wbGainR * shotDriftR
        val wbG = spec.wbGainG
        val wbB = spec.wbGainB * shotDriftB
        val bloomWeight = (spec.highlightBloomAmount * tuning.bloomAndSmearMultiplier * strength).coerceIn(0f, 0.28f)
        val smearWeight = (spec.verticalSmearStrength * tuning.bloomAndSmearMultiplier * strength / sensorH.toFloat()).coerceIn(0f, 0.20f)

        // STEP 3: Black clip (0.03-0.05), Pre-clip exposure gain (1.4-1.8x), White clip (0.72-0.82)
        val blackClip = (spec.blackClipLinear * strength).coerceIn(0.0f, 0.055f)
        val invBlackSpan = 1f / (1f - blackClip).coerceAtLeast(0.5f)
        val preClipExpGain = (1f + (spec.exposureBiasGain - 1f) * strength) * shotExposureDrift
        val whiteClip = (1f - (1f - spec.whiteClipLinear.coerceIn(0.72f, 0.82f)) * strength).coerceIn(0.72f, 0.96f)
        val invWhiteClip = 1f / whiteClip

        // STEP 4: Shot noise (~ sqrt(luma) before tone curve) & Constant Read Noise (after black crush)
        val shotNoiseSigma = (spec.fineLumaGrain * tuning.ccdNoiseMultiplier * effectiveIsoNoiseScale * strength).coerceIn(0f, 0.035f)
        val sigmaLumaFloor = (spec.sigmaLumaFloor * tuning.ccdNoiseMultiplier * strength).coerceIn(0f, 0.045f)
        val sigmaChromaBlotch = (spec.shadowChromaSpeckle * tuning.ccdNoiseMultiplier * strength).coerceIn(0f, 0.065f)
        val satBoost = 1f + (spec.saturationBoost - 1f) * strength

        val yTonePlane = rLin
        val cbPlane = gLin
        val crPlane = bLin
        val readNoiseYStore = bBlurCorner
        var clippedCount = 0
        var peakSmear = 0f

        for (y in 0 until sensorH) {
            val rowOffset = y * sensorW

            for (x in 0 until sensorW) {
                val idx = rowOffset + x
                var r = rLens[idx] + veilingMean
                var g = gLens[idx] + veilingMean
                var b = bLens[idx] + veilingMean

                val bloomAdd = highlightBloomMap[idx] * bloomWeight
                val colSmear = (columnSmearAccum[x] * smearWeight).coerceAtMost(0.18f)
                if (colSmear > peakSmear) peakSmear = colSmear
                val totalBleed = bloomAdd + colSmear
                r += totalBleed
                g += totalBleed * 0.98f
                b += totalBleed * 1.04f

                // STEP 3.1: Black clip 0.03-0.05 first in linear space
                r = ((r - blackClip).coerceAtLeast(0f)) * invBlackSpan
                g = ((g - blackClip).coerceAtLeast(0f)) * invBlackSpan
                b = ((b - blackClip).coerceAtLeast(0f)) * invBlackSpan

                // STEP 3.2: Pre-clip exposure gain 1.4-1.8x (per mode)
                if (!flashActive) {
                    r *= preClipExpGain
                    g *= preClipExpGain
                    b *= preClipExpGain
                }

                // STEP 4.1: Shot noise ~ sqrt(luma) before the tone curve
                val linY = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(0f)
                val nShot = pseudoGaussian(idx * 19 + imageSignature + 4099) * shotNoiseSigma * sqrt(linY)
                r = (r + nShot).coerceAtLeast(0f)
                g = (g + nShot).coerceAtLeast(0f)
                b = (b + nShot).coerceAtLeast(0f)

                // STEP 3.3: Apply per-channel WB gains (and color matrix) BEFORE clamping each channel to 1.0
                val rMat = (m[0] * r + m[1] * g + m[2] * b) * wbR
                val gMat = (m[3] * r + m[4] * g + m[5] * b) * wbG
                val bMat = (m[6] * r + m[7] * g + m[8] * b) * wbB

                r = (r + (rMat - r) * strength).coerceAtLeast(0f)
                g = (g + (gMat - g) * strength).coerceAtLeast(0f)
                b = (b + (bMat - b) * strength).coerceAtLeast(0f)

                // STEP 3.4: White clip 0.72-0.82 (per mode). When 2+ channels clip, desaturate toward white.
                r *= invWhiteClip
                g *= invWhiteClip
                b *= invWhiteClip

                val rClipT = ((r - 0.88f) / 0.12f).coerceIn(0f, 1f)
                val gClipT = ((g - 0.88f) / 0.12f).coerceIn(0f, 1f)
                val bClipT = ((b - 0.88f) / 0.12f).coerceIn(0f, 1f)
                val twoChannelClipT = max(min(rClipT, gClipT), max(min(gClipT, bClipT), min(rClipT, bClipT)))
                if (twoChannelClipT > 0f) {
                    r += (1f - r.coerceAtMost(1f)) * twoChannelClipT
                    g += (1f - g.coerceAtMost(1f)) * twoChannelClipT
                    b += (1f - b.coerceAtMost(1f)) * twoChannelClipT
                }

                r = r.coerceIn(0f, 1f)
                g = g.coerceIn(0f, 1f)
                b = b.coerceIn(0f, 1f)

                val rGam = linearToSrgb(r)
                val gGam = linearToSrgb(g)
                val bGam = linearToSrgb(b)

                val yGam = (0.299f * rGam + 0.587f * gGam + 0.114f * bGam).coerceIn(0f, 1f)
                var cb = -0.168736f * rGam - 0.331264f * gGam + 0.5f * bGam
                var cr = 0.5f * rGam - 0.418688f * gGam - 0.081312f * bGam

                // Tone curve maps 1.0 -> 1.0 with no top shoulder
                var yToned = toneLut[(yGam * 1023f + 0.5f).toInt().coerceIn(0, 1023)]
                if (yToned >= 0.99f || r >= 0.999f || g >= 0.999f || b >= 0.999f) clippedCount++

                val shadowMask = (1f - yToned).pow(2)
                val hiMask = ((yToned - 0.72f) / 0.28f).coerceIn(0f, 1f) * (1f - ((yToned - 0.95f) / 0.05f).coerceIn(0f, 1f))
                val cyanGreenTint = spec.shadowCyanGreenTint * strength * shadowMask
                val hiMagenta = spec.highlightMagentaTilt * strength * hiMask

                cb += cyanGreenTint * 0.45f + hiMagenta * 0.35f
                cr -= cyanGreenTint * 0.85f - hiMagenta * 0.65f

                val clipDesat = (1f - ((yToned - 0.90f) / 0.10f).coerceIn(0f, 1f) * 0.85f) * (1f - twoChannelClipT)
                cb *= satBoost * clipDesat
                cr *= satBoost * clipDesat

                // STEP 4.2: Constant read noise injected AFTER the black crush
                // Luma read noise at 1/1 + 1/2 res; Cb/Cr read noise at 1/4 res bilinearly upsampled
                val nLumaFull = pseudoGaussian(idx * 37 + imageSignature + 8191)
                val nLumaCombined = (0.65f * nLumaFull + 0.35f * upsampledHalfLuma[idx]) * 1.148f * sigmaLumaFloor
                readNoiseYStore[idx] = nLumaCombined

                var yWithRead = yToned + nLumaCombined
                if (yWithRead < 0f) {
                    // Fold negative excursions on 0.0 black so dark areas keep full read-noise floor
                    yWithRead = -yWithRead * 0.85f
                }
                yToned = yWithRead.coerceIn(0f, 1f)

                val chromaFloorWeight = (0.55f + 0.45f * shadowMask) * (1f - twoChannelClipT * 0.9f)
                cb += blotchyCbField[idx] * sigmaChromaBlotch * chromaFloorWeight
                cr += blotchyCrField[idx] * sigmaChromaBlotch * chromaFloorWeight

                yTonePlane[idx] = yToned
                cbPlane[idx] = cb
                crPlane[idx] = cr
            }
        }

        // STEP 4.3: Smeary In-Camera Noise Reduction AFTER the noise step
        val yBlurNr = boxBlurSeparable(yTonePlane, sensorW, sensorH, nrRadius, blurTmp, rLens)
        val cbSmear = boxBlurSeparable(cbPlane, sensorW, sensorH, 1, blurTmp, gLens)
        val crSmear = boxBlurSeparable(crPlane, sensorW, sensorH, 1, blurTmp, bLens)
        val nrSmearWeight = (spec.deClaritySoftness * strength).coerceIn(0f, 0.75f)

        val yAfterNr = highlightExcess
        for (i in 0 until numPixels) {
            val yOrig = yTonePlane[i]
            val ySmooth = yBlurNr[i]
            val diff = abs(yOrig - ySmooth)
            if (diff < 0.085f) {
                val t = 1f - (diff / 0.085f)
                // Smooth low-contrast texture while retaining the dark-current read noise floor in deep shadows
                val shadowFloorKeep = (1f - ySmooth).pow(2) * 0.55f
                val effectiveNr = nrSmearWeight * t * (1f - shadowFloorKeep)
                yAfterNr[i] = (yOrig + (ySmooth - yOrig) * effectiveNr).coerceIn(0f, 1f)
            } else {
                yAfterNr[i] = yOrig
            }
        }

        // Stage 5: Per-Mode Over-Sharpening with Visible Edge Halos at Internal Sensor Size
        val yBlurUsm = boxBlurSeparable(yAfterNr, sensorW, sensorH, usmRadius, blurTmp, yBlurNr)
        val usmAmount = (spec.usmCrispness * tuning.edgeHaloUsmMultiplier * strength * 1.40f).coerceIn(0f, 2.0f)
        val haloCap = (spec.usmHaloLimit * tuning.edgeHaloUsmMultiplier * strength).coerceIn(0.03f, 0.18f)

        for (i in 0 until numPixels) {
            val edgeDelta = yAfterNr[i] - yBlurUsm[i]
            if (abs(edgeDelta) > 0.012f) {
                val haloOvershoot = (edgeDelta * usmAmount).coerceIn(-haloCap, haloCap)
                yAfterNr[i] = (yAfterNr[i] + haloOvershoot).coerceIn(0f, 1f)
            }
        }

        // Stage 6: True 4:2:0 Chroma Subsampling at Internal Sensor Size
        val outSensorPixels = inPixels
        for (y in 0 until sensorH step 2) {
            val y0 = y * sensorW
            val y1 = min(y + 1, sensorH - 1) * sensorW
            for (x in 0 until sensorW step 2) {
                val x1 = min(x + 1, sensorW - 1)
                val i00 = y0 + x
                val i01 = y0 + x1
                val i10 = y1 + x
                val i11 = y1 + x1

                val cb420 = 0.25f * (cbSmear[i00] + cbSmear[i01] + cbSmear[i10] + cbSmear[i11])
                val cr420 = 0.25f * (crSmear[i00] + crSmear[i01] + crSmear[i10] + crSmear[i11])

                val blockIndices = intArrayOf(i00, i01, i10, i11)
                for (idx in blockIndices) {
                    val yVal = yAfterNr[idx].coerceIn(0f, 1f)

                    val rOut = (yVal + 1.402f * cr420).coerceIn(0f, 1f)
                    val gOut = (yVal - 0.344136f * cb420 - 0.714136f * cr420).coerceIn(0f, 1f)
                    val bOut = (yVal + 1.772f * cb420).coerceIn(0f, 1f)

                    val rInt = (rOut * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val gInt = (gOut * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val bInt = (bOut * 255f + 0.5f).toInt().coerceIn(0, 255)
                    outSensorPixels[idx] = (0xFF shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
                }
            }
        }

        val internalBmp = Bitmap.createBitmap(sensorW, sensorH, Bitmap.Config.ARGB_8888)
        internalBmp.setPixels(outSensorPixels, 0, sensorW, 0, 0, sensorW, sensorH)

        // Stage 7: Imprint Classic 7-Segment Quartz Date Stamp AT INTERNAL SENSOR SIZE!
        if (timestampConfig.enabled) {
            val internalCanvas = Canvas(internalBmp)
            QuartzTimestampRenderer.imprintTimestamp(
                canvas = internalCanvas,
                imageWidth = sensorW,
                imageHeight = sensorH,
                config = timestampConfig
            )
        }

        // Stage 8: Actual JPEG Encode/Decode Round-Trip AT INTERNAL SENSOR SIZE (Q58..Q86)!
        val effectiveJpegQuality = (spec.jpegQuality / tuning.jpegCrunchMultiplier.coerceIn(0.8f, 1.25f))
            .roundToInt()
            .coerceIn(56, 88)

        val internalJpegBaos = ByteArrayOutputStream()
        internalBmp.compress(Bitmap.CompressFormat.JPEG, effectiveJpegQuality, internalJpegBaos)
        val internalJpegBytes = internalJpegBaos.toByteArray()
        val compressedKb = (internalJpegBytes.size / 1024).coerceAtLeast(1)
        val decodedInternalBmp = BitmapFactory.decodeByteArray(internalJpegBytes, 0, internalJpegBytes.size) ?: internalBmp
        if (decodedInternalBmp !== internalBmp) {
            internalBmp.recycle()
        }

        // STEP 2: Upscale to 1080x1440 ONLY at the end using Lanczos-3 (never bilinear),
        // or keep native internal size if tuning.exportNativeInternalSize is enabled.
        val finalOutputBmp: Bitmap
        val outW: Int
        val outH: Int
        if (tuning.exportNativeInternalSize) {
            outW = sensorW
            outH = sensorH
            finalOutputBmp = decodedInternalBmp
        } else {
            val targetShortEdge = 1080
            val shortEdge = min(sensorW, sensorH).coerceAtLeast(1)
            val finalScale = targetShortEdge.toFloat() / shortEdge.toFloat()
            outW = (((sensorW * finalScale).roundToInt().coerceAtLeast(64)) / 2) * 2
            outH = (((sensorH * finalScale).roundToInt().coerceAtLeast(64)) / 2) * 2
            finalOutputBmp = resampleLanczos3(decodedInternalBmp, outW, outH)
            if (decodedInternalBmp !== finalOutputBmp) {
                decodedInternalBmp.recycle()
            }
        }

        // STEP 2: Save the final file at Q >= 92
        val finalSaveQuality = spec.finalExportJpegQuality.coerceIn(92, 100)
        val exportBaos = ByteArrayOutputStream()
        finalOutputBmp.compress(Bitmap.CompressFormat.JPEG, finalSaveQuality, exportBaos)
        val exportJpegBytes = exportBaos.toByteArray()

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return IspProcessedResult(
            bitmap = finalOutputBmp,
            telemetry = IspPipelineTelemetry(
                effectiveSensorWidth = sensorW,
                effectiveSensorHeight = sensorH,
                outputWidth = outW,
                outputHeight = outH,
                clippedHighlightFraction = (clippedCount.toFloat() / numPixels.toFloat()).coerceIn(0f, 1f),
                peakVerticalSmear = peakSmear,
                effectiveJpegQuality = effectiveJpegQuality,
                compressedSizeKb = compressedKb,
                processingTimeMs = elapsedMs
            ),
            jpegBytes = exportJpegBytes
        )
    }

    private fun linearToSrgb(lin: Float): Float {
        val c = lin.coerceIn(0f, 1.0f)
        return if (c <= 0.0031308f) {
            12.92f * c
        } else {
            1.055f * c.pow(1f / 2.4f) - 0.055f
        }
    }

    /**
     * STEP 3: Tone curve with NO top shoulder — strictly maps 1.0 -> 1.0.
     */
    private fun buildDigicamToneLut(
        shadowRollOff: Float,
        midtoneGamma: Float,
        contrast: Float
    ): FloatArray {
        val lut = FloatArray(1024)
        val blackToe = (shadowRollOff * 0.09f).coerceIn(0f, 0.05f)
        val denom = (1f - blackToe).coerceAtLeast(0.5f)
        val invGamma = 1f / midtoneGamma.coerceIn(0.75f, 1.35f)

        for (i in 0 until 1024) {
            val x = i / 1023f
            var v = ((x - blackToe) / denom).coerceIn(0f, 1f)

            if (v < 0.55f) {
                val t = (0.55f - v) / 0.55f
                v -= shadowRollOff * 0.28f * v * t * t
                v = v.coerceAtLeast(0f)
            }

            v = v.pow(invGamma)

            val s = v * v * (3f - 2f * v)
            val curved = (v + (s - v) * contrast).coerceIn(0f, 1f)
            // Top 5% linearly locks to 1.0 with zero top shoulder roll-off
            lut[i] = if (x >= 0.95f) {
                val t = (x - 0.95f) / 0.05f
                (curved + (1f - curved) * t).coerceIn(0f, 1f)
            } else {
                curved
            }
        }
        lut[1023] = 1.0f
        return lut
    }

    /**
     * STEP 2: Separable 5-tap Gaussian Low-Pass Filter.
     */
    private fun gaussianBlurSeparable(
        src: FloatArray,
        width: Int,
        height: Int,
        sigma: Float,
        tmp: FloatArray,
        dstBuf: FloatArray? = null
    ): FloatArray {
        val dst = dstBuf ?: FloatArray(width * height)
        val s = sigma.coerceIn(0.4f, 2.5f)
        val twoSigmaSq = 2f * s * s
        val w0 = 1f
        val w1 = exp(-1f / twoSigmaSq)
        val w2 = exp(-4f / twoSigmaSq)
        val norm = 1f / (w0 + 2f * (w1 + w2))
        val k0 = w0 * norm
        val k1 = w1 * norm
        val k2 = w2 * norm

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val xm2 = (x - 2).coerceAtLeast(0)
                val xm1 = (x - 1).coerceAtLeast(0)
                val xp1 = (x + 1).coerceAtMost(width - 1)
                val xp2 = (x + 2).coerceAtMost(width - 1)
                tmp[row + x] = src[row + x] * k0 +
                    (src[row + xm1] + src[row + xp1]) * k1 +
                    (src[row + xm2] + src[row + xp2]) * k2
            }
        }

        for (y in 0 until height) {
            val ym2 = (y - 2).coerceAtLeast(0) * width
            val ym1 = (y - 1).coerceAtLeast(0) * width
            val y0 = y * width
            val yp1 = (y + 1).coerceAtMost(height - 1) * width
            val yp2 = (y + 2).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                dst[y0 + x] = tmp[y0 + x] * k0 +
                    (tmp[ym1 + x] + tmp[yp1 + x]) * k1 +
                    (tmp[ym2 + x] + tmp[yp2 + x]) * k2
            }
        }
        return dst
    }

    /**
     * STEP 4: Bilinear upsampling for 1/4-res Chroma Read Noise & 1/2-res Luma Read Noise.
     */
    private fun upsampleBilinear(
        grid: FloatArray,
        gridW: Int,
        gridH: Int,
        dstW: Int,
        dstH: Int,
        dstBuf: FloatArray? = null
    ): FloatArray {
        val out = dstBuf ?: FloatArray(dstW * dstH)
        val scaleX = (gridW - 1).toFloat() / (dstW - 1).coerceAtLeast(1).toFloat()
        val scaleY = (gridH - 1).toFloat() / (dstH - 1).coerceAtLeast(1).toFloat()

        for (y in 0 until dstH) {
            val gy = y * scaleY
            val y0 = gy.toInt().coerceIn(0, gridH - 1)
            val y1 = (y0 + 1).coerceAtMost(gridH - 1)
            val fy = gy - y0
            val row0 = y0 * gridW
            val row1 = y1 * gridW
            val dstRow = y * dstW

            for (x in 0 until dstW) {
                val gx = x * scaleX
                val x0 = gx.toInt().coerceIn(0, gridW - 1)
                val x1 = (x0 + 1).coerceAtMost(gridW - 1)
                val fx = gx - x0

                val v00 = grid[row0 + x0]
                val v01 = grid[row0 + x1]
                val v10 = grid[row1 + x0]
                val v11 = grid[row1 + x1]

                val top = v00 + (v01 - v00) * fx
                val bot = v10 + (v11 - v10) * fx
                out[dstRow + x] = top + (bot - top) * fy
            }
        }
        return out
    }

    /**
     * STEP 2: Separable 2-pass Lanczos-3 Resampler (never bilinear) for final output scaling.
     */
    private fun resampleLanczos3(srcBitmap: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val srcW = srcBitmap.width
        val srcH = srcBitmap.height
        if (srcW == dstW && srcH == dstH) return srcBitmap

        val srcPixels = IntArray(srcW * srcH)
        srcBitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        // Precompute horizontal 6-tap Lanczos-3 indices & weights
        val hIdx = IntArray(dstW * 6)
        val hWgt = FloatArray(dstW * 6)
        val scaleX = srcW.toFloat() / dstW.toFloat()
        for (dx in 0 until dstW) {
            val center = (dx + 0.5f) * scaleX - 0.5f
            val base = kotlin.math.floor(center).toInt()
            var wSum = 0f
            val off = dx * 6
            for (t in 0 until 6) {
                val sx = base - 2 + t
                hIdx[off + t] = sx.coerceIn(0, srcW - 1)
                val w = lanczos3Kernel(center - sx)
                hWgt[off + t] = w
                wSum += w
            }
            val invW = if (abs(wSum) > 1e-6f) 1f / wSum else 1f
            for (t in 0 until 6) {
                hWgt[off + t] *= invW
            }
        }

        // Precompute vertical 6-tap Lanczos-3 indices & weights
        val vIdx = IntArray(dstH * 6)
        val vWgt = FloatArray(dstH * 6)
        val scaleY = srcH.toFloat() / dstH.toFloat()
        for (dy in 0 until dstH) {
            val center = (dy + 0.5f) * scaleY - 0.5f
            val base = kotlin.math.floor(center).toInt()
            var wSum = 0f
            val off = dy * 6
            for (t in 0 until 6) {
                val sy = base - 2 + t
                vIdx[off + t] = sy.coerceIn(0, srcH - 1) * dstW
                val w = lanczos3Kernel(center - sy)
                vWgt[off + t] = w
                wSum += w
            }
            val invW = if (abs(wSum) > 1e-6f) 1f / wSum else 1f
            for (t in 0 until 6) {
                vWgt[off + t] *= invW
            }
        }

        // Horizontal pass -> packed intermediate R, G, B float planes (srcH * dstW)
        val tmpSize = srcH * dstW
        val tmpR = FloatArray(tmpSize)
        val tmpG = FloatArray(tmpSize)
        val tmpB = FloatArray(tmpSize)

        for (y in 0 until srcH) {
            val srcRow = y * srcW
            val dstRow = y * dstW
            for (dx in 0 until dstW) {
                val off = dx * 6
                var rAcc = 0f
                var gAcc = 0f
                var bAcc = 0f
                for (t in 0 until 6) {
                    val c = srcPixels[srcRow + hIdx[off + t]]
                    val w = hWgt[off + t]
                    rAcc += ((c ushr 16) and 0xFF) * w
                    gAcc += ((c ushr 8) and 0xFF) * w
                    bAcc += (c and 0xFF) * w
                }
                val outIdx = dstRow + dx
                tmpR[outIdx] = rAcc
                tmpG[outIdx] = gAcc
                tmpB[outIdx] = bAcc
            }
        }

        // Vertical pass -> final dstPixels (dstW * dstH)
        val dstPixels = IntArray(dstW * dstH)
        for (dy in 0 until dstH) {
            val off = dy * 6
            val r0 = vIdx[off]
            val r1 = vIdx[off + 1]
            val r2 = vIdx[off + 2]
            val r3 = vIdx[off + 3]
            val r4 = vIdx[off + 4]
            val r5 = vIdx[off + 5]
            val w0 = vWgt[off]
            val w1 = vWgt[off + 1]
            val w2 = vWgt[off + 2]
            val w3 = vWgt[off + 3]
            val w4 = vWgt[off + 4]
            val w5 = vWgt[off + 5]
            val outRow = dy * dstW

            for (dx in 0 until dstW) {
                val rVal = (tmpR[r0 + dx] * w0 + tmpR[r1 + dx] * w1 + tmpR[r2 + dx] * w2 +
                    tmpR[r3 + dx] * w3 + tmpR[r4 + dx] * w4 + tmpR[r5 + dx] * w5 + 0.5f).toInt().coerceIn(0, 255)
                val gVal = (tmpG[r0 + dx] * w0 + tmpG[r1 + dx] * w1 + tmpG[r2 + dx] * w2 +
                    tmpG[r3 + dx] * w3 + tmpG[r4 + dx] * w4 + tmpG[r5 + dx] * w5 + 0.5f).toInt().coerceIn(0, 255)
                val bVal = (tmpB[r0 + dx] * w0 + tmpB[r1 + dx] * w1 + tmpB[r2 + dx] * w2 +
                    tmpB[r3 + dx] * w3 + tmpB[r4 + dx] * w4 + tmpB[r5 + dx] * w5 + 0.5f).toInt().coerceIn(0, 255)
                dstPixels[outRow + dx] = (0xFF shl 24) or (rVal shl 16) or (gVal shl 8) or bVal
            }
        }

        val outBmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        return outBmp
    }

    private fun lanczos3Kernel(x: Float): Float {
        val ax = abs(x)
        if (ax < 1e-5f) return 1f
        if (ax >= 3f) return 0f
        val pix = PI.toFloat() * x
        val pixOver3 = pix / 3f
        return (sin(pix) / pix) * (sin(pixOver3) / pixOver3)
    }

    private fun boxBlurSeparable(
        src: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        tmp: FloatArray,
        dstBuf: FloatArray? = null
    ): FloatArray {
        val dst = dstBuf ?: FloatArray(width * height)
        if (radius <= 0 || width <= 2 || height <= 2) {
            System.arraycopy(src, 0, dst, 0, src.size)
            return dst
        }
        val r = radius.coerceIn(1, 12)
        val window = 2 * r + 1
        val invWin = 1f / window.toFloat()

        for (y in 0 until height) {
            val rowOffset = y * width
            var sum = 0f
            for (k in -r..r) {
                val xk = k.coerceIn(0, width - 1)
                sum += src[rowOffset + xk]
            }
            tmp[rowOffset] = sum * invWin
            for (x in 1 until width) {
                val addIdx = (x + r).coerceAtMost(width - 1)
                val subIdx = (x - r - 1).coerceAtLeast(0)
                sum += src[rowOffset + addIdx] - src[rowOffset + subIdx]
                tmp[rowOffset + x] = sum * invWin
            }
        }

        for (x in 0 until width) {
            var sum = 0f
            for (k in -r..r) {
                val yk = k.coerceIn(0, height - 1)
                sum += tmp[yk * width + x]
            }
            dst[x] = sum * invWin
            for (y in 1 until height) {
                val addIdx = (y + r).coerceAtMost(height - 1)
                val subIdx = (y - r - 1).coerceAtLeast(0)
                sum += tmp[addIdx * width + x] - tmp[subIdx * width + x]
                dst[y * width + x] = sum * invWin
            }
        }
        return dst
    }

    private fun pseudoGaussian(seed: Int): Float {
        var x = seed
        x = ((x ushr 16) xor x) * 0x45d9f3b
        x = ((x ushr 16) xor x) * 0x45d9f3b
        x = (x ushr 16) xor x
        val u1 = ((x and 0x7FFF) / 32767f) - 0.5f
        val u2 = (((x ushr 15) and 0x7FFF) / 32767f) - 0.5f
        return (u1 + u2) * 1.414f
    }
}
