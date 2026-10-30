package com.chronoccd.studio.isp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

data class DemoScenePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String
)

val BuiltInDemoScenes = listOf(
    DemoScenePreset(
        id = "nightlife_flash",
        title = "Y2K Nightclub Portrait",
        subtitle = "Tests Xenon strobe falloff, skin tones & vertical CCD smear",
        badge = "FLASH + SMEAR"
    ),
    DemoScenePreset(
        id = "sunny_skatepark",
        title = "Sunny Skatepark '04",
        subtitle = "Tests sky full-well clipping, foliage greens & USM edge halos",
        badge = "DAYLIGHT CCD"
    ),
    DemoScenePreset(
        id = "cyber_arcade",
        title = "Shibuya Cyber Arcade",
        subtitle = "Tests 4:2:0 red/blue chroma bleed, CA fringing & point lights",
        badge = "NEON + 4:2:0"
    ),
    DemoScenePreset(
        id = "sunset_boardwalk",
        title = "Golden Hour Boardwalk",
        subtitle = "Tests warm highlight roll-off, lens vignette & water reflections",
        badge = "GOLDEN HOUR"
    )
)

object DemoSceneGenerator {

    fun generateSceneBitmap(
        sceneId: String,
        width: Int = 960,
        height: Int = 720
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        when (sceneId) {
            "sunny_skatepark" -> drawSunnySkatepark(canvas, width.toFloat(), height.toFloat())
            "cyber_arcade" -> drawCyberArcade(canvas, width.toFloat(), height.toFloat())
            "sunset_boardwalk" -> drawSunsetBoardwalk(canvas, width.toFloat(), height.toFloat())
            else -> drawNightlifeFlash(canvas, width.toFloat(), height.toFloat())
        }
        return bmp
    }

    private fun drawNightlifeFlash(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Deep club background gradient
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF0A0D1C.toInt(), 0xFF1B102E.toInt(), 0xFF0E1B2A.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Background bokeh & bright point ceiling spotlights (triggers vertical CCD smear!)
        val spots = listOf(
            Triple(w * 0.18f, h * 0.16f, 0xFFFF2E93.toInt()),
            Triple(w * 0.82f, h * 0.14f, 0xFF00F0FF.toInt()),
            Triple(w * 0.34f, h * 0.22f, 0xFFFFF4D0.toInt()),
            Triple(w * 0.68f, h * 0.20f, 0xFFFFF9E6.toInt()),
            Triple(w * 0.90f, h * 0.36f, 0xFFFF7700.toInt())
        )
        for ((sx, sy, col) in spots) {
            paint.shader = RadialGradient(
                sx, sy, w * 0.12f,
                intArrayOf(0xFFFFFFFF.toInt(), col, 0x00000000),
                floatArrayOf(0f, 0.28f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(sx, sy, w * 0.12f, paint)
            paint.shader = null
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(sx, sy, w * 0.016f, paint)
        }

        // Retro metallic tiled wall grid in background
        paint.color = 0x226688AA
        paint.strokeWidth = 2f
        val step = w / 12f
        for (i in 1..11) {
            canvas.drawLine(i * step, 0f, i * step, h, paint)
            canvas.drawLine(0f, i * step, w, i * step, paint)
        }

        // Foreground Y2K metallic jacket shoulders
        val jacketPath = Path().apply {
            moveTo(w * 0.20f, h)
            cubicTo(w * 0.22f, h * 0.66f, w * 0.36f, h * 0.62f, w * 0.50f, h * 0.62f)
            cubicTo(w * 0.64f, h * 0.62f, w * 0.78f, h * 0.66f, w * 0.80f, h)
            close()
        }
        paint.shader = LinearGradient(
            w * 0.25f, h * 0.62f, w * 0.75f, h,
            intArrayOf(0xFFDCE6F2.toInt(), 0xFF7B8FA8.toInt(), 0xFF283242.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(jacketPath, paint)
        paint.shader = null

        // Red graphic tee collar
        paint.color = 0xFFE02838.toInt()
        canvas.drawArc(RectF(w * 0.39f, h * 0.58f, w * 0.61f, h * 0.78f), 0f, 180f, true, paint)

        // Neck & Face with realistic warm skin tones + direct flash specular highlight
        paint.color = 0xFFD99875.toInt()
        canvas.drawRoundRect(RectF(w * 0.45f, h * 0.50f, w * 0.55f, h * 0.66f), 20f, 20f, paint)

        paint.shader = RadialGradient(
            w * 0.48f, h * 0.38f, w * 0.16f,
            intArrayOf(0xFFFFDFC8.toInt(), 0xFFE6A582.toInt(), 0xFFB87454.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(RectF(w * 0.37f, h * 0.24f, w * 0.63f, h * 0.57f), paint)
        paint.shader = null

        // Forehead & nose bridge specular flash reflection
        paint.shader = RadialGradient(
            w * 0.49f, h * 0.32f, w * 0.06f,
            intArrayOf(0xCCFFFFFF.toInt(), 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.49f, h * 0.32f, w * 0.06f, paint)
        paint.shader = null

        // Y2K tinted rimless sunglasses
        paint.color = 0xBB1A3868.toInt()
        canvas.drawRoundRect(RectF(w * 0.39f, h * 0.35f, w * 0.49f, h * 0.42f), 14f, 14f, paint)
        canvas.drawRoundRect(RectF(w * 0.51f, h * 0.35f, w * 0.61f, h * 0.42f), 14f, 14f, paint)

        paint.color = 0xFFE8F4FF.toInt()
        paint.strokeWidth = 3.5f
        canvas.drawLine(w * 0.49f, h * 0.38f, w * 0.51f, h * 0.38f, paint)
        // Specular strobe glint on sunglasses lens
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(w * 0.42f, h * 0.375f, w * 0.012f, paint)
        canvas.drawCircle(w * 0.54f, h * 0.375f, w * 0.012f, paint)

        // Spiky 2000s hair silhouette
        val hairPath = Path().apply {
            moveTo(w * 0.36f, h * 0.38f)
            lineTo(w * 0.34f, h * 0.25f)
            lineTo(w * 0.41f, h * 0.27f)
            lineTo(w * 0.44f, h * 0.19f)
            lineTo(w * 0.50f, h * 0.24f)
            lineTo(w * 0.56f, h * 0.18f)
            lineTo(w * 0.59f, h * 0.26f)
            lineTo(w * 0.66f, h * 0.24f)
            lineTo(w * 0.64f, h * 0.38f)
            cubicTo(w * 0.58f, h * 0.27f, w * 0.42f, h * 0.27f, w * 0.36f, h * 0.38f)
            close()
        }
        paint.color = 0xFF1A1412.toInt()
        canvas.drawPath(hairPath, paint)
    }

    private fun drawSunnySkatepark(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Bright 2004 afternoon sky gradient
        paint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.58f,
            intArrayOf(0xFF1C78E8.toInt(), 0xFF66B6FF.toInt(), 0xFFDDF2FF.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.58f, paint)
        paint.shader = null

        // Intense sun disc (tests highlight full-well clip & vertical CCD smear)
        paint.shader = RadialGradient(
            w * 0.78f, h * 0.18f, w * 0.15f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFF9D6.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.78f, h * 0.18f, w * 0.15f, paint)
        paint.shader = null

        // Bright Cumulus Clouds
        paint.color = 0xFFF7FBFF.toInt()
        canvas.drawCircle(w * 0.22f, h * 0.26f, w * 0.08f, paint)
        canvas.drawCircle(w * 0.30f, h * 0.24f, w * 0.10f, paint)
        canvas.drawCircle(w * 0.38f, h * 0.27f, w * 0.07f, paint)

        // Lush Treeline (tests Fujifilm emerald green vs Canon warm green science)
        val treeColors = intArrayOf(0xFF1E5E2F.toInt(), 0xFF2E7D32.toInt(), 0xFF4CAF50.toInt())
        for (i in 0..9) {
            val tx = w * (0.06f + i * 0.10f)
            val ty = h * 0.54f
            paint.color = treeColors[i % 3]
            canvas.drawCircle(tx, ty - h * 0.04f, w * 0.075f, paint)
        }

        // Concrete Skate Bowl & Coping
        paint.shader = LinearGradient(
            0f, h * 0.54f, 0f, h,
            intArrayOf(0xFFD8D3C8.toInt(), 0xFFB8B2A6.toInt(), 0xFF827C72.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.54f, w, h, paint)
        paint.shader = null

        // Curved Bowl Shadow
        val bowlPath = Path().apply {
            moveTo(0f, h * 0.66f)
            quadTo(w * 0.5f, h * 0.92f, w, h * 0.62f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        paint.color = 0xFF6B665E.toInt()
        canvas.drawPath(bowlPath, paint)

        // High-contrast Red & Blue Skateboard Deck on coping (tests 4:2:0 chroma & CA)
        paint.color = 0xFFE52420.toInt()
        canvas.drawRoundRect(RectF(w * 0.34f, h * 0.60f, w * 0.64f, h * 0.66f), 28f, 28f, paint)
        paint.color = 0xFFFFD600.toInt()
        canvas.drawCircle(w * 0.40f, h * 0.68f, w * 0.022f, paint)
        canvas.drawCircle(w * 0.58f, h * 0.68f, w * 0.022f, paint)
    }

    private fun drawCyberArcade(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Dark cyber alley background
        paint.color = 0xFF090B14.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        // High-contrast Neon Signboards (saturated reds, cyans, magentas, yellows)
        val signs = listOf(
            RectF(w * 0.08f, h * 0.10f, w * 0.32f, h * 0.44f) to 0xFFFF1744.toInt(),
            RectF(w * 0.68f, h * 0.08f, w * 0.92f, h * 0.38f) to 0xFF00E5FF.toInt(),
            RectF(w * 0.14f, h * 0.50f, w * 0.36f, h * 0.72f) to 0xFFFFEA00.toInt(),
            RectF(w * 0.64f, h * 0.44f, w * 0.88f, h * 0.70f) to 0xFFD500F9.toInt()
        )
        for ((rect, col) in signs) {
            paint.color = col
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 8f
            canvas.drawRoundRect(rect, 16f, 16f, paint)

            paint.style = Paint.Style.FILL
            paint.color = (col and 0x00FFFFFF) or 0x44000000
            canvas.drawRoundRect(rect, 16f, 16f, paint)

            // Bright white neon tube bars inside
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawRect(rect.left + 20f, rect.centerY() - 8f, rect.right - 20f, rect.centerY() + 8f, paint)
        }

        // Wet Asphalt Neon Reflections at bottom
        paint.shader = LinearGradient(
            0f, h * 0.72f, 0f, h,
            intArrayOf(0xFF182238.toInt(), 0xFF0D1220.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.72f, w, h, paint)
        paint.shader = null

        // CRT Monitor Bank in Center
        paint.color = 0xFF263238.toInt()
        canvas.drawRoundRect(RectF(w * 0.39f, h * 0.28f, w * 0.61f, h * 0.74f), 18f, 18f, paint)
        paint.shader = RadialGradient(
            w * 0.50f, h * 0.46f, w * 0.11f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF40C4FF.toInt(), 0xFF01579B.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(w * 0.42f, h * 0.32f, w * 0.58f, h * 0.58f), 14f, 14f, paint)
        paint.shader = null
    }

    private fun drawSunsetBoardwalk(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Warm 2003 California Sunset Sky
        paint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.62f,
            intArrayOf(
                0xFF283593.toInt(),
                0xFFAD1457.toInt(),
                0xFFFF6F00.toInt(),
                0xFFFFEA00.toInt()
            ),
            floatArrayOf(0f, 0.38f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.62f, paint)
        paint.shader = null

        // Setting Sun on Horizon
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(w * 0.52f, h * 0.56f, w * 0.085f, paint)

        // Ocean Horizon & Sun Glitter Path
        paint.shader = LinearGradient(
            0f, h * 0.62f, 0f, h,
            intArrayOf(0xFF1A237E.toInt(), 0xFF0D47A1.toInt(), 0xFF001933.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.62f, w, h, paint)
        paint.shader = null

        // Specular Sun Shimmer Bars on Water (triggers CCD bloom & vertical smear)
        paint.color = 0xFFFFF59D.toInt()
        for (i in 0..8) {
            val sy = h * (0.64f + i * 0.035f)
            val sw = w * (0.14f - i * 0.01f)
            canvas.drawRoundRect(RectF(w * 0.52f - sw, sy, w * 0.52f + sw, sy + 7f), 4f, 4f, paint)
        }

        // Palm Tree Silhouettes
        paint.color = 0xFF120A1A.toInt()
        paint.strokeWidth = 14f
        canvas.drawLine(w * 0.18f, h, w * 0.24f, h * 0.24f, paint)
        canvas.drawLine(w * 0.82f, h, w * 0.76f, h * 0.30f, paint)
        for (angle in listOf(-50f, -20f, 15f, 45f)) {
            canvas.save()
            canvas.translate(w * 0.24f, h * 0.24f)
            canvas.rotate(angle)
            canvas.drawOval(RectF(-w * 0.10f, -10f, w * 0.10f, 10f), paint)
            canvas.restore()

            canvas.save()
            canvas.translate(w * 0.76f, h * 0.30f)
            canvas.rotate(angle)
            canvas.drawOval(RectF(-w * 0.09f, -9f, w * 0.09f, 9f), paint)
            canvas.restore()
        }
    }
}
