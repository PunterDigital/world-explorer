package co.shaypunter.worldexplorer.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.shaypunter.worldexplorer.assumeEmulator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds each exported schema version (app/schemas) and checks that the
 * current database opens it with [ALL_MIGRATIONS] and keeps the data. When an
 * entity changes without a version bump and migration, Room fails to validate
 * the old file here instead of crashing on users' phones.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java)

    @Before
    fun setUp() = assumeEmulator()

    @Test
    fun version1OpensWithTheCurrentDatabaseAndKeepsPoints() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.insert(
                "explored_points",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("latitude", 51.5007)
                    put("longitude", -0.1246)
                    put("radiusMeters", 200f)
                    put("timestamp", 1_700_000_000_000L)
                }
            )
        }

        val db = Room.databaseBuilder(instrumentation.targetContext, AppDatabase::class.java, TEST_DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            // Opening validates the migrated schema against the current entities.
            val points = runBlocking { db.exploredPointDao().getAll() }
            assertEquals(1, points.size)
            assertEquals(51.5007, points.single().latitude, 1e-9)
            assertEquals(200f, points.single().radiusMeters)
        } finally {
            db.close()
            instrumentation.targetContext.deleteDatabase(TEST_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
