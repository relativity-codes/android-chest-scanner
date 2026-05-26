package com.totalbattle.chestscanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chest_events")
data class ChestEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val chestName: String,
    val fromPlayer: String,
    val source: String? = null,
    val originalTimer: String = "",
    val actualTimestamp: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)
