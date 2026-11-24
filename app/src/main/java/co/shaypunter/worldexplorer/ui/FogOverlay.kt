package co.shaypunter.worldexplorer.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import co.shaypunter.worldexplorer.data.ExploredPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Draws a translucent dark veil over the entire map, then "cuts out" a soft
 * circle around every explored point. The cutout uses a radial gradient so the
 * edge fades rather than producing a hard line.
 *
 * Per frame (#5674) it looks up only the points near the view in a
 * [FogIndex] (built off the main thread), and allocates nothing: one
 * prebuilt unit gradient is moved and scaled into place with the canvas
 * matrix. Zoomed out, where a circle is under [DOT_RADIUS_PX], each pixel-sized
 * cell of the index gives one round dot and all the dots go out in a single
 * drawPoints call, so the cost stays flat however long the history gets.
 *
 * Assumes the map isn't rotated (the app has no rotation gesture).
 */
class FogOverlay : Overlay() {

    @Volatile
    private var scene: FogScene = FogScene.EMPTY

    private val fogPaint = Paint().apply {
        color = Color.argb(FOG_ALPHA, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Keep the fully-cleared zone almost the full radius so adjacent discs
    // along a trail merge into one continuous ribbon instead of showing the
    // "beads-on-a-string" feather halos at their joins. The thin outer fade
    // still rounds the outer boundary of the overall shape. A unit circle at
    // the origin: each disc is drawn with the canvas translated and scaled.
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.95f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Dot positions for drawPoints; grows (rarely) and is reused. */
    private var dots = FloatArray(4096)
    private var dotCount = 0

    // Per-frame mapping from normalised Mercator to screen pixels.
    private var worldPx = 0.0
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var metersPerPixel = 0f
    private var viewWidth = 0
    private var viewHeight = 0

    /** Drawn from the next frame on. Build it off the main thread ([FogIndexBuilder]). */
    fun setScene(newScene: FogScene) {
        scene = newScene
    }

    /** Builds the index here and now: for tests and small lists. */
    fun setPoints(newPoints: List<ExploredPoint>) = setScene(FogScene.of(newPoints))

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val width = mapView.width
        val height = mapView.height
        if (width == 0 || height == 0) return

        val projection = mapView.projection
        // Web-Mercator ground resolution. osmdroid's Projection doesn't expose
        // a metres-per-pixel accessor across versions, so compute it directly.
        val zoom = mapView.zoomLevelDouble
        val centerLat = projection.boundingBox.centerLatitude
        metersPerPixel = (156543.03392 * cos(Math.toRadians(centerLat)) / 2.0.pow(zoom)).toFloat()
        if (metersPerPixel <= 0f) return

        val current = scene
        if (current.size == 0) {
            // Fully fogged map.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fogPaint)
            return
        }

        // Screen pixel of a Mercator position, the way osmdroid's
        // Projection.toPixels does it: floor(x * world size) plus the
        // projection's offset, which the map's centre gives us.
        worldPx = projection.worldMapSize
        if (worldPx <= 0.0) return
        val centerLon = projection.boundingBox.centerLongitude
        offsetX = projection.getLongPixelXFromLongitude(centerLon, false) -
            floor((centerLon + 180.0) / 360.0 * worldPx)
        offsetY = projection.getLongPixelYFromLatitude(centerLat, false) -
            floor(FogIndex.mercatorY(centerLat) * worldPx)
        viewWidth = width
        viewHeight = height

        // The view in Mercator, padded so circles centred off screen still show.
        val maxRadiusPx = max(current.maxRadiusMeters / metersPerPixel, 1f)
        val pad = (maxRadiusPx + 2.0) / worldPx
        val x0 = -offsetX / worldPx - pad
        val x1 = (width - offsetX) / worldPx + pad
        val y0 = (-offsetY / worldPx - pad).coerceAtLeast(0.0)
        val y1 = ((height - offsetY) / worldPx + pad).coerceAtMost(1.0)
        // A level whose cells are about the view's size: a handful of lookups.
        val span = max(x1 - x0, y1 - y0)
        val cellLevel = floor(-ln(span) / LN2).toInt().coerceIn(0, FogIndex.LEVELS)
        // Zoomed out, one dot per cell that's at most a pixel across.
        val dotLevel = if (maxRadiusPx < DOT_RADIUS_PX) {
            ceil(ln(worldPx) / LN2).toInt().coerceAtMost(FogIndex.LEVELS)
        } else {
            FogIndex.LEVELS
        }
        val asDots = maxRadiusPx < DOT_RADIUS_PX

        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fogPaint)
        dotCount = 0
        // The world repeats sideways when zoomed out: draw the copies in view too.
        val firstCopy = floor(x0).toInt()
        val lastCopy = floor(x1).toInt()
        for (copy in firstCopy..lastCopy) {
            val from = max(x0 - copy, 0.0)
            val to = minOf(x1 - copy, 1.0)
            drawIndex(canvas, current.base, copy.toDouble(), from, to, y0, y1, cellLevel, dotLevel, asDots)
            drawIndex(canvas, current.recent, copy.toDouble(), from, to, y0, y1, cellLevel, dotLevel, asDots)
        }
        if (dotCount > 0) {
            dotPaint.strokeWidth = 2f * DOT_EDGE * maxRadiusPx
            canvas.drawPoints(dots, 0, dotCount * 2, dotPaint)
        }
        canvas.restoreToCount(layerId)
    }

    private fun drawIndex(
        canvas: Canvas, index: FogIndex, copy: Double,
        x0: Double, x1: Double, y0: Double, y1: Double,
        cellLevel: Int, dotLevel: Int, asDots: Boolean
    ) {
        index.forEachIn(x0, x1, y0, y1, cellLevel, dotLevel) { i ->
            val sx = (offsetX + floor((index.x[i] + copy) * worldPx)).toFloat()
            val sy = (offsetY + floor(index.y[i] * worldPx)).toFloat()
            val radiusPx = max(index.radiusMeters[i] / metersPerPixel, 1f)
            // Quick reject if the circle is fully outside the screen.
            if (sx + radiusPx >= 0 && sx - radiusPx <= viewWidth &&
                sy + radiusPx >= 0 && sy - radiusPx <= viewHeight
            ) {
                if (asDots) addDot(sx, sy) else drawDisc(canvas, sx, sy, radiusPx)
            }
        }
    }

    private fun drawDisc(canvas: Canvas, sx: Float, sy: Float, radiusPx: Float) {
        val saved = canvas.save()
        canvas.translate(sx, sy)
        canvas.scale(radiusPx, radiusPx)
        canvas.drawCircle(0f, 0f, 1f, cutoutPaint)
        canvas.restoreToCount(saved)
    }

    private fun addDot(sx: Float, sy: Float) {
        if (dotCount * 2 + 2 > dots.size) dots = dots.copyOf(dots.size * 2)
        dots[dotCount * 2] = sx
        dots[dotCount * 2 + 1] = sy
        dotCount++
    }

    companion object {
        private const val FOG_ALPHA = 242
        /**
         * Below this radius a disc is drawn as a plain round dot: the
         * gradient's fade (the outer 5 %) is under a pixel there anyway.
         */
        const val DOT_RADIUS_PX = 12f

        /**
         * A disc's gradient is solid to 95 % of the radius and fades out by
         * 100 %: a plain dot this much of the radius clears the same area.
         */
        private const val DOT_EDGE = 0.975f
        private val LN2 = ln(2.0)
    }
}
