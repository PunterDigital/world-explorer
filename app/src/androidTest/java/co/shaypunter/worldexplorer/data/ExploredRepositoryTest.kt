package co.shaypunter.worldexplorer.data

import android.os.SystemClock
import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.shaypunter.worldexplorer.location.GapFiller
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploredRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ExploredRepository

    /** Every statement Room runs, so tests can count full-table reads and transactions. */
    private val queries: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .setQueryCallback({ sql, _ -> queries += sql }, { it.run() })
            .build()
        repository = ExploredRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun firstPointIsRecorded() = runTest {
        assertTrue(repository.recordIfNew(51.5, -0.12, 200f, 1L))
        assertEquals(1, db.exploredPointDao().getAll().size)
    }

    @Test
    fun pointInsideDedupRadiusIsSkipped() = runTest {
        repository.recordIfNew(51.5, -0.12, 200f, 1L)
        // ~55 m north: inside the default half-radius (100 m) dedup box.
        assertFalse(repository.recordIfNew(51.5005, -0.12, 200f, 2L))
        assertEquals(1, db.exploredPointDao().getAll().size)
    }

    @Test
    fun pointOutsideDedupRadiusIsRecorded() = runTest {
        repository.recordIfNew(51.5, -0.12, 200f, 1L)
        // ~220 m north: outside the dedup box.
        assertTrue(repository.recordIfNew(51.502, -0.12, 200f, 2L))
        assertEquals(2, db.exploredPointDao().getAll().size)
    }

    @Test
    fun tighterDedupRadiusAllowsCloserPoints() = runTest {
        repository.recordIfNew(51.5, -0.12, 200f, 1L, dedupRadiusMeters = 20.0)
        // ~55 m north: outside a 20 m dedup box (the foreground setting).
        assertTrue(repository.recordIfNew(51.5005, -0.12, 200f, 2L, dedupRadiusMeters = 20.0))
    }

    @Test
    fun pointJustAcrossTheAntimeridianIsSkipped() = runTest {
        repository.recordIfNew(0.0, 179.9999, 200f, 1L)
        // ~22 m east, on the other side of ±180°.
        assertFalse(repository.recordIfNew(0.0, -179.9999, 200f, 2L))
        assertEquals(1, db.exploredPointDao().getAll().size)
    }

    @Test
    fun pointOnTheFarSideOfThePoleIsSkipped() = runTest {
        repository.recordIfNew(89.9999, 0.0, 200f, 1L)
        // ~22 m away across the North Pole.
        assertFalse(repository.recordIfNew(89.9999, 180.0 - 1e-9, 200f, 2L))
        assertEquals(1, db.exploredPointDao().getAll().size)
    }

    @Test
    fun smoothTrailWritesBackOnceAndAdvancesTheWatermark() = runTest {
        val dao = db.exploredPointDao()
        // East every ~50 m with an ~8 m bump on the middle point, 1 s apart.
        for (i in 0..6) {
            val north = if (i == 3) 8.0 / 111_320.0 else 0.0
            dao.insert(ExploredPoint(latitude = 51.5 + north, longitude = -0.12 + i * 0.00072,
                radiusMeters = 200f, timestamp = i * 1_000L))
        }
        assertEquals(7L, repository.maxId())

        val watermark = repository.smoothTrail(smoothedUpToId = 0)
        assertEquals(5L, watermark)
        assertEquals(51.5, dao.getAll()[3].latitude, 1e-6)

        val before = dao.getAll()
        assertEquals(watermark, repository.smoothTrail(watermark))
        assertEquals(before, dao.getAll())
    }

    @Test
    fun smoothingWindowIsTheNewPointsAndTheirNeighbours() = runTest {
        val dao = db.exploredPointDao()
        seedTrail(20)
        val ids = dao.getAll().map { it.id }
        val context = TrailSmoother.WINDOW_RADIUS
        for (watermark in 0L..ids.last() + 1) {
            // TrailSmootherTest shows smoothing this window equals smoothing everything.
            val before = ids.count { it <= watermark }
            val expected = ids.drop((before - context).coerceAtLeast(0))
            assertEquals("watermark $watermark", expected, dao.getSmoothingWindow(watermark, context).map { it.id })
        }
    }

    @Test
    fun smoothingReadsOnlyTheNewPointsAndNeverMovesOlderOnes(): Unit = runBlocking {
        val dao = db.exploredPointDao()
        // Every third point is a GPS bump the smoother would flatten.
        seedTrail(10_000, zigzagMeters = 8.0)
        val before = dao.getAll()
        val watermark = before[9_700].id
        queries.clear()

        repository.smoothTrail(watermark)
        val read = statements().map { it.trim() }.filter { it.startsWith("SELECT") && "explored_points" in it }

        val after = dao.getAll()
        assertEquals(before.filter { it.id <= watermark }, after.filter { it.id <= watermark })
        assertTrue("nothing was smoothed", before.zip(after).any { (b, a) -> b != a })
        assertEquals(read.joinToString(" | "), listOf(true), read.map { "id >= COALESCE" in it })
    }

    @Test
    fun aFiveKilometreGapFillIsOneTransaction(): Unit = runBlocking {
        // A 5 km straight-line gap: GapFiller gives 33 points in between.
        val east = 5_000.0 / (111_320.0 * Math.cos(Math.toRadians(51.5)))
        val gap = GapFiller.between(51.5, -0.12, 0L, 51.5, -0.12 + east, 60_000L)
        assertEquals(33, gap.size)
        val positions = gap.map { it.latitude to it.longitude } + (51.5 to -0.12 + east)

        val invalidations = AtomicInteger()
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("explored_points")) {
            override fun onInvalidated(tables: Set<String>) {
                invalidations.incrementAndGet()
            }
        })
        queries.clear()

        assertEquals(34, repository.recordAllIfNew(positions, 200f, 60_000L))

        // Invalidations are sent after the transaction ends, on Room's executor.
        waitFor { invalidations.get() > 0 }
        Thread.sleep(1_000)
        assertEquals(1, invalidations.get())
        val begins = topLevelTransactions()
        Log.i(TAG, "5 km gap fill: ${invalidations.get()} invalidation(s), $begins transaction(s), ${statements().size} statements")
        assertEquals(statements().joinToString(" | "), 1, begins)
        assertEquals(34, db.exploredPointDao().getAll().size)
    }

    @Test
    fun gapFillStillDedupsAgainstItsOwnPoints() = runTest {
        // Two positions 20 m apart: the second sits in the first's dedup box.
        val inserted = repository.recordAllIfNew(listOf(51.5 to -0.12, 51.50018 to -0.12), 200f, 1L)
        assertEquals(1, inserted)
    }

    /**
     * App open with 10,000 points (#5676): smoothing plus the point list used
     * to read the table three times, and every new point re-read all of it.
     */
    @Test
    fun appOpenReadsTheTableOnceAndNewPointsAreAppended(): Unit = runBlocking {
        val dao = db.exploredPointDao()
        seedTrail(10_000, zigzagMeters = 8.0)
        val watermark = dao.getAll()[9_700].id
        queries.clear()

        // What MainViewModel does: smooth the new points, then feed the list.
        val start = SystemClock.elapsedRealtimeNanos()
        repository.smoothTrail(watermark)
        val feed = repository.pointsFeed()
        val lists = Channel<List<ExploredPoint>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { feed.flow().collect { lists.send(it) } }
        val first = withTimeout(30_000) { lists.receive() }
        val openMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        assertEquals(10_000, first.size)

        // One new point.
        val t0 = SystemClock.elapsedRealtimeNanos()
        assertTrue(repository.recordIfNew(10.0, 10.0, 200f, 99L))
        val second = receiveUntil(lists) { it.size == 10_001 }
        val insertMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        assertEquals(first, second.subList(0, 10_000))

        // A gap fill: one more list, with all of its points.
        val east = 5_000.0 / (111_320.0 * Math.cos(Math.toRadians(20.0)))
        val positions = GapFiller.between(20.0, 20.0, 0L, 20.0, 20.0 + east, 60_000L)
            .map { it.latitude to it.longitude } + (20.0 to 20.0 + east)
        repository.recordAllIfNew(positions, 200f, 60_000L)
        val third = receiveUntil(lists) { it.size == 10_035 }
        collector.cancel()

        // The screen coming back carries on from the list it has.
        val again = launch(Dispatchers.IO) { feed.flow().collect { lists.send(it) } }
        assertEquals(third, withTimeout(30_000) { lists.receive() })
        again.cancel()

        val fullReads = statements().count { it.trim() == "SELECT * FROM explored_points ORDER BY id ASC" }
        Log.i(TAG, "10k points: app open ${"%.1f".format(openMs)} ms, $fullReads full-table read(s); " +
            "new point on screen after ${"%.1f".format(insertMs)} ms")
        assertEquals(1, fullReads)
    }

    @Test
    fun feedReadsEverythingAgainWhenRowsGoAway(): Unit = runBlocking {
        seedTrail(5)
        val lists = Channel<List<ExploredPoint>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { repository.pointsFeed().flow().collect { lists.send(it) } }
        assertEquals(5, withTimeout(30_000) { lists.receive() }.size)
        db.clearAllTables()
        receiveUntil(lists) { it.isEmpty() }
        repository.recordIfNew(1.0, 1.0, 200f, 1L)
        receiveUntil(lists) { it.size == 1 }
        collector.cancel()
    }

    // --- helpers -------------------------------------------------------------

    /** A trail heading east, ~50 m and 1 s apart, every third point bumped north, in one insert. */
    private suspend fun seedTrail(count: Int, zigzagMeters: Double = 0.0) {
        db.exploredPointDao().insertAll(
            (0 until count).map { i ->
                val north = if (i % 3 == 1) zigzagMeters / 111_320.0 else 0.0
                ExploredPoint(
                    latitude = 51.5 + north,
                    longitude = -0.12 + i * 0.00072,
                    radiusMeters = 200f,
                    timestamp = i * 1_000L
                )
            }
        )
    }

    /** A copy: Room logs from its own threads (the invalidation tracker) while a test reads. */
    private fun statements(): List<String> = synchronized(queries) { ArrayList(queries) }

    /**
     * Outermost transactions that touch explored_points: Room's own insert
     * transactions nest inside ours, and the invalidation tracker's refresh
     * (room_table_modification_log) runs in one of its own.
     */
    private fun topLevelTransactions(): Int {
        var depth = 0
        var count = 0
        var touches = false
        for (sql in statements()) {
            when {
                sql.startsWith("BEGIN") -> { if (depth == 0) touches = false; depth++ }
                sql.startsWith("END TRANSACTION") -> { depth--; if (depth == 0 && touches) count++ }
                "explored_points" in sql -> touches = true
            }
        }
        return count
    }

    private suspend fun receiveUntil(
        lists: Channel<List<ExploredPoint>>,
        condition: (List<ExploredPoint>) -> Boolean
    ): List<ExploredPoint> = withTimeout(30_000) {
        var list = lists.receive()
        while (!condition(list)) list = lists.receive()
        list
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        assertTrue(condition())
    }

    private companion object {
        const val TAG = "ExploredRepositoryTest"
    }
}
