package com.gramakhata.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerKey: String = "offline",
    val name: String,
    val phone: String,
    val photoUri: String?,
    val createdAt: Long = System.currentTimeMillis()
)
