package com.gramakhata.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.gramakhata.data.model.Customer
import com.gramakhata.data.model.CustomerBalance
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Insert
    suspend fun insert(customer: Customer): Long

    @Query(
        """
        SELECT customers.*, COALESCE(SUM(
            CASE ledger_entries.type
                WHEN 'GIVE' THEN ledger_entries.amount
                WHEN 'TAKE' THEN -ledger_entries.amount
                ELSE 0
            END
        ), 0) AS balance
        FROM customers
        LEFT JOIN ledger_entries ON customers.id = ledger_entries.customerId
            AND ledger_entries.ownerKey = :ownerKey
        WHERE customers.ownerKey = :ownerKey
        GROUP BY customers.id
        ORDER BY balance DESC, customers.name COLLATE NOCASE ASC
        """
    )
    fun observeCustomerBalances(ownerKey: String): Flow<List<CustomerBalance>>

    @Query(
        """
        SELECT customers.*, COALESCE(SUM(
            CASE ledger_entries.type
                WHEN 'GIVE' THEN ledger_entries.amount
                WHEN 'TAKE' THEN -ledger_entries.amount
                ELSE 0
            END
        ), 0) AS balance
        FROM customers
        LEFT JOIN ledger_entries ON customers.id = ledger_entries.customerId
            AND ledger_entries.ownerKey = :ownerKey
        WHERE customers.id = :customerId
            AND customers.ownerKey = :ownerKey
        GROUP BY customers.id
        """
    )
    fun observeCustomerBalance(customerId: Long, ownerKey: String): Flow<CustomerBalance?>

    @Query("DELETE FROM customers WHERE id = :customerId AND ownerKey = :ownerKey")
    suspend fun deleteById(ownerKey: String, customerId: Long)
}
