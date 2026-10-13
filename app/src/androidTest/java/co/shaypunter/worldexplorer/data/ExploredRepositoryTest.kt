package co.shaypunter.worldexplorer.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repository = ExploredRepository(db.exploredPointDao())
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
}
