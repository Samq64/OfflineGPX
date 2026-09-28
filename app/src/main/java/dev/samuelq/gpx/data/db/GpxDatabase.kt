package dev.samuelq.gpx.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = GpxDatabase.DropUnreadColumns::class)],
)
abstract class GpxDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    /** Summary columns that were written on every save and never read back. */
    @DeleteColumn(tableName = "tracks", columnName = "source")
    @DeleteColumn(tableName = "tracks", columnName = "movingSeconds")
    @DeleteColumn(tableName = "tracks", columnName = "ascentMeters")
    @DeleteColumn(tableName = "tracks", columnName = "descentMeters")
    @DeleteColumn(tableName = "tracks", columnName = "pointCount")
    class DropUnreadColumns : AutoMigrationSpec

    /** Absolute track paths become relative to filesDir. */
    private object RelativeLocations : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE tracks SET location = substr(location, instr(location, '/files/') + 7) " +
                    "WHERE location LIKE '/%/files/%'"
            )
        }
    }

    companion object {
        fun create(context: Context): GpxDatabase =
            Room.databaseBuilder(context.applicationContext, GpxDatabase::class.java, "gpx.db")
                .addMigrations(RelativeLocations)
                .build()
    }
}
