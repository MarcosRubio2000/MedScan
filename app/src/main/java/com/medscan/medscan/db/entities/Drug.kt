package com.medscan.medscan.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "drugs",
    indices = [Index(value = ["normalized"], unique = true)]
)
data class Drug(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalized: String
)
