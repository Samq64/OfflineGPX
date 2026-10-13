package dev.samuelq.gpx.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.samuelq.gpx.targetContext
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GpxDatabase::class.java)

    @Test
    fun version1RowsKeepTheirStart() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                """INSERT INTO tracks (id, trackName, lastOpenedAtEpochMillis, visible, color, category,
                   startedAtEpochMillis, distanceMeters, totalSeconds,
                   southLatitude, westLongitude, northLatitude, eastLongitude)
                   VALUES (7, 'Ride', 2000, 1, 'Blue', 'Cycling', 1000, 1234.5, 600.0, 1.0, 2.0, 3.0, 4.0)""",
            )
        }
        // Validates the migrated schema against 2.json.
        helper.runMigrationsAndValidate(DB, 2, true).close()

        val database = Room.databaseBuilder(targetContext, GpxDatabase::class.java, DB).build()
        try {
            val row = runBlocking { database.trackDao().byId(7)!! }
            assertEquals("Ride", row.trackName)
            assertEquals(1000L, row.startedAtEpochMillis)
            assertEquals(600.0, row.totalSeconds)
            assertEquals("Cycling", row.category)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
