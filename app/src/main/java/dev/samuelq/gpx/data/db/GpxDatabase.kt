package dev.samuelq.gpx.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TrackEntity::class], version = 1, exportSchema = true)
abstract class GpxDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        fun create(context: Context): GpxDatabase =
            Room.databaseBuilder(context.applicationContext, GpxDatabase::class.java, "gpx.db")
                .build()
    }
}
