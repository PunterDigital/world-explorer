package co.shaypunter.worldexplorer.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import co.shaypunter.worldexplorer.data.ExploredPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/**
 * Draws a translucent dark veil over the entire map, then "cuts out" a soft
 * circle around every explored point. The cutout uses a radial gradient so the
 * edge fades rather than producing a hard line.
 *
 * Only points whose centre falls within an expanded view bounding box are
 * iterated, so performance stays flat regardless of total DB size.
 */
class FogOverlay : Overlay() {

    @Volatile
    private var points: List<ExploredPoint> = emptyList()

    private val fogPaint = Paint().apply {
        color = Color.argb(FOG_ALPHA, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private val scratchPoint = android.graphics.Point()
    private val scratchBounds = Rect()

    fun setPoints(newPoints: List<ExploredPoint>) {
        points = newPoints
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        canvas.getClipBounds(scratchBounds)
        val width = mapView.width
        val height = mapView.height
        if (width == 0 || height == 0) return

        val projection = mapView.projection
        // Web-Mercator ground resolution. osmdroid's Projection doesn't expose
        // a metres-per-pixel accessor across versions, so compute it directly.
        val zoom = mapView.zoomLevelDouble
        val centerLat = (projection.boundingBox.centerLatitude)
        val metersPerPixel = (156543.03392 * cos(Math.toRadians(centerLat)) /
                2.0.pow(zoom)).toFloat()
        if (metersPerPixel <= 0f) return

        val snapshot = points
        if (snapshot.isEmpty()) {
            // Fully fogged map.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fogPaint)
            return
        }

        // Compute a rough lat/lon bounding box for the visible map so we can
        // cheaply skip out-of-view points without invoking projection per point.
        val topLeft = projection.fromPixels(0, 0) as GeoPoint
        val bottomRight = projection.fromPixels(width, height) as GeoPoint
        val minLat = minOf(topLeft.latitude, bottomRight.latitude)
        val maxLat = maxOf(topLeft.latitude, bottomRight.latitude)
        val minLon = minOf(topLeft.longitude, bottomRight.longitude)
        val maxLon = maxOf(topLeft.longitude, bottomRight.longitude)
        // Pad bounds by the largest possible radius so circles whose centres
        // are off-screen still get drawn.
        val maxRadiusDeg = (snapshot.maxOf { it.radiusMeters } / 111_320f).toDouble()

        val layerId = canvas.saveLayer(
            0f, 0f, width.toFloat(), height.toFloat(), null
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fogPaint)

        for (point in snapshot) {
            if (point.latitude < minLat - maxRadiusDeg ||
                point.latitude > maxLat + maxRadiusDeg ||
                point.longitude < minLon - maxRadiusDeg ||
                point.longitude > maxLon + maxRadiusDeg
            ) continue

            projection.toPixels(GeoPoint(point.latitude, point.longitude), scratchPoint)
            val radiusPx = max(point.radiusMeters / metersPerPixel, 1f)

            // Quick reject if circle is fully outside the screen.
            if (scratchPoint.x + radiusPx < 0 || scratchPoint.x - radiusPx > width) continue
            if (scratchPoint.y + radiusPx < 0 || scratchPoint.y - radiusPx > height) continue

            cutoutPaint.shader = RadialGradient(
                scratchPoint.x.toFloat(),
                scratchPoint.y.toFloat(),
                radiusPx,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(
                scratchPoint.x.toFloat(),
                scratchPoint.y.toFloat(),
                radiusPx,
                cutoutPaint
            )
        }

        canvas.restoreToCount(layerId)
    }

    companion object {
        private const val FOG_ALPHA = 250
    }
}
