package dev.samuelq.gpx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /**
     * Newest activity first, falling back to when it was last opened for files that carry
     * no timestamps at all.
     */
    @Query("SELECT * FROM tracks ORDER BY COALESCE(startedAtEpochMillis, lastOpenedAtEpochMillis) DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    /** A one-shot read of every row, to release SAF grants before clearing the table. */
    @Query("SELECT * FROM tracks")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE location = :location")
    suspend fun byLocation(location: String): TrackEntity?

    @Upsert
    suspend fun upsert(track: TrackEntity): Long

    @Query("UPDATE tracks SET lastOpenedAtEpochMillis = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}
