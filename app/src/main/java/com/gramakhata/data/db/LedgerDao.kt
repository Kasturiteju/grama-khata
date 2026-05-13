package com.gramakhata.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.gramakhata.data.model.LedgerEntry
import kotlinx.coroutines.flow.Flow

data class SummaryRow(
    val creditGiven: Double?,
    val cashReceived: Double?
)

@Dao
interface LedgerDao {
    @Insert
    suspend fun insert(entry: LedgerEntry): Long

    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE customerId = :customerId AND ownerKey = :ownerKey
        ORDER BY date DESC
        """
    )
    fun observeEntries(customerId: Long, ownerKey: String): Flow<List<LedgerEntry>>

    @Query(
        """
        SELECT
            SUM(CASE WHEN type = 'GIVE' THEN amount ELSE 0 END) AS creditGiven,
            SUM(CASE WHEN type = 'TAKE' THEN amount ELSE 0 END) AS cashReceived
        FROM ledger_entries
        WHERE ownerKey = :ownerKey
            AND date BETWEEN :startOfDay AND :endOfDay
        """
    )
    fun observeDailySummary(ownerKey: String, startOfDay: Long, endOfDay: Long): Flow<SummaryRow>

    @Query("DELETE FROM ledger_entries WHERE customerId = :customerId AND ownerKey = :ownerKey")
    suspend fun deleteForCustomer(ownerKey: String, customerId: Long)
}
