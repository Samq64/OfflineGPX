package dev.samuelq.gpx.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.samuelq.gpx.targetContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GpxDatabase::class.java)

    @Test
    fun version1RowsKeepTheirDataAndAwaitSummary() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                """INSERT INTO tracks (id, location, displayName, trackName, startedAtEpochMillis,
                   lastOpenedAtEpochMillis, visible, colorIndex, distanceMeters, totalSeconds)
                   VALUES (7, 'imports/a.gpx', 'a.gpx', 'Ride', 1000, 2000, 0, 3, 1234.5, 600.0)""",
            )
        }

        // Validates the migrated schema against 2.json.
        helper.runMigrationsAndValidate(DB, 2, true).close()

        val database = Room.databaseBuilder(targetContext, GpxDatabase::class.java, DB).build()
        try {
            runBlocking {
                val dao = database.trackDao()
                val row = dao.byId(7)!!
                assertEquals("imports/a.gpx", row.location)
                assertEquals("Ride", row.trackName)
                assertEquals(1000L, row.startedAtEpochMillis)
                assertFalse(row.visible)
                assertEquals(3, row.colorIndex)
                assertEquals(1234.5, row.distanceMeters)
                assertEquals(600.0, row.totalSeconds)
                assertFalse(row.summarised)
                assertNull(row.bounds)
                assertEquals(0.0, row.summary.ascentMeters)
                assertEquals(listOf(7L), dao.unsummarised().map { it.id })
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun version2RowsComeUncategorised() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                """INSERT INTO tracks (id, location, displayName, trackName, startedAtEpochMillis,
                   lastOpenedAtEpochMillis, visible, colorIndex, pointCount, distanceMeters, totalSeconds)
                   VALUES (8, 'recordings/b.gpx', 'b.gpx', NULL, 1000, 2000, 1, 2, 5, 10.0, 60.0)""",
            )
        }
        // Validates the migrated schema against 3.json.
        helper.runMigrationsAndValidate(DB, 3, true).use { db ->
            db.query("SELECT category, pointCount FROM tracks WHERE id = 8").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals(5, cursor.getInt(1))
            }
        }
    }

    @Test
    fun emptyVersion1Migrates() {
        helper.createDatabase(DB, 1).close()
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT COUNT(*) FROM tracks").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
