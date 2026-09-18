package dev.samuelq.gpx.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** Stores [TrackSource] as its name, so a new variant does not renumber the old ones. */
class TrackSourceConverter {
    @TypeConverter
    fun toSource(value: String): TrackSource = TrackSource.valueOf(value)

    @TypeConverter
    fun fromSource(source: TrackSource): String = source.name
}

@Database(entities = [TrackEntity::class], version = 1, exportSchema = true)
@TypeConverters(TrackSourceConverter::class)
abstract class GpxDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        fun create(context: Context): GpxDatabase =
            Room.databaseBuilder(context.applicationContext, GpxDatabase::class.java, "gpx.db")
                .build()
    }
}
