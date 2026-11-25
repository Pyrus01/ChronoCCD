package com.chronoccd.studio.isp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
 * Implements the exact per-mode resolution & artifact architecture with a strict 6-plane
 * peak Java heap footprint:
 * 1. Capture high-res still -> Optical Low-Pass Filter (OLPF) -> Downsample to per-mode internal size
 *    (VGA 640x480, 0.8MP 960x720, 1.3MP 1280x960, 2MP 1600x1200, or 4MP 2048x1536),
 *    or preserve crisp full sensor resolution (capped to safe heap limits).
 * 2. Run the ENTIRE chain at that internal size:
 *    - Linear space conversion
 *    - Compact lens physics (barrel distortion, inline 5-tap corner softness, lateral CA, vignette / Xenon flash)
 *    - Light-starved 8-10 stop CCD dynamic range, early highlight clip, bloom & vertical smear
 *    - Signal-dependent shot noise + AWB + per-camera 3x3 matrix + channel crosstalk
 *    - Punchy SOOC S-curve tone mapping + constant read noise floor
 *    - Smeary in-camera noise reduction
 *    - Per-mode Luma Unsharp Mask sharpening with capped edge halos
 *    - 4:2:0 chroma subsampling streamed 2 rows at a time
 *    - Classic 7-segment quartz date stamp imprinted at internal sensor size
 *    - Actual JPEG encode/decode round-trip at per-mode quality (58..86) in retro modes
 * 3. Upscale to 1080p with Lanczos-3 filtering ONLY on final export.
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

    private val LINEAR_TO_SRGB_BYTE = IntArray(256) { i ->
        (linearToSrgb(i / 255f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private val ispLock = Any()

    /**
     * Converts a linear-tonemap bitmap into display-ready sRGB row-by-row with minimal heap usage.
     */
    fun encodeLinearBitmapToSrgb(source: Bitmap): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            source.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val a = c and 0xFF000000.toInt()
                val r = LINEAR_TO_SRGB_BYTE[(c ushr 16) and 0xFF]
                val g = LINEAR_TO_SRGB_BYTE[(c ushr 8) and 0xFF]
                val b = LINEAR_TO_SRGB_BYTE[c and 0xFF]
                row[x] = a or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

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

        val srcW = source.width.coerceAtLeast(2)
        val srcH = source.height.coerceAtLeast(2)

        // Step 1: Determine Internal Resolution:
        // Either crisp full sensor resolution (heap-guarded) or each mode's predetermined resolution
        val sensorW: Int
        val sensorH: Int
        if (tuning.useFullSensorResolution) {
            val maxLongEdge = if (isPreviewMode) 2560 else 3840
            var targetScale = if (max(srcW, srcH) > maxLongEdge) {
                maxLongEdge.toFloat() / max(srcW, srcH).toFloat()
            } else {
                1f
            }
            if (!isPreviewMode) {
                val maxHeapBytes = Runtime.getRuntime().maxMemory().toDouble()
                val maxSafePixels = ((maxHeapBytes * 0.45) / (6.5 * 4.0)).toLong().coerceAtLeast(1_500_000L)
                val scaledPixels = (srcW * targetScale).toDouble() * (srcH * targetScale).toDouble()
                if (scaledPixels > maxSafePixels.toDouble()) {
                    val memScale = sqrt(maxSafePixels.toDouble() / scaledPixels).toFloat()
                    targetScale *= memScale
                }
            }
            sensorW = (((srcW * targetScale).roundToInt().coerceAtLeast(2)) / 2) * 2
            sensorH = (((srcH * targetScale).roundToInt().coerceAtLeast(2)) / 2) * 2
        } else {
            val internalLongEdge = (spec.sensorLongEdgePx * tuning.sensorPitchMultiplier)
                .roundToInt()
                .coerceIn(480, 2048)
            val internalScale = internalLongEdge.toFloat() / max(srcW, srcH).toFloat()
            sensorW = (((srcW * internalScale).roundToInt().coerceAtLeast(64)) / 2) * 2
            sensorH = (((srcH * internalScale).roundToInt().coerceAtLeast(64)) / 2) * 2
        }

        // Multi-step pre-downsample so high-res phone detail is cleanly filtered before hitting the CCD grid
        val sensorBitmap = if (tuning.useFullSensorResolution) {
            if (source.width != sensorW || source.height != sensorH) {
                Bitmap.createScaledBitmap(source, sensorW, sensorH, true)
            } else {
                source
            }
        } else {
            val preW = min(srcW, sensorW * 2).coerceAtLeast(sensorW)
            val preH = min(srcH, sensorH * 2).coerceAtLeast(sensorH)
            val preBitmap = if (preW < srcW || preH < srcH) {
                Bitmap.createScaledBitmap(source, preW, preH, true)
            } else {
                source
            }
            val scaled = Bitmap.createScaledBitmap(preBitmap, sensorW, sensorH, true)
            if (preBitmap !== source && preBitmap !== scaled) {
                preBitmap.recycle()
            }
            scaled
        }

        val numPixels = sensorW * sensorH

        // Strict 6-plane peak Java heap budget: (rLin, gLin, bLin, rLens, gLens, bLens)
        val rLin = FloatArray(numPixels)
        val gLin = FloatArray(numPixels)
        val bLin = FloatArray(numPixels)
        val rLens = FloatArray(numPixels)
        val gLens = FloatArray(numPixels)
        val bLens = FloatArray(numPixels)

        // Stream input row-by-row into linear light (sRGB inverse unless a linear tonemap is active)
        val inputToLinearLut = if (linearTonemapActive) LINEAR_IDENTITY else SRGB_TO_LINEAR
        val rowPixels = IntArray(sensorW)
        var totalLumaSum = 0.0
        val sigIdxA = numPixels / 2
        val sigIdxB = numPixels / 3
        var sigPixelA = 0
        var sigPixelB = 0

        for (y in 0 until sensorH) {
            sensorBitmap.getPixels(rowPixels, 0, sensorW, 0, y, sensorW, 1)
            val rowOffset = y * sensorW
            for (x in 0 until sensorW) {
                val c = rowPixels[x]
                val idx = rowOffset + x
                if (idx == sigIdxA) sigPixelA = c
                if (idx == sigIdxB) sigPixelB = c
                val rl = inputToLinearLut[(c ushr 16) and 0xFF]
                val gl = inputToLinearLut[(c ushr 8) and 0xFF]
                val bl = inputToLinearLut[c and 0xFF]
                rLin[idx] = rl
                gLin[idx] = gl
                bLin[idx] = bl
                totalLumaSum += (0.2126f * rl + 0.7152f * gl + 0.0722f * bl)
            }
        }
        if (sensorBitmap !== source) {
            sensorBitmap.recycle()
        }

        // STEP 2: Gaussian Low-Pass Filter (OLPF) in linear space (skipped in Full Sensor Resolution mode)
        // Reuse rLens as temporary scratch buffer so peak heap remains 6 planes.
        if (!tuning.useFullSensorResolution) {
            val olpfSigma = (0.75f + spec.olpfLowPassStrength * strength * 0.85f).coerceIn(0.60f, 1.55f)
            gaussianBlurSeparable(rLin, sensorW, sensorH, olpfSigma, rLens, rLin)
            gaussianBlurSeparable(gLin, sensorW, sensorH, olpfSigma, rLens, gLin)
            gaussianBlurSeparable(bLin, sensorW, sensorH, olpfSigma, rLens, bLin)
        }

        val meanSceneLuma = (totalLumaSum / numPixels.toDouble()).toFloat().coerceIn(0.02f, 0.95f)
        val lowLightIsoFactor = ((0.36f - meanSceneLuma) / 0.30f).coerceIn(0f, 1f)
        val effectiveIsoNoiseScale = 0.80f + 0.60f * lowLightIsoFactor

        val imageSignature = ((sigPixelA xor sigPixelB) and 0x7FFFFFFF)
        val shotDriftR = 1f + ((imageSignature % 19) - 9) * 0.0018f
        val shotDriftB = 1f - ((imageSignature % 17) - 8) * 0.0018f
        val shotExposureDrift = 1f + ((imageSignature % 13) - 6) * 0.003f

        // Scale spatial radii proportionally to internal sensor size
        val resScale = (max(sensorW, sensorH) / 1280f).coerceIn(0.5f, if (tuning.useFullSensorResolution) 3.5f else 1.6f)
        val cornerSigma = if (tuning.useFullSensorResolution) 1.2f else (1.6f * resScale).coerceIn(1.2f, 2.4f)
        val cornerSoftScale = if (tuning.useFullSensorResolution) 0.35f else 1.0f
        val nrRadius = if (tuning.useFullSensorResolution) 1 else (2f * resScale).roundToInt().coerceIn(1, 3)
        val nrSmearScale = if (tuning.useFullSensorResolution) 0.22f else 1.0f

        // STEP 6: Lens Effects in Linear Space at Internal Size
        // - Inline 5-tap cross corner softness (zero extra blur arrays)
        // - Consistent barrel distortion in all modes
        // - Lateral CA: R scale = 1 + k_r*r^2, B scale = 1 - k_b*r^2
        // - Halved vignette strength or softened natural Xenon flash
        val cornerSoftWeight = (spec.cornerSoftness * cornerSoftScale * strength).coerceIn(0f, 0.45f)
        val cornerStepPx = cornerSigma.roundToInt().coerceAtLeast(1)
        val caMult = (tuning.chromaticAberrationMultiplier * strength).coerceIn(0f, 2.0f)
        val kR = (spec.caKr * caMult).coerceAtLeast(0f)
        val kB = (spec.caKb * caMult).coerceAtLeast(0f)
        val barrelK = (spec.barrelDistortion * strength).coerceIn(0f, 0.06f)
        val invBarrelNorm = 1f / (1f + barrelK)
        val vignetteWeight = (spec.lensVignette * strength).coerceIn(0f, 0.12f)
        val veilingWeight = (spec.veilingFlare * strength).coerceIn(0f, 0.16f)

        val invW = 1f / sensorW.toFloat()
        val invH = 1f / sensorH.toFloat()
        val maxX = sensorW - 1
        val maxY = sensorH - 1
        val flashActive = tuning.xenonFlashEnabled

        for (y in 0 until sensorH) {
            val ny = (y + 0.5f) * invH - 0.5f
            val nySq = ny * ny
            val rowOffset = y * sensorW

            for (x in 0 until sensorW) {
                val nx = (x + 0.5f) * invW - 0.5f
                // Normalized r^2 in [0.0, 1.0] from center (0,0) to corners (±0.5, ±0.5)
                val rSq = 2f * (nx * nx + nySq)
                val idx = rowOffset + x

                // Consistent barrel distortion across all modes
                val barrelScale = (1f + barrelK * rSq) * invBarrelNorm
                val bxNorm = nx * barrelScale
                val byNorm = ny * barrelScale

                // STEP 6: Lateral CA: R scale = 1 + k_r*r^2, B scale = 1 - k_b*r^2 (k_r > 0, k_b > 0)
                val rScale = 1f + kR * rSq
                val bScale = 1f - kB * rSq

                val gx = (bxNorm + 0.5f) * sensorW - 0.5f
                val gy = (byNorm + 0.5f) * sensorH - 0.5f
                val rx = (bxNorm * rScale + 0.5f) * sensorW - 0.5f
                val ry = (byNorm * rScale + 0.5f) * sensorH - 0.5f
                val bx = (bxNorm * bScale + 0.5f) * sensorW - 0.5f
                val by = (byNorm * bScale + 0.5f) * sensorH - 0.5f

                var r = sampleBilinear2D(rLin, sensorW, sensorH, rx, ry)
                var g = sampleBilinear2D(gLin, sensorW, sensorH, gx, gy)
                var b = sampleBilinear2D(bLin, sensorW, sensorH, bx, by)

                // Mild corner softness computed inline via 5-tap cross filter (zero corner blur planes)
                val cornerMask = ((rSq - 0.30f) / 0.70f).coerceIn(0f, 1f) * cornerSoftWeight
                if (cornerMask > 0.001f) {
                    val cx = gx.roundToInt().coerceIn(0, maxX)
                    val cy = gy.roundToInt().coerceIn(0, maxY)
                    val xm = (cx - cornerStepPx).coerceAtLeast(0)
                    val xp = (cx + cornerStepPx).coerceAtMost(maxX)
                    val ym = (cy - cornerStepPx).coerceAtLeast(0) * sensorW
                    val yp = (cy + cornerStepPx).coerceAtMost(maxY) * sensorW
                    val yc = cy * sensorW
                    val iC = yc + cx
                    val iL = yc + xm
                    val iR = yc + xp
                    val iU = ym + cx
                    val iD = yp + cx

                    val rCornerBlur = 0.20f * (rLin[iC] + rLin[iL] + rLin[iR] + rLin[iU] + rLin[iD])
                    val gCornerBlur = 0.20f * (gLin[iC] + gLin[iL] + gLin[iR] + gLin[iU] + gLin[iD])
                    val bCornerBlur = 0.20f * (bLin[iC] + bLin[iL] + bLin[iR] + bLin[iU] + bLin[iD])
                    r += (rCornerBlur - r) * cornerMask
                    g += (gCornerBlur - g) * cornerMask
                    b += (bCornerBlur - b) * cornerMask
                }

                if (flashActive) {
                    val flashFalloff = (rSq * 0.85f).coerceIn(0f, 1f)
                    val flashEnvelope = 1.04f - 0.28f * flashFalloff
                    r *= flashEnvelope * (0.98f + 0.05f * flashFalloff)
                    g *= flashEnvelope
                    b *= flashEnvelope * (1.03f - 0.07f * flashFalloff)
                } else {
                    // STEP 6: Halved vignette strength
                    val vig = 1f - vignetteWeight * rSq.coerceIn(0f, 1f)
                    r *= vig
                    g *= vig
                    b *= vig
                }

                rLens[idx] = r
                gLens[idx] = g
                bLens[idx] = b
            }
        }

        // Reuse rLin / gLin / bLin for lumaLin / highlightExcess / blurTmp (strict 6-plane ceiling)
        val lumaLin = rLin
        val highlightExcess = gLin
        val blurTmp = bLin

        // STEP 6: Point-light bloom threshold luma > 0.82
        val bloomThreshold = spec.pointLightBloomThreshold.coerceIn(0.75f, 0.90f)
        val invBloomSpan = 1f / (1f - bloomThreshold).coerceAtLeast(0.10f)
        val columnSmearAccum = FloatArray(sensorW)
        var highlightExcessSum = 0.0

        for (y in 0 until sensorH) {
            val rowOffset = y * sensorW
            for (x in 0 until sensorW) {
                val idx = rowOffset + x
                val yL = (0.2126f * rLens[idx] + 0.7152f * gLens[idx] + 0.0722f * bLens[idx]) * spec.exposureBiasGain
                lumaLin[idx] = yL
                if (yL > bloomThreshold) {
                    val ex = ((yL - bloomThreshold) * invBloomSpan).coerceIn(0f, 1.5f)
                    highlightExcess[idx] = ex
                    columnSmearAccum[x] += ex
                    highlightExcessSum += ex
                } else {
                    highlightExcess[idx] = 0f
                }
            }
        }

        // STEP 6: Point-light bloom (blur sigma ~3 px), vertical smear, and dark-side blue-violet fringing
        val veilingMean = ((highlightExcessSum / numPixels.toDouble()).toFloat()).coerceIn(0f, 0.25f) * veilingWeight
        val bloomSigmaPx = spec.pointLightBloomSigmaPx.coerceIn(2.0f, 4.0f)
        val highlightBloomMap = gaussianBlurSeparable(highlightExcess, sensorW, sensorH, bloomSigmaPx, blurTmp, highlightExcess)
        val lumaEdgeBlur = gaussianBlurSeparable(lumaLin, sensorW, sensorH, 2.2f, blurTmp, lumaLin)

        // Smooth column smear horizontally across adjacent columns for natural vertical CCD streak
        val smoothedColumnSmear = FloatArray(sensorW) { x ->
            val xm1 = (x - 1).coerceAtLeast(0)
            val xp1 = (x + 1).coerceAtMost(maxX)
            0.25f * columnSmearAccum[xm1] + 0.50f * columnSmearAccum[x] + 0.25f * columnSmearAccum[xp1]
        }

        // STEP 4: Generate compact 1/2-res Luma & 1/4-res Chroma noise grids (sampled bilinearly inline)
        val halfW = (sensorW / 2).coerceAtLeast(2)
        val halfH = (sensorH / 2).coerceAtLeast(2)
        val halfLumaGrid = FloatArray(halfW * halfH) { i ->
            pseudoGaussian(i * 43 + imageSignature + 503)
        }
        val quarterW = (sensorW / 4).coerceAtLeast(2)
        val quarterH = (sensorH / 4).coerceAtLeast(2)
        val quarterCbGrid = FloatArray(quarterW * quarterH) { i ->
            pseudoGaussian(i * 31 + imageSignature + 17)
        }
        val quarterCrGrid = FloatArray(quarterW * quarterH) { i ->
            pseudoGaussian(i * 73 + imageSignature + 89)
        }
        val invSensorWm1 = 1f / (sensorW - 1).coerceAtLeast(1).toFloat()
        val invSensorHm1 = 1f / (sensorH - 1).coerceAtLeast(1).toFloat()
        val halfScaleX = (halfW - 1).toFloat() * invSensorWm1
        val halfScaleY = (halfH - 1).toFloat() * invSensorHm1
        val quarterScaleX = (quarterW - 1).toFloat() * invSensorWm1
        val quarterScaleY = (quarterH - 1).toFloat() * invSensorHm1

        // STEP 3: No top shoulder in tone curve — maps 1.0 -> 1.0
        val toneLut = buildDigicamToneLut(
            shadowRollOff = (spec.shadowRollOff + if (flashActive) 0.04f else 0f) * strength,
            midtoneGamma = 1f + (spec.midtoneGamma - 1f) * strength,
            contrast = (spec.toneContrast + if (flashActive) 0.03f else 0f) * strength
        )

        val m = spec.colorMatrix3x3
        val wbR = spec.wbGainR * shotDriftR
        val wbG = spec.wbGainG
        val wbB = spec.wbGainB * shotDriftB
        val bloomWeight = (spec.highlightBloomAmount * tuning.bloomAndSmearMultiplier * strength).coerceIn(0f, 0.28f)
        val smearWeight = (spec.verticalSmearStrength * tuning.bloomAndSmearMultiplier * strength / sensorH.toFloat()).coerceIn(0f, 0.20f)
        val blueVioletFringeWeight = (spec.blueVioletFringeStrength * tuning.chromaticAberrationMultiplier * strength).coerceIn(0f, 0.25f)

        // STEP 3: Black clip (0.03-0.05), Pre-clip exposure gain (1.4-1.8x, moderated on flash), White clip (0.72-0.82)
        val blackClip = (spec.blackClipLinear * strength).coerceIn(0.0f, 0.055f)
        val invBlackSpan = 1f / (1f - blackClip).coerceAtLeast(0.5f)
        val baseExpGain = (1f + (spec.exposureBiasGain - 1f) * strength) * shotExposureDrift
        val effExpGain = if (flashActive) 1f + (baseExpGain - 1f) * 0.55f else baseExpGain
        val whiteClip = (1f - (1f - spec.whiteClipLinear.coerceIn(0.72f, 0.82f)) * strength).coerceIn(0.72f, 0.96f)
        val invWhiteClip = 1f / whiteClip

        // STEP 4: Shot noise (~ sqrt(luma) before tone curve) & Constant Read Noise (after black crush)
        val shotNoiseSigma = (spec.fineLumaGrain * tuning.ccdNoiseMultiplier * effectiveIsoNoiseScale * strength).coerceIn(0f, 0.035f)
        val sigmaLumaFloor = (spec.sigmaLumaFloor * tuning.ccdNoiseMultiplier * strength).coerceIn(0f, 0.045f)
        val sigmaChromaBlotch = (spec.shadowChromaSpeckle * tuning.ccdNoiseMultiplier * strength).coerceIn(0f, 0.065f)
        val satBoost = 1f + (spec.saturationBoost - 1f) * strength

        // Overwrite rLens / gLens / bLens in-place with Y / Cb / Cr
        val yTonePlane = rLens
        val cbPlane = gLens
        val crPlane = bLens
        var clippedCount = 0
        var peakSmear = 0f

        for (y in 0 until sensorH) {
            val rowOffset = y * sensorW
            val halfFy = y * halfScaleY
            val quarterFy = y * quarterScaleY

            for (x in 0 until sensorW) {
                val idx = rowOffset + x
                val rBase = rLens[idx]
                val gBase = gLens[idx]
                val bBase = bLens[idx]
                var r = rBase + veilingMean
                var g = gBase + veilingMean
                var b = bBase + veilingMean

                // STEP 6: Point-light bloom (sigma ~3 px) + vertical smear
                val bloomAdd = highlightBloomMap[idx] * bloomWeight
                val colSmear = (smoothedColumnSmear[x] * smearWeight).coerceAtMost(0.18f)
                if (colSmear > peakSmear) peakSmear = colSmear
                val totalBleed = bloomAdd + colSmear
                r += totalBleed
                g += totalBleed * 0.98f
                b += totalBleed * 1.04f

                // STEP 6: Blue-violet fringing on the dark side of edges at 10-20% strength
                val yCurr = (0.2126f * rBase + 0.7152f * gBase + 0.0722f * bBase) * spec.exposureBiasGain
                val yNeighbor = lumaEdgeBlur[idx]
                val darkSideEdge = (yNeighbor - yCurr).coerceAtLeast(0f)
                val darkSideMask = (1f - (yCurr / bloomThreshold).coerceIn(0f, 1f)).pow(2)
                val bloomDarkSide = highlightBloomMap[idx] * darkSideMask
                val brightEdgeDarkSide = darkSideEdge * ((yNeighbor - 0.45f) / 0.40f).coerceIn(0f, 1f)
                val fringeSignal = max(bloomDarkSide, brightEdgeDarkSide * 1.35f)
                if (fringeSignal > 0.001f) {
                    val bvFringe = fringeSignal * blueVioletFringeWeight
                    r += bvFringe * 0.48f
                    g = (g - bvFringe * 0.22f).coerceAtLeast(0f)
                    b += bvFringe * 1.00f
                }

                // STEP 3.1: Black clip 0.03-0.05 first in linear space
                r = ((r - blackClip).coerceAtLeast(0f)) * invBlackSpan
                g = ((g - blackClip).coerceAtLeast(0f)) * invBlackSpan
                b = ((b - blackClip).coerceAtLeast(0f)) * invBlackSpan

                // STEP 3.2: Pre-clip exposure gain (moderated when Xenon flash is enabled)
                r *= effExpGain
                g *= effExpGain
                b *= effExpGain

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

                // STEP 4.2: Constant read noise injected AFTER the black crush (inline bilinear grid sampling)
                val nLumaFull = pseudoGaussian(idx * 37 + imageSignature + 8191)
                val nLumaHalf = sampleBilinear2D(halfLumaGrid, halfW, halfH, x * halfScaleX, halfFy)
                val nLumaCombined = (0.65f * nLumaFull + 0.35f * nLumaHalf) * 1.148f * sigmaLumaFloor

                var yWithRead = yToned + nLumaCombined
                if (yWithRead < 0f) {
                    // Fold negative excursions on 0.0 black so dark areas keep full read-noise floor
                    yWithRead = -yWithRead * 0.85f
                }
                yToned = yWithRead.coerceIn(0f, 1f)

                val chromaFloorWeight = (0.55f + 0.45f * shadowMask) * (1f - twoChannelClipT * 0.9f)
                val qFx = x * quarterScaleX
                cb += sampleBilinear2D(quarterCbGrid, quarterW, quarterH, qFx, quarterFy) * sigmaChromaBlotch * chromaFloorWeight
                cr += sampleBilinear2D(quarterCrGrid, quarterW, quarterH, qFx, quarterFy) * sigmaChromaBlotch * chromaFloorWeight

                yTonePlane[idx] = yToned
                cbPlane[idx] = cb
                crPlane[idx] = cr
            }
        }

        // STEP 4.3: Smeary In-Camera Noise Reduction AFTER the noise step
        // rLin, gLin, bLin are now free for scratch & destination buffers.
        val yBlurNr = boxBlurSeparable(yTonePlane, sensorW, sensorH, nrRadius, blurTmp, rLin)
        val cbSmear = if (tuning.useFullSensorResolution) {
            cbPlane
        } else {
            boxBlurSeparable(cbPlane, sensorW, sensorH, 1, blurTmp, cbPlane)
        }
        val crSmear = if (tuning.useFullSensorResolution) {
            crPlane
        } else {
            boxBlurSeparable(crPlane, sensorW, sensorH, 1, blurTmp, crPlane)
        }
        val nrSmearWeight = (spec.deClaritySoftness * nrSmearScale * strength).coerceIn(0f, 0.75f)

        val yAfterNr = gLin
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

        // STEP 5: Luma Unsharp Mask ONLY, radius 1.0-1.5 px at internal resolution
        val usmRadiusPx = spec.usmRadiusPx.coerceIn(1.0f, 1.5f)
        val yBlurUsm = gaussianBlurSeparable(yAfterNr, sensorW, sensorH, usmRadiusPx, blurTmp, yBlurNr)
        val usmAmount = (spec.usmCrispness * tuning.edgeHaloUsmMultiplier * strength).coerceIn(0f, 1.2f)
        val haloCap = (spec.usmHaloLimit * tuning.edgeHaloUsmMultiplier * strength).coerceIn(0f, 0.12f)

        for (i in 0 until numPixels) {
            val edgeDelta = yAfterNr[i] - yBlurUsm[i]
            if (abs(edgeDelta) > 0.010f) {
                val haloOvershoot = (edgeDelta * usmAmount).coerceIn(-haloCap, haloCap)
                yAfterNr[i] = (yAfterNr[i] + haloOvershoot).coerceIn(0f, 1f)
            }
        }

        // Stage 6: True 4:2:0 Chroma Subsampling streamed 2 rows at a time directly into internalBmp
        val internalBmp = Bitmap.createBitmap(sensorW, sensorH, Bitmap.Config.ARGB_8888)
        val rowPairPixels = IntArray(sensorW * 2)
        for (y in 0 until sensorH step 2) {
            val rowsInChunk = min(2, sensorH - y)
            val y0 = y * sensorW
            val y1 = min(y + 1, maxY) * sensorW
            for (x in 0 until sensorW step 2) {
                val x1 = min(x + 1, maxX)
                val i00 = y0 + x
                val i01 = y0 + x1
                val i10 = y1 + x
                val i11 = y1 + x1

                val cb420 = 0.25f * (cbSmear[i00] + cbSmear[i01] + cbSmear[i10] + cbSmear[i11])
                val cr420 = 0.25f * (crSmear[i00] + crSmear[i01] + crSmear[i10] + crSmear[i11])

                rowPairPixels[x] = yCbCrToArgb(yAfterNr[i00], cb420, cr420)
                if (x1 != x) {
                    rowPairPixels[x1] = yCbCrToArgb(yAfterNr[i01], cb420, cr420)
                }
                if (rowsInChunk == 2) {
                    rowPairPixels[sensorW + x] = yCbCrToArgb(yAfterNr[i10], cb420, cr420)
                    if (x1 != x) {
                        rowPairPixels[sensorW + x1] = yCbCrToArgb(yAfterNr[i11], cb420, cr420)
                    }
                }
            }
            internalBmp.setPixels(rowPairPixels, 0, sensorW, 0, y, sensorW, rowsInChunk)
        }

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

        // Stage 8: JPEG Encode/Decode Round-Trip at internal size (skipped in crisp Full Sensor Resolution mode)
        val effectiveJpegQuality: Int
        var compressedKb: Int
        val decodedInternalBmp: Bitmap
        if (tuning.useFullSensorResolution) {
            effectiveJpegQuality = spec.finalExportJpegQuality.coerceIn(92, 100)
            decodedInternalBmp = internalBmp
            compressedKb = ((sensorW * sensorH) / 6 / 1024).coerceAtLeast(1)
        } else {
            effectiveJpegQuality = (spec.jpegQuality / tuning.jpegCrunchMultiplier.coerceIn(0.8f, 1.25f))
                .roundToInt()
                .coerceIn(56, 88)
            val internalJpegBaos = ByteArrayOutputStream()
            internalBmp.compress(Bitmap.CompressFormat.JPEG, effectiveJpegQuality, internalJpegBaos)
            val internalJpegBytes = internalJpegBaos.toByteArray()
            compressedKb = (internalJpegBytes.size / 1024).coerceAtLeast(1)
            decodedInternalBmp = BitmapFactory.decodeByteArray(internalJpegBytes, 0, internalJpegBytes.size) ?: internalBmp
            if (decodedInternalBmp !== internalBmp) {
                internalBmp.recycle()
            }
        }

        // STEP 2: Upscale to 1080p ONLY on final export using Lanczos-3 (skip in preview mode or full/native size)
        val finalOutputBmp: Bitmap
        val outW: Int
        val outH: Int
        if (isPreviewMode || tuning.useFullSensorResolution || tuning.exportNativeInternalSize) {
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

        // STEP 2: Save the final file at Q >= 92 (skip in preview mode)
        val exportJpegBytes: ByteArray? = if (isPreviewMode) {
            null
        } else {
            val finalSaveQuality = spec.finalExportJpegQuality.coerceIn(92, 100)
            val exportBaos = ByteArrayOutputStream()
            finalOutputBmp.compress(Bitmap.CompressFormat.JPEG, finalSaveQuality, exportBaos)
            val bytes = exportBaos.toByteArray()
            if (tuning.useFullSensorResolution) {
                compressedKb = (bytes.size / 1024).coerceAtLeast(1)
            }
            bytes
        }

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

    private fun yCbCrToArgb(yRaw: Float, cb: Float, cr: Float): Int {
        val yVal = yRaw.coerceIn(0f, 1f)
        val rOut = (yVal + 1.402f * cr).coerceIn(0f, 1f)
        val gOut = (yVal - 0.344136f * cb - 0.714136f * cr).coerceIn(0f, 1f)
        val bOut = (yVal + 1.772f * cb).coerceIn(0f, 1f)

        val rInt = (rOut * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gInt = (gOut * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bInt = (bOut * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
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
     * Bilinear 2D sampler for smooth subpixel barrel distortion, lateral CA shifts, and compact noise grids.
     */
    private fun sampleBilinear2D(
        plane: FloatArray,
        width: Int,
        height: Int,
        fx: Float,
        fy: Float
    ): Float {
        val cx = fx.coerceIn(0f, (width - 1).toFloat())
        val cy = fy.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt().coerceIn(0, width - 1)
        val y0 = cy.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val tx = cx - x0
        val ty = cy - y0

        val r0 = y0 * width
        val r1 = y1 * width
        val top = plane[r0 + x0] + (plane[r0 + x1] - plane[r0 + x0]) * tx
        val bot = plane[r1 + x0] + (plane[r1 + x1] - plane[r1 + x0]) * tx
        return top + (bot - top) * ty
    }

    /**
     * Separable Gaussian Filter supporting OLPF (0.6..1.5 px), Luma USM (1.0..1.5 px),
     * and Point-Light Bloom (sigma ~3.0 px).
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
        val s = sigma.coerceIn(0.4f, 4.0f)
        val radius = kotlin.math.ceil(2.5f * s).toInt().coerceIn(2, 9)
        val twoSigmaSq = 2f * s * s
        val weights = FloatArray(radius + 1)
        var sumW = 1f
        weights[0] = 1f
        for (k in 1..radius) {
            val w = exp(-(k * k).toFloat() / twoSigmaSq)
            weights[k] = w
            sumW += 2f * w
        }
        val norm = 1f / sumW
        for (k in 0..radius) {
            weights[k] *= norm
        }

        val maxW = width - 1
        val maxH = height - 1
        val w0 = weights[0]

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = src[row + x] * w0
                for (k in 1..radius) {
                    val xm = (x - k).coerceAtLeast(0)
                    val xp = (x + k).coerceAtMost(maxW)
                    acc += (src[row + xm] + src[row + xp]) * weights[k]
                }
                tmp[row + x] = acc
            }
        }

        for (y in 0 until height) {
            val y0 = y * width
            for (x in 0 until width) {
                var acc = tmp[y0 + x] * w0
                for (k in 1..radius) {
                    val ym = (y - k).coerceAtLeast(0) * width
                    val yp = (y + k).coerceAtMost(maxH) * width
                    acc += (tmp[ym + x] + tmp[yp + x]) * weights[k]
                }
                dst[y0 + x] = acc
            }
        }
        return dst
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
            if (src !== dst) {
                System.arraycopy(src, 0, dst, 0, src.size)
            }
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
