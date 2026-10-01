package dev.samuelq.gpx.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 2 keeps each track's stats and bounds; a 1 row gets defaults and is summarised at launch. */
@Database(
    entities = [TrackEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class GpxDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        fun create(context: Context): GpxDatabase =
            Room.databaseBuilder(context.applicationContext, GpxDatabase::class.java, "gpx.db")
                .build()
    }
}
