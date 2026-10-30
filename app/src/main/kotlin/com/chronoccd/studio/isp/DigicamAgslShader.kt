package com.chronoccd.studio.isp

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.IspUserTuning

/**
 * Real-time GPU AGSL RuntimeShader matching the 11-stage 2000s Compact Digicam hardware pipeline.
 *
 * Mirrors the exact stages of [DigicamIspPipeline] on the live CameraX viewfinder so the live
 * preview matches the captured and saved Gallery output.
 */
object DigicamAgslShader {

    private const val AGSL_SOURCE = """
        uniform shader content;
        uniform float2 resolution;
        uniform float3 row0;
        uniform float3 row1;
        uniform float3 row2;
        uniform float3 wbGains;
        uniform float satBoost;
        uniform float shadowRollOff;
        uniform float highlightShoulder;
        uniform float invGamma;
        uniform float toneContrast;
        uniform float deClarity;
        uniform float usmRadiusPx;
        uniform float usmCrisp;
        uniform float usmHaloCap;
        uniform float highlightBloom;
        uniform float bloomThreshold;
        uniform float blueVioletFringe;
        uniform float fineGrain;
        uniform float chromaBlotch;
        uniform float cornerSoftness;
        uniform float caKr;
        uniform float caKb;
        uniform float barrelK;
        uniform float vignetteWeight;
        uniform float exposureBias;
        uniform float shadowCyanGreen;
        uniform float highlightMagenta;
        uniform float sensorLongEdge;
        uniform float flashActive;
        uniform float linearTonemapActive;
        uniform float blackClip;
        uniform float whiteClip;
        uniform float sigmaLumaFloor;

        float srgbChannelToLinear(float s) {
            float c = clamp(s, 0.0, 1.0);
            return (c <= 0.04045) ? (c / 12.92) : pow((c + 0.055) / 1.055, 2.4);
        }

        float linearChannelToSrgb(float lin) {
            float c = clamp(lin, 0.0, 1.0);
            return (c <= 0.0031308) ? (12.92 * c) : (1.055 * pow(c, 1.0 / 2.4) - 0.055);
        }

        float3 encodeLinearToSrgb(float3 lin) {
            return float3(
                linearChannelToSrgb(lin.r),
                linearChannelToSrgb(lin.g),
                linearChannelToSrgb(lin.b)
            );
        }

        float3 decodeInputToLinear(float3 rgb) {
            if (linearTonemapActive > 0.5) {
                return clamp(rgb, float3(0.0), float3(1.0));
            }
            return float3(
                srgbChannelToLinear(rgb.r),
                srgbChannelToLinear(rgb.g),
                srgbChannelToLinear(rgb.b)
            );
        }

        float hash12(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float bilinearNoise(float2 qCoord, float2 seedOffset) {
            float2 i = floor(qCoord);
            float2 f = fract(qCoord);
            float v00 = (hash12(i + seedOffset) - 0.5) * 2.0;
            float v10 = (hash12(i + float2(1.0, 0.0) + seedOffset) - 0.5) * 2.0;
            float v01 = (hash12(i + float2(0.0, 1.0) + seedOffset) - 0.5) * 2.0;
            float v11 = (hash12(i + float2(1.0, 1.0) + seedOffset) - 0.5) * 2.0;
            return mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
        }

        // STEP 3: Tone curve with NO top shoulder — strictly maps 1.0 -> 1.0
        float applyDigicamToneCurve(float x) {
            float blackToe = clamp(shadowRollOff * 0.09, 0.0, 0.05);
            float denom = max(0.50, 1.0 - blackToe);
            float v = clamp((x - blackToe) / denom, 0.0, 1.0);
            if (v < 0.55) {
                float t = (0.55 - v) / 0.55;
                v = max(0.0, v - shadowRollOff * 0.28 * v * t * t);
            }
            v = pow(max(v, 0.0001), invGamma);
            float s = v * v * (3.0 - 2.0 * v);
            float curved = clamp(mix(v, s, toneContrast), 0.0, 1.0);
            if (x >= 0.95) {
                float t = (x - 0.95) / 0.05;
                return clamp(mix(curved, 1.0, t), 0.0, 1.0);
            }
            return curved;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(resolution, float2(64.0, 64.0));
            float2 uv = fragCoord / res - 0.5;
            // Normalized r^2 in [0.0, 1.0] from center to corners
            float rSq = 2.0 * dot(uv, uv);

            // STEP 6: Consistent barrel distortion across all modes
            float barrelScale = (1.0 + barrelK * rSq) / (1.0 + barrelK);
            float2 bUv = uv * barrelScale;
            float maxRes = max(res.x, res.y);
            float pixelGridStep = max(1.0, maxRes / max(480.0, sensorLongEdge));
            float2 rawBCoord = (bUv + 0.5) * res;
            float2 quantCoord = (floor(rawBCoord / pixelGridStep) + 0.5) * pixelGridStep;
            float2 bCoord = clamp(mix(rawBCoord, quantCoord, 0.55), float2(1.0), res - float2(1.0));

            // STEP 6: Lateral CA: R scale = 1 + k_r*r^2, B scale = 1 - k_b*r^2 (k_r > 0, k_b > 0)
            float rScale = 1.0 + caKr * rSq;
            float bScale = 1.0 - caKb * rSq;
            float2 rCoord = clamp((bUv * rScale + 0.5) * res, float2(1.0), res - float2(1.0));
            float2 bCoordCa = clamp((bUv * bScale + 0.5) * res, float2(1.0), res - float2(1.0));

            float rSample = content.eval(rCoord).r;
            float bSample = content.eval(bCoordCa).b;
            float3 cCenter = decodeInputToLinear(content.eval(bCoord).rgb);
            float rCa = (linearTonemapActive > 0.5) ? clamp(rSample, 0.0, 1.0) : srgbChannelToLinear(rSample);
            float bCa = (linearTonemapActive > 0.5) ? clamp(bSample, 0.0, 1.0) : srgbChannelToLinear(bSample);
            float3 colLin = float3(rCa, cCenter.g, bCa);

            // Neighborhood at USM radius (1.0 - 1.5 px at internal resolution)
            float usmStepPx = max(1.0, pixelGridStep * clamp(usmRadiusPx, 1.0, 1.5));
            float3 cU = decodeInputToLinear(content.eval(clamp(bCoord - float2(0.0, usmStepPx), float2(0.0), res)).rgb);
            float3 cD = decodeInputToLinear(content.eval(clamp(bCoord + float2(0.0, usmStepPx), float2(0.0), res)).rgb);
            float3 cL = decodeInputToLinear(content.eval(clamp(bCoord - float2(usmStepPx, 0.0), float2(0.0), res)).rgb);
            float3 cR = decodeInputToLinear(content.eval(clamp(bCoord + float2(usmStepPx, 0.0), float2(0.0), res)).rgb);
            float3 meanLin = 0.25 * (cU + cD + cL + cR);

            // Neighborhood at sigma ~ 3 px for point-light bloom
            float bloomStepPx = max(2.5, pixelGridStep * 3.0);
            float3 bU = decodeInputToLinear(content.eval(clamp(bCoord - float2(0.0, bloomStepPx), float2(0.0), res)).rgb);
            float3 bD = decodeInputToLinear(content.eval(clamp(bCoord + float2(0.0, bloomStepPx), float2(0.0), res)).rgb);
            float3 bL = decodeInputToLinear(content.eval(clamp(bCoord - float2(bloomStepPx, 0.0), float2(0.0), res)).rgb);
            float3 bR = decodeInputToLinear(content.eval(clamp(bCoord + float2(bloomStepPx, 0.0), float2(0.0), res)).rgb);
            float3 bloomMeanLin = 0.20 * (cCenter + bU + bD + bL + bR);

            // STEP 6: Mild corner softness in outer field
            float cornerMask = clamp((rSq - 0.30) / 0.70, 0.0, 1.0) * cornerSoftness;
            colLin = mix(colLin, meanLin, cornerMask);

            // STEP 6: Halved Vignette vs Flash Hotspot
            if (flashActive > 0.5) {
                float flashFalloff = clamp(rSq * 1.25, 0.0, 1.0);
                float env = 1.06 - 0.76 * flashFalloff;
                colLin.r *= env * (0.96 + 0.14 * flashFalloff);
                colLin.g *= env;
                colLin.b *= env * (1.06 - 0.18 * flashFalloff);
            } else {
                float vig = 1.0 - vignetteWeight * clamp(rSq, 0.0, 1.0);
                colLin *= vig;
                meanLin *= vig;
                bloomMeanLin *= vig;
            }

            // STEP 6: Point-light bloom (threshold luma > 0.82, sigma ~3 px) & dark-side blue-violet fringing (10-20%)
            float yCurrPre = dot(colLin, float3(0.2126, 0.7152, 0.0722)) * exposureBias;
            float yBloomPre = dot(bloomMeanLin, float3(0.2126, 0.7152, 0.0722)) * exposureBias;
            float hiEx = clamp((yBloomPre - bloomThreshold) / max(0.10, 1.0 - bloomThreshold), 0.0, 1.5);
            colLin += float3(1.0, 0.98, 1.04) * (hiEx * highlightBloom);

            float darkSideEdge = max(0.0, yBloomPre - yCurrPre);
            float darkSideMask = pow(1.0 - clamp(yCurrPre / bloomThreshold, 0.0, 1.0), 2.0);
            float fringeSig = max(hiEx * darkSideMask, darkSideEdge * clamp((yBloomPre - 0.45) / 0.40, 0.0, 1.0) * 1.35);
            if (fringeSig > 0.001) {
                float bv = fringeSig * blueVioletFringe;
                colLin.r += bv * 0.48;
                colLin.g = max(0.0, colLin.g - bv * 0.22);
                colLin.b += bv * 1.00;
            }

            // STEP 3.1: Black clip 0.03-0.05 first in linear space
            float invBlackSpan = 1.0 / max(0.5, 1.0 - blackClip);
            colLin = max(float3(0.0), colLin - float3(blackClip)) * invBlackSpan;
            meanLin = max(float3(0.0), meanLin - float3(blackClip)) * invBlackSpan;

            // STEP 3.2: Pre-clip exposure gain 1.4-1.8x (per mode)
            if (flashActive < 0.5) {
                colLin *= exposureBias;
                meanLin *= exposureBias;
            }

            // STEP 4.1: Shot noise ~ sqrt(luma) before the tone curve
            float linY = max(0.0, dot(colLin, float3(0.2126, 0.7152, 0.0722)));
            float nShot = (hash12(fragCoord + float2(19.0, 47.0)) - 0.5) * 2.0 * fineGrain * sqrt(linY);
            colLin = max(float3(0.0), colLin + float3(nShot));

            // STEP 3.3: Apply per-channel WB gains BEFORE clamping each channel to 1.0
            float3 ccdLin;
            ccdLin.r = max(0.0, dot(row0, colLin) * wbGains.r);
            ccdLin.g = max(0.0, dot(row1, colLin) * wbGains.g);
            ccdLin.b = max(0.0, dot(row2, colLin) * wbGains.b);

            float3 meanMat;
            meanMat.r = max(0.0, dot(row0, meanLin) * wbGains.r);
            meanMat.g = max(0.0, dot(row1, meanLin) * wbGains.g);
            meanMat.b = max(0.0, dot(row2, meanLin) * wbGains.b);

            // STEP 3.4: White clip 0.72-0.82 (per mode). When 2+ channels clip, desaturate toward white.
            float invWhite = 1.0 / clamp(whiteClip, 0.72, 0.82);
            ccdLin *= invWhite;
            meanMat = clamp(meanMat * invWhite, float3(0.0), float3(1.0));

            float3 clipT = clamp((ccdLin - float3(0.88)) / 0.12, float3(0.0), float3(1.0));
            float twoChanClip = max(min(clipT.r, clipT.g), max(min(clipT.g, clipT.b), min(clipT.r, clipT.b)));
            if (twoChanClip > 0.0) {
                ccdLin = mix(min(ccdLin, float3(1.0)), float3(1.0), twoChanClip);
            }
            ccdLin = clamp(ccdLin, float3(0.0), float3(1.0));

            // 6. Linear -> sRGB OETF
            float3 ccdGam = encodeLinearToSrgb(ccdLin);
            float3 meanGam = encodeLinearToSrgb(meanMat);

            float yOrig = clamp(dot(ccdGam, float3(0.299, 0.587, 0.114)), 0.0, 1.0);
            float yMean = clamp(dot(meanGam, float3(0.299, 0.587, 0.114)), 0.0, 1.0);
            float cb = -0.168736 * ccdGam.r - 0.331264 * ccdGam.g + 0.5 * ccdGam.b;
            float cr =  0.5 * ccdGam.r - 0.418688 * ccdGam.g - 0.081312 * ccdGam.b;

            // 7. Tone Curve (no top shoulder, maps 1.0 -> 1.0)
            float yToned = applyDigicamToneCurve(yOrig);
            float yMeanToned = applyDigicamToneCurve(yMean);

            // 8. Channel Crosstalk + Pushed Saturation
            float shMask = (1.0 - yToned) * (1.0 - yToned);
            float hiMask = clamp((yToned - 0.72) / 0.28, 0.0, 1.0) * (1.0 - clamp((yToned - 0.95) / 0.05, 0.0, 1.0));
            cb += shadowCyanGreen * 0.45 * shMask + highlightMagenta * 0.35 * hiMask;
            cr -= shadowCyanGreen * 0.85 * shMask - highlightMagenta * 0.65 * hiMask;

            float clipDesat = (1.0 - clamp((yToned - 0.90) / 0.10, 0.0, 1.0) * 0.85) * (1.0 - twoChanClip);
            cb *= satBoost * clipDesat;
            cr *= satBoost * clipDesat;

            // STEP 4.2: Constant Read Noise injected AFTER black crush (1/1+1/2 res luma, 1/4 res bilinear Cb/Cr)
            float2 halfCoord = fragCoord / max(2.0, pixelGridStep * 2.0);
            float nLumaFull = (hash12(fragCoord) - 0.5) * 2.0;
            float nLumaHalf = bilinearNoise(halfCoord, float2(53.0, 97.0));
            float nLumaRead = (0.65 * nLumaFull + 0.35 * nLumaHalf) * 1.148 * sigmaLumaFloor;
            float yWithRead = yToned + nLumaRead;
            if (yWithRead < 0.0) {
                yWithRead = -yWithRead * 0.85;
            }
            yToned = clamp(yWithRead, 0.0, 1.0);

            float2 quarterCoord = fragCoord / max(4.0, pixelGridStep * 4.0);
            float nCb = bilinearNoise(quarterCoord, float2(17.0, 31.0)) * chromaBlotch * (0.55 + 0.45 * shMask);
            float nCr = bilinearNoise(quarterCoord, float2(73.0, 89.0)) * chromaBlotch * (0.55 + 0.45 * shMask);
            cb += nCb;
            cr += nCr;

            // STEP 4.3 & STEP 5: Smeary NR after the noise step + Luma USM (radius 1-1.5 px at internal res, ~40% reduced halo)
            float dY = yToned - yMeanToned;
            if (abs(dY) < 0.085) {
                float t = 1.0 - (abs(dY) / 0.085);
                float shadowKeep = (1.0 - yMeanToned) * (1.0 - yMeanToned) * 0.55;
                yToned = mix(yToned, yMeanToned, deClarity * t * (1.0 - shadowKeep));
            }
            float edgeDelta = yToned - yMeanToned;
            if (abs(edgeDelta) > 0.010) {
                float halo = clamp(edgeDelta * usmCrisp, -usmHaloCap, usmHaloCap);
                yToned = clamp(yToned + halo, 0.0, 1.0);
            }

            float3 outRgb;
            outRgb.r = clamp(yToned + 1.402 * cr, 0.0, 1.0);
            outRgb.g = clamp(yToned - 0.344136 * cb - 0.714136 * cr, 0.0, 1.0);
            outRgb.b = clamp(yToned + 1.772 * cb, 0.0, 1.0);

            return half4(half3(outRgb), 1.0);
        }
    """

    fun createComposeRenderEffect(
        persona: DigicamPersona,
        tuning: IspUserTuning,
        widthPx: Float,
        heightPx: Float,
        linearTonemapActive: Boolean = false
    ): androidx.compose.ui.graphics.RenderEffect? {
        val spec = persona.ispSpec

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && widthPx > 10f && heightPx > 10f) {
            val agslEffect = runCatching {
                val shader = RuntimeShader(AGSL_SOURCE)
                val m = spec.colorMatrix3x3
                shader.setFloatUniform("resolution", widthPx, heightPx)
                shader.setFloatUniform("row0", m[0], m[1], m[2])
                shader.setFloatUniform("row1", m[3], m[4], m[5])
                shader.setFloatUniform("row2", m[6], m[7], m[8])
                shader.setFloatUniform("wbGains", spec.wbGainR, spec.wbGainG, spec.wbGainB)
                shader.setFloatUniform("satBoost", spec.saturationBoost)
                val effShadow = if (tuning.xenonFlashEnabled) {
                    (spec.shadowRollOff + 0.10f).coerceAtMost(0.48f)
                } else {
                    spec.shadowRollOff
                }
                val effShoulder = if (tuning.xenonFlashEnabled) {
                    (spec.highlightShoulder * 0.95f).coerceAtLeast(0.68f)
                } else {
                    spec.highlightShoulder
                }
                shader.setFloatUniform("shadowRollOff", effShadow)
                shader.setFloatUniform("highlightShoulder", effShoulder)
                shader.setFloatUniform("invGamma", 1f / spec.midtoneGamma.coerceIn(0.75f, 1.35f))
                shader.setFloatUniform("toneContrast", spec.toneContrast)
                shader.setFloatUniform("deClarity", spec.deClaritySoftness)
                shader.setFloatUniform("usmRadiusPx", spec.usmRadiusPx)
                shader.setFloatUniform("usmCrisp", spec.usmCrispness * tuning.edgeHaloUsmMultiplier)
                shader.setFloatUniform("usmHaloCap", spec.usmHaloLimit * tuning.edgeHaloUsmMultiplier)
                shader.setFloatUniform("highlightBloom", spec.highlightBloomAmount * tuning.bloomAndSmearMultiplier)
                shader.setFloatUniform("bloomThreshold", spec.pointLightBloomThreshold)
                shader.setFloatUniform("blueVioletFringe", spec.blueVioletFringeStrength * tuning.chromaticAberrationMultiplier)
                shader.setFloatUniform("fineGrain", spec.fineLumaGrain * tuning.ccdNoiseMultiplier)
                shader.setFloatUniform("chromaBlotch", spec.shadowChromaSpeckle * tuning.ccdNoiseMultiplier)
                shader.setFloatUniform("cornerSoftness", spec.cornerSoftness)
                shader.setFloatUniform("caKr", spec.caKr * tuning.chromaticAberrationMultiplier)
                shader.setFloatUniform("caKb", spec.caKb * tuning.chromaticAberrationMultiplier)
                shader.setFloatUniform("barrelK", spec.barrelDistortion)
                shader.setFloatUniform("vignetteWeight", spec.lensVignette)
                shader.setFloatUniform("exposureBias", spec.exposureBiasGain)
                shader.setFloatUniform("shadowCyanGreen", spec.shadowCyanGreenTint)
                shader.setFloatUniform("highlightMagenta", spec.highlightMagentaTilt)
                shader.setFloatUniform("sensorLongEdge", spec.sensorLongEdgePx.toFloat())
                shader.setFloatUniform("flashActive", if (tuning.xenonFlashEnabled) 1f else 0f)
                shader.setFloatUniform("linearTonemapActive", if (linearTonemapActive) 1f else 0f)
                shader.setFloatUniform("blackClip", spec.blackClipLinear)
                shader.setFloatUniform("whiteClip", spec.whiteClipLinear)
                shader.setFloatUniform("sigmaLumaFloor", spec.sigmaLumaFloor * tuning.ccdNoiseMultiplier)

                RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
            }.getOrNull()

            if (agslEffect != null) return agslEffect
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return runCatching {
                val m = spec.colorMatrix3x3
                val c = 1f + 0.28f * spec.toneContrast
                val offset = (-spec.shadowRollOff * 52f)
                val cm = ColorMatrix(
                    floatArrayOf(
                        m[0] * spec.wbGainR * c, m[1] * c, m[2] * c, 0f, offset,
                        m[3] * c, m[4] * spec.wbGainG * c, m[5] * c, 0f, offset,
                        m[6] * c, m[7] * c, m[8] * spec.wbGainB * c, 0f, offset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm)).asComposeRenderEffect()
            }.getOrNull()
        }

        return null
    }
}
