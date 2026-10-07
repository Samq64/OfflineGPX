package dev.samuelq.gpx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT DISTINCT category FROM tracks WHERE category IS NOT NULL AND id != :except")
    suspend fun categories(except: Long): List<String>

    @Query("UPDATE tracks SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: String?)

    @Query("UPDATE tracks SET location = :location WHERE id = :id")
    suspend fun setLocation(id: Long, location: String)

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

    @Query("UPDATE tracks SET visible = :visible WHERE id IN (:ids)")
    suspend fun setVisible(ids: List<Long>, visible: Boolean)

    /** Puts back what [setVisible] or [showOnly] changed, in one write. */
    @Transaction
    suspend fun setVisibility(shown: List<Long>, hidden: List<Long>) {
        setVisible(shown, true)
        setVisible(hidden, false)
    }

    /** Shows [ids] and hides every other track. */
    @Query("UPDATE tracks SET visible = id IN (:ids)")
    suspend fun showOnly(ids: List<Long>)
}

/** For picking the next palette slot. */
data class ColorUse(val colorIndex: Int, val visible: Boolean)
