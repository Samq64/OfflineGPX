package dev.samuelq.gpx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.samuelq.gpx.data.track.RouteColor
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /** Most recently opened first; the list and map re-sort it by the chosen sort. */
    @Query("SELECT * FROM tracks ORDER BY lastOpenedAtEpochMillis DESC")
    fun observeByRecent(): Flow<List<TrackEntity>>

    @Query("SELECT color, visible FROM tracks")
    suspend fun colorUsage(): List<ColorUse>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT id FROM tracks")
    suspend fun ids(): List<Long>

    @Insert
    suspend fun insert(track: TrackEntity): Long

    /**
     * Inserts [track] and hands its id to [place] before committing, so a [place] that throws
     * leaves no row. A crash before the commit rolls the id back too, for the next insert to reuse.
     */
    @Transaction
    suspend fun insert(track: TrackEntity, place: (id: Long) -> Unit): Long = insert(track).also(place)

    @Query("UPDATE tracks SET lastOpenedAtEpochMillis = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Update(entity = TrackEntity::class)
    suspend fun setSummary(summary: SummaryUpdate)

    /** Writes [summary] and runs [place] before committing, as [insert] does. */
    @Transaction
    suspend fun setSummary(summary: SummaryUpdate, place: () -> Unit) {
        setSummary(summary)
        place()
    }

    @Query("UPDATE tracks SET trackName = :name WHERE id = :id")
    suspend fun setTrackName(id: Long, name: String?)

    @Query("SELECT DISTINCT category FROM tracks WHERE category IS NOT NULL AND id != :except")
    suspend fun categories(except: Long): List<String>

    @Query("UPDATE tracks SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: String?)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrackEntity>

    @Query("UPDATE tracks SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    @Query("UPDATE tracks SET color = :color WHERE id = :id")
    suspend fun setColor(id: Long, color: RouteColor)

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

/** For picking the next colour. */
data class ColorUse(val color: RouteColor, val visible: Boolean)
