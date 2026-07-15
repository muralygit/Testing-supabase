package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bills",
    indices = [Index(value = ["category"])]
)
data class Bill(
    @PrimaryKey val id: String,
    val category: String, // electricity, water, landtax, buildingtax, cabletv, other
    val date: String,     // ISO YYYY-MM-DD
    val refNumber: String,
    val notes: String,
    val photoPath: String, // local device storage path
    val ocrText: String,   // offline ML Kit text recognition result
    val createdAt: String  // timestamp string
)
