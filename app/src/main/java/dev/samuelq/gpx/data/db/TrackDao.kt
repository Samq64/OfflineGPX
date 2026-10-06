package dev.samuelq.gpx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /** The app's only ordering; the map walks it backwards so the list's top is drawn on top. */
    @Query("SELECT * FROM tracks ORDER BY lastOpenedAtEpochMillis DESC")
    fun observeByRecent(): Flow<List<TrackEntity>>

    @Query("SELECT colorIndex, visible FROM tracks")
    suspend fun colorUsage(): List<ColorUse>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Upsert
    suspend fun upsert(track: TrackEntity): Long

    @Query("UPDATE tracks SET lastOpenedAtEpochMillis = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Update(entity = TrackEntity::class)
    suspend fun setSummary(summary: SummaryUpdate)

    /** Rows from before the summary was kept, still to be read. */
    @Query("SELECT * FROM tracks WHERE pointCount < 0")
    suspend fun unsummarised(): List<TrackEntity>

    @Query("UPDATE tracks SET trackName = :name WHERE id = :id")
    suspend fun setTrackName(id: Long, name: String?)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM tracks WHERE displayName = :displayName")
    suspend fun byDisplayName(displayName: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrackEntity>

    @Query("UPDATE tracks SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    @Query("UPDATE tracks SET colorIndex = :colorIndex WHERE id = :id")
    suspend fun setColor(id: Long, colorIndex: Int)

    @Query("UPDATE tracks SET visible = :visible")
    suspend fun setAllVisible(visible: Boolean)
}

/** For picking the next palette slot. */
data class ColorUse(val colorIndex: Int, val visible: Boolean)
