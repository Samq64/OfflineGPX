package dev.samuelq.gpx.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 2 keeps each track's stats and bounds; a 1 row gets defaults and is summarised at launch.
 * 3 adds the category, null for rows from before.
 */
@Database(
    entities = [TrackEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class GpxDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        fun create(context: Context): GpxDatabase =
            Room.databaseBuilder(context.applicationContext, GpxDatabase::class.java, "gpx.db")
                .build()
    }
}
