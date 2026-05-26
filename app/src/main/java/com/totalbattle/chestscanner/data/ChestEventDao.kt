package com.totalbattle.chestscanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChestEventDao {
    @Insert
    suspend fun insert(event: ChestEventEntity)

    @Query("SELECT * FROM chest_events ORDER BY timestamp ASC")
    suspend fun getAllEvents(): List<ChestEventEntity>

    @Query("DELETE FROM chest_events")
    suspend fun deleteAll()
}
