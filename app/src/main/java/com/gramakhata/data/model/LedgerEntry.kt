package com.gramakhata.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val ENTRY_GIVE = "GIVE"
const val ENTRY_TAKE = "TAKE"

@Entity(
    tableName = "ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = Customer::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("customerId")]
)
data class LedgerEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerKey: String = "offline",
    val customerId: Long,
    val type: String,
    val amount: Double,
    val note: String?,
    val date: Long = System.currentTimeMillis()
)
