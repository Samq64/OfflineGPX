package dev.samuelq.gpx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /**
     * Most recently interacted with first.
     *
     * The only ordering in the app. The list and the map agree by construction - the map
     * just walks it backwards, so the track at the top of the list is the one drawn on
     * top of the pile.
     */
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

    @Query("UPDATE tracks SET trackName = :name WHERE id = :id")
    suspend fun setTrackName(id: Long, name: String?)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrackEntity>

    @Query("UPDATE tracks SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    @Query("UPDATE tracks SET visible = :visible")
    suspend fun setAllVisible(visible: Boolean)
}

/** One track's palette slot and whether it is on the map, for picking the next slot. */
data class ColorUse(val colorIndex: Int, val visible: Boolean)
