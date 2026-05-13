package com.gramakhata

import android.app.Application
import com.gramakhata.data.db.AppDatabase
import com.gramakhata.data.repository.LedgerRepository

class GramaKhataApp : Application() {
    val repository: LedgerRepository by lazy {
        LedgerRepository(
            AppDatabase.getInstance(this).customerDao(),
            AppDatabase.getInstance(this).ledgerDao()
        )
    }
}
