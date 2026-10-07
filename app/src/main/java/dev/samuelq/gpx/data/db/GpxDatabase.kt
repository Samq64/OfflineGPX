package dev.samuelq.gpx.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 2 keeps each track's stats and bounds; a 1 row gets defaults and is summarised at launch.
 * 3 adds the category, null for rows from before. 4 changes no columns: it re-summarises every
 * row, as routes beside a track no longer count among its points.
 */
@Database(
    entities = [TrackEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class GpxDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        fun create(context: Context): GpxDatabase =
            Room.databaseBuilder(context.applicationContext, GpxDatabase::class.java, "gpx.db")
                .addMigrations(RESUMMARISE)
                .build()

        /** Marks every row for summariseOlderRows, which keeps what the user set. */
        internal val RESUMMARISE = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("UPDATE tracks SET pointCount = -1")
        }
    }
}
