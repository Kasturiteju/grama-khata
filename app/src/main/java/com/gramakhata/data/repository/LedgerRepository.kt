package com.gramakhata.data.repository

import com.gramakhata.data.db.CustomerDao
import com.gramakhata.data.db.LedgerDao
import com.gramakhata.data.model.Customer
import com.gramakhata.data.model.DailySummary
import com.gramakhata.data.model.ENTRY_GIVE
import com.gramakhata.data.model.ENTRY_TAKE
import com.gramakhata.data.model.LedgerEntry
import kotlinx.coroutines.flow.map
import java.util.Calendar

data class CreatedCustomer(
    val customer: Customer,
    val openingEntry: LedgerEntry?
)

class LedgerRepository(
    private val customerDao: CustomerDao,
    private val ledgerDao: LedgerDao
) {
    fun customerBalances(ownerKey: String) = customerDao.observeCustomerBalances(ownerKey)

    fun customerBalance(customerId: Long, ownerKey: String) = customerDao.observeCustomerBalance(customerId, ownerKey)

    fun entries(customerId: Long, ownerKey: String) = ledgerDao.observeEntries(customerId, ownerKey)

    suspend fun deleteCustomer(ownerKey: String, customerId: Long) {
        ledgerDao.deleteForCustomer(ownerKey, customerId)
        customerDao.deleteById(ownerKey, customerId)
    }

    fun dailySummary(ownerKey: String) = ledgerDao.observeDailySummary(ownerKey, todayStart(), todayEnd()).map {
        DailySummary(
            creditGiven = it.creditGiven ?: 0.0,
            cashReceived = it.cashReceived ?: 0.0
        )
    }

    suspend fun addCustomer(
        ownerKey: String,
        name: String,
        phone: String,
        photoUri: String?,
        openingBalance: Double
    ): CreatedCustomer {
        val newCustomer = Customer(
            ownerKey = ownerKey,
            name = name.trim(),
            phone = phone.trim(),
            photoUri = photoUri
        )
        val customerId = customerDao.insert(newCustomer)
        val savedCustomer = newCustomer.copy(id = customerId)
        val openingEntry = if (openingBalance > 0.0) {
            addEntry(ownerKey, customerId, ENTRY_GIVE, openingBalance, "Opening balance")
        } else {
            null
        }
        return CreatedCustomer(savedCustomer, openingEntry)
    }

    suspend fun giveCredit(ownerKey: String, customerId: Long, amount: Double, note: String?): LedgerEntry? {
        return addEntry(ownerKey, customerId, ENTRY_GIVE, amount, note)
    }

    suspend fun takePayment(ownerKey: String, customerId: Long, amount: Double, note: String?): LedgerEntry? {
        return addEntry(ownerKey, customerId, ENTRY_TAKE, amount, note)
    }

    private suspend fun addEntry(ownerKey: String, customerId: Long, type: String, amount: Double, note: String?): LedgerEntry? {
        if (amount <= 0.0) return null
        val entry = LedgerEntry(
            ownerKey = ownerKey,
            customerId = customerId,
            type = type,
            amount = amount,
            note = note?.trim().orEmpty().ifBlank { null }
        )
        val entryId = ledgerDao.insert(entry)
        return entry.copy(id = entryId)
    }

    private fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun todayEnd(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}
