package com.totalbattle.chestscanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChestEventDao {
    @Insert
    suspend fun insert(event: ChestEventEntity)

    @Query("SELECT * FROM chest_events ORDER BY timestamp ASC")
    fun getAllEvents(): kotlinx.coroutines.flow.Flow<List<ChestEventEntity>>

    @Query("SELECT * FROM chest_events WHERE isSynced = :synced ORDER BY timestamp ASC")
    suspend fun getUnsyncedEvents(synced: Boolean = false): List<ChestEventEntity>

    @Query("UPDATE chest_events SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)
}
