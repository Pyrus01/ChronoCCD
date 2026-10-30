package com.chronoccd.studio.isp

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.chronoccd.studio.data.TimestampConfig
import com.chronoccd.studio.data.TimestampPosition
import kotlin.math.min

/**
 * Authentic 2000s 7-Segment / 9-Segment Quartz Date & Timestamp Imprint Renderer.
 *
 * Draws hexagonal LED segments with a classic 6-degree rightward quartz slant and
 * 4-pass phosphor halation (Outer CCD Bleed -> Inner Glow -> Core Segment -> Hot Saturated Spine).
 * Executed inside the ISP pipeline BEFORE the final JPEG 4:2:0 DCT compression pass so that
 * the timestamp exhibits genuine digicam chroma bleed and mosquito ringing into the image.
 */
object QuartzTimestampRenderer {

    // Segment indices for a 9-segment glyph cell:
    // 0: Top horizontal
    // 1: Top-left vertical
    // 2: Top-right vertical
    // 3: Middle horizontal
    // 4: Bottom-left vertical
    // 5: Bottom-right vertical
    // 6: Bottom horizontal
    // 7: Top-center vertical (for T, I, etc.)
    // 8: Bottom-center vertical (for T, I, etc.)
    // 9: Diagonal top-left to center (for M, N, K, Y)
    // 10: Diagonal top-right to center (for M, V, Y, K)
    // 11: Diagonal center to bottom-right (for R, N, K)
    private val glyphMap: Map<Char, IntArray> = mapOf(
        '0' to intArrayOf(0, 1, 2, 4, 5, 6),
        '1' to intArrayOf(2, 5),
        '2' to intArrayOf(0, 2, 3, 4, 6),
        '3' to intArrayOf(0, 2, 3, 5, 6),
        '4' to intArrayOf(1, 2, 3, 5),
        '5' to intArrayOf(0, 1, 3, 5, 6),
        '6' to intArrayOf(0, 1, 3, 4, 5, 6),
        '7' to intArrayOf(0, 2, 5),
        '8' to intArrayOf(0, 1, 2, 3, 4, 5, 6),
        '9' to intArrayOf(0, 1, 2, 3, 5, 6),
        '-' to intArrayOf(3),
        // Month letters (JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC)
        'A' to intArrayOf(0, 1, 2, 3, 4, 5),
        'B' to intArrayOf(0, 1, 2, 3, 4, 5, 6),
        'C' to intArrayOf(0, 1, 4, 6),
        'D' to intArrayOf(0, 2, 4, 5, 6, 7, 8),
        'E' to intArrayOf(0, 1, 3, 4, 6),
        'F' to intArrayOf(0, 1, 3, 4),
        'G' to intArrayOf(0, 1, 3, 4, 5, 6),
        'H' to intArrayOf(1, 2, 3, 4, 5),
        'I' to intArrayOf(0, 6, 7, 8),
        'J' to intArrayOf(2, 4, 5, 6),
        'L' to intArrayOf(1, 4, 6),
        'M' to intArrayOf(1, 2, 4, 5, 9, 10),
        'N' to intArrayOf(1, 2, 4, 5, 9, 11),
        'O' to intArrayOf(0, 1, 2, 4, 5, 6),
        'P' to intArrayOf(0, 1, 2, 3, 4),
        'R' to intArrayOf(0, 1, 2, 3, 4, 11),
        'S' to intArrayOf(0, 1, 3, 5, 6),
        'T' to intArrayOf(0, 7, 8),
        'U' to intArrayOf(1, 2, 4, 5, 6),
        'V' to intArrayOf(1, 4, 10),
        'Y' to intArrayOf(9, 10, 8)
    )

    fun imprintTimestamp(
        canvas: Canvas,
        imageWidth: Int,
        imageHeight: Int,
        config: TimestampConfig
    ) {
        if (!config.enabled) return
        val text = config.formattedString()
        if (text.isBlank()) return

        val minDim = min(imageWidth, imageHeight).toFloat()
        val charH = (minDim * 0.040f * config.scaleMultiplier).coerceIn(14f, 140f)
        val charW = charH * 0.56f
        val segThick = (charH * 0.165f).coerceAtLeast(2.2f)
        val spacing = charW * 0.34f
        val narrowW = charW * 0.38f

        // Compute total width of rendered string
        var totalW = 0f
        text.forEachIndexed { idx, ch ->
            totalW += when (ch) {
                '.', ':', '\'' -> narrowW
                ' ' -> charW * 0.55f
                else -> charW
            }
            if (idx < text.lastIndex) totalW += spacing
        }

        val insetX = imageWidth * 0.065f
        val insetY = imageHeight * 0.062f

        val startX = when (config.position) {
            TimestampPosition.BOTTOM_RIGHT -> (imageWidth - insetX - totalW).coerceAtLeast(insetX * 0.5f)
            TimestampPosition.BOTTOM_LEFT -> insetX
        }
        val startY = (imageHeight - insetY - charH).coerceAtLeast(insetY * 0.5f)

        val glowScale = config.glowIntensity.coerceIn(0f, 1.5f)
        val led = config.ledColor

        // Prepare paths for all segments in the string
        val corePath = Path()
        val spinePath = Path()

        var cursorX = 0f
        for (ch in text) {
            when (ch) {
                ' ' -> {
                    cursorX += charW * 0.55f + spacing
                }
                '.' -> {
                    addDot(corePath, spinePath, cursorX + narrowW * 0.35f, charH - segThick * 0.6f, segThick * 0.65f)
                    cursorX += narrowW + spacing
                }
                ':' -> {
                    addDot(corePath, spinePath, cursorX + narrowW * 0.35f, charH * 0.32f, segThick * 0.6f)
                    addDot(corePath, spinePath, cursorX + narrowW * 0.35f, charH * 0.72f, segThick * 0.6f)
                    cursorX += narrowW + spacing
                }
                '\'' -> {
                    addApostrophe(corePath, spinePath, cursorX + narrowW * 0.3f, 0f, segThick * 0.75f, charH * 0.30f)
                    cursorX += narrowW + spacing
                }
                else -> {
                    val segments = glyphMap[ch.uppercaseChar()] ?: intArrayOf(0, 1, 2, 4, 5, 6)
                    for (segId in segments) {
                        addSegmentBar(corePath, spinePath, segId, cursorX, 0f, charW, charH, segThick)
                    }
                    cursorX += charW + spacing
                }
            }
        }

        canvas.save()
        canvas.translate(startX, startY)
        // Classic 6-degree rightward quartz italic slant
        canvas.skew(-0.105f, 0f)

        // Pass 1: Outer CCD Charge Bleed Halo
        if (glowScale > 0.05f) {
            val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = segThick * 1.4f
                color = applyAlpha(led.outerBleedHex, (0.36f * glowScale).coerceIn(0f, 0.85f))
                maskFilter = BlurMaskFilter((segThick * 2.2f * glowScale).coerceAtLeast(1.5f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(corePath, outerPaint)

            // Pass 2: Inner Phosphor Halation
            val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = segThick * 0.65f
                color = applyAlpha(led.innerGlowHex, (0.68f * glowScale).coerceIn(0f, 0.95f))
                maskFilter = BlurMaskFilter((segThick * 0.85f * glowScale).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(corePath, innerPaint)
        }

        // Pass 3: Crisp Hexagonal LED Segment Core
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = led.coreHex
        }
        canvas.drawPath(corePath, corePaint)

        // Pass 4: Hot Saturated Filament Center Spine
        val hotCoreColor = blendColors(led.coreHex, 0xFFFFF8E7.toInt(), 0.48f)
        val spinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = (segThick * 0.32f).coerceAtLeast(1f)
            color = hotCoreColor
        }
        canvas.drawPath(spinePath, spinePaint)

        canvas.restore()
    }

    private fun addDot(corePath: Path, spinePath: Path, cx: Float, cy: Float, radius: Float) {
        corePath.addCircle(cx, cy, radius, Path.Direction.CW)
        spinePath.moveTo(cx - radius * 0.2f, cy)
        spinePath.lineTo(cx + radius * 0.2f, cy)
    }

    private fun addApostrophe(corePath: Path, spinePath: Path, x: Float, y: Float, w: Float, h: Float) {
        corePath.moveTo(x, y)
        corePath.lineTo(x + w, y)
        corePath.lineTo(x + w * 0.65f, y + h)
        corePath.lineTo(x - w * 0.1f, y + h)
        corePath.close()

        spinePath.moveTo(x + w * 0.45f, y + h * 0.2f)
        spinePath.lineTo(x + w * 0.30f, y + h * 0.8f)
    }

    private fun addSegmentBar(
        corePath: Path,
        spinePath: Path,
        segId: Int,
        ox: Float,
        oy: Float,
        w: Float,
        h: Float,
        t: Float
    ) {
        val halfH = h * 0.5f
        val halfW = w * 0.5f
        val gap = h * 0.032f
        val r = t * 0.5f

        when (segId) {
            0 -> addHorizontalHex(corePath, spinePath, ox + gap + r * 0.5f, ox + w - gap - r * 0.5f, oy + r, r)
            3 -> addHorizontalHex(corePath, spinePath, ox + gap + r * 0.5f, ox + w - gap - r * 0.5f, oy + halfH, r)
            6 -> addHorizontalHex(corePath, spinePath, ox + gap + r * 0.5f, ox + w - gap - r * 0.5f, oy + h - r, r)

            1 -> addVerticalHex(corePath, spinePath, ox + r, oy + gap + r * 0.5f, oy + halfH - gap, r)
            2 -> addVerticalHex(corePath, spinePath, ox + w - r, oy + gap + r * 0.5f, oy + halfH - gap, r)
            4 -> addVerticalHex(corePath, spinePath, ox + r, oy + halfH + gap, oy + h - gap - r * 0.5f, r)
            5 -> addVerticalHex(corePath, spinePath, ox + w - r, oy + halfH + gap, oy + h - gap - r * 0.5f, r)

            7 -> addVerticalHex(corePath, spinePath, ox + halfW, oy + gap + r, oy + halfH - gap * 0.5f, r)
            8 -> addVerticalHex(corePath, spinePath, ox + halfW, oy + halfH + gap * 0.5f, oy + h - gap - r, r)

            9 -> addDiagonalBar(corePath, spinePath, ox + r * 1.4f, oy + r * 1.4f, ox + halfW, oy + halfH, r * 0.85f)
            10 -> addDiagonalBar(corePath, spinePath, ox + w - r * 1.4f, oy + r * 1.4f, ox + halfW, oy + halfH, r * 0.85f)
            11 -> addDiagonalBar(corePath, spinePath, ox + halfW, oy + halfH, ox + w - r * 1.2f, oy + h - r * 1.2f, r * 0.85f)
        }
    }

    private fun addHorizontalHex(
        corePath: Path,
        spinePath: Path,
        x1: Float,
        x2: Float,
        cy: Float,
        r: Float
    ) {
        if (x2 <= x1) return
        corePath.moveTo(x1, cy)
        corePath.lineTo(x1 + r, cy - r)
        corePath.lineTo(x2 - r, cy - r)
        corePath.lineTo(x2, cy)
        corePath.lineTo(x2 - r, cy + r)
        corePath.lineTo(x1 + r, cy + r)
        corePath.close()

        spinePath.moveTo(x1 + r, cy)
        spinePath.lineTo(x2 - r, cy)
    }

    private fun addVerticalHex(
        corePath: Path,
        spinePath: Path,
        cx: Float,
        y1: Float,
        y2: Float,
        r: Float
    ) {
        if (y2 <= y1) return
        corePath.moveTo(cx, y1)
        corePath.lineTo(cx + r, y1 + r)
        corePath.lineTo(cx + r, y2 - r)
        corePath.lineTo(cx, y2)
        corePath.lineTo(cx - r, y2 - r)
        corePath.lineTo(cx - r, y1 + r)
        corePath.close()

        spinePath.moveTo(cx, y1 + r)
        spinePath.lineTo(cx, y2 - r)
    }

    private fun addDiagonalBar(
        corePath: Path,
        spinePath: Path,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        r: Float
    ) {
        corePath.moveTo(x1 - r, y1)
        corePath.lineTo(x1 + r, y1)
        corePath.lineTo(x2 + r, y2)
        corePath.lineTo(x2 - r, y2)
        corePath.close()

        spinePath.moveTo(x1, y1)
        spinePath.lineTo(x2, y2)
    }

    private fun applyAlpha(colorInt: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
        return (colorInt and 0x00FFFFFF) or (a shl 24)
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(c1) * inv + Color.red(c2) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(c1) * inv + Color.green(c2) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }
}
