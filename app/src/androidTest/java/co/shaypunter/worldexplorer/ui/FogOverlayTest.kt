package co.shaypunter.worldexplorer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderNode
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.shaypunter.worldexplorer.assumeEmulator
import co.shaypunter.worldexplorer.data.ExploredPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * The fog overlay with 10,000 points (#5674): it must look like the old one
 * ([LegacyFogOverlay]), draw faster, and allocate nothing per frame.
 * Numbers go to logcat (tag FogOverlayTest) for the PR.
 */
@RunWith(AndroidJUnit4::class)
class FogOverlayTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val points = trail(10_000)
    private lateinit var mapView: MapView

    @Before
    fun setUp() {
        assumeEmulator()
        instrumentation.runOnMainSync {
            mapView = MapView(ApplicationProvider.getApplicationContext()).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                isTilesScaledToDpi = true
                setUseDataConnection(false)
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
    }

    @Test
    fun looksLikeTheOldFog() {
        val legacy = LegacyFogOverlay().apply { setPoints(points) }
        val fog = FogOverlay().apply { setPoints(points) }
        val problems = mutableListOf<String>()
        for (zoom in ZOOMS) {
            val before = render(legacy, zoom)
            val after = render(fog, zoom)
            save(before, "fog-before-z${zoom.toInt()}.png")
            save(after, "fog-after-z${zoom.toInt()}.png")
            val (different, meanDiff) = compare(before, after)
            val share = different.toDouble() / (WIDTH * HEIGHT)
            Log.i(TAG, "zoom $zoom: ${"%.3f".format(share * 100)} % of pixels differ by > $TOLERANCE, " +
                "mean difference ${"%.2f".format(meanDiff)} / 255")
            if (share >= 0.005) problems += "zoom $zoom: ${share * 100} % of pixels differ"
            if (clearedShare(after) == 0.0) problems += "zoom $zoom: no explored area shows"
        }
        assertTrue(problems.joinToString(), problems.isEmpty())
    }

    @Test
    fun drawsFasterThanTheOldFogAndAllocatesNothing() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val legacy = LegacyFogOverlay().apply { setPoints(points) }
        val fog = FogOverlay().apply { setPoints(points) }
        for (zoom in ZOOMS) {
            val old = time(legacy, zoom)
            val new = time(fog, zoom)
            Log.i(TAG, "zoom $zoom, 10k points: record ${ms(old.recordNs)} -> ${ms(new.recordNs)} ms/frame, " +
                "software raster ${ms(old.rasterNs)} -> ${ms(new.rasterNs)} ms/frame, " +
                "allocations ${old.allocations} -> ${new.allocations} per frame")
            assertEquals("zoom $zoom: allocations per frame", 0L, new.allocations)
            assertTrue("zoom $zoom: no faster to record", new.recordNs < old.recordNs)
        }
    }

    // --- helpers -------------------------------------------------------------

    private class Timing(val recordNs: Long, val rasterNs: Long, val allocations: Long)

    private fun time(overlay: Overlay, zoom: Double): Timing {
        moveTo(zoom)
        val node = RenderNode("fog").apply { setPosition(0, 0, WIDTH, HEIGHT) }
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var record = LongArray(FRAMES)
        var raster = LongArray(FRAMES)
        var allocations = 0L
        instrumentation.runOnMainSync {
            repeat(WARM_UP) {
                node.beginRecording().also { overlay.draw(it, mapView, false) }
                node.endRecording()
            }
            @Suppress("DEPRECATION") // still counts on ART
            Debug.startAllocCounting()
            for (f in 0 until FRAMES) {
                val recording = node.beginRecording()
                @Suppress("DEPRECATION")
                Debug.resetThreadAllocCount()
                val t0 = SystemClock.elapsedRealtimeNanos()
                overlay.draw(recording, mapView, false)
                record[f] = SystemClock.elapsedRealtimeNanos() - t0
                @Suppress("DEPRECATION")
                allocations = maxOf(allocations, Debug.getThreadAllocCount().toLong())
                node.endRecording()
            }
            @Suppress("DEPRECATION")
            Debug.stopAllocCounting()
            for (f in 0 until FRAMES) {
                bitmap.eraseColor(BACKGROUND)
                val t0 = SystemClock.elapsedRealtimeNanos()
                overlay.draw(canvas, mapView, false)
                raster[f] = SystemClock.elapsedRealtimeNanos() - t0
            }
        }
        node.discardDisplayList()
        bitmap.recycle()
        record.sort()
        raster.sort()
        return Timing(record[FRAMES / 2], raster[FRAMES / 2], allocations)
    }

    private fun render(overlay: Overlay, zoom: Double): Bitmap {
        moveTo(zoom)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(BACKGROUND)
        instrumentation.runOnMainSync { overlay.draw(Canvas(bitmap), mapView, false) }
        return bitmap
    }

    private fun moveTo(zoom: Double) {
        instrumentation.runOnMainSync {
            mapView.controller.setZoom(zoom)
            mapView.controller.setCenter(GeoPoint(CENTER_LAT, CENTER_LON))
        }
    }

    /** Pixels whose colour differs by more than [TOLERANCE] in any channel, and the mean difference. */
    private fun compare(a: Bitmap, b: Bitmap): Pair<Int, Double> {
        val pa = IntArray(WIDTH * HEIGHT).also { a.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        val pb = IntArray(WIDTH * HEIGHT).also { b.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        var different = 0
        var sum = 0L
        for (i in pa.indices) {
            val d = maxOf(
                abs(Color.red(pa[i]) - Color.red(pb[i])),
                abs(Color.green(pa[i]) - Color.green(pb[i])),
                abs(Color.blue(pa[i]) - Color.blue(pb[i]))
            )
            if (d > TOLERANCE) different++
            sum += d
        }
        return different to sum.toDouble() / pa.size
    }

    /** Share of pixels where the fog is (mostly) cut away. */
    private fun clearedShare(bitmap: Bitmap): Double {
        val px = IntArray(WIDTH * HEIGHT).also { bitmap.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        return px.count { Color.green(it) > Color.green(BACKGROUND) / 2 }.toDouble() / px.size
    }

    /** Into the folder AGP copies back from the device, when there is one. */
    private fun save(bitmap: Bitmap, name: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        runCatching {
            File(dir).mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "couldn't save $name", it) }
    }

    private fun ms(ns: Long) = "%.2f".format(ns / 1e6)

    /** A wandering walk around London, ~50-150 m between points. */
    private fun trail(n: Int): List<ExploredPoint> {
        val random = Random(5674)
        var lat = CENTER_LAT
        var lon = CENTER_LON
        var bearing = 0.0
        return (1..n).map { id ->
            bearing += (random.nextDouble() - 0.5) * 0.8
            val step = 50.0 + random.nextDouble() * 100.0
            lat += step * cos(bearing) / 111_320.0
            lon += step * sin(bearing) / (111_320.0 * cos(Math.toRadians(lat)))
            // Keep it within ~6 km of the centre so zoom 12 shows all of it.
            if (abs(lat - CENTER_LAT) > 0.05 || abs(lon - CENTER_LON) > 0.08) bearing += Math.PI
            ExploredPoint(id = id.toLong(), latitude = lat, longitude = lon, radiusMeters = 200f, timestamp = id * 1_000L)
        }
    }

    private companion object {
        const val TAG = "FogOverlayTest"
        const val WIDTH = 1080
        const val HEIGHT = 2400
        const val CENTER_LAT = 51.5007
        const val CENTER_LON = -0.1246
        val ZOOMS = listOf(16.0, 12.0, 5.0)
        const val WARM_UP = 5
        const val FRAMES = 25
        const val TOLERANCE = 48
        val BACKGROUND = Color.rgb(120, 160, 200)
    }
}
