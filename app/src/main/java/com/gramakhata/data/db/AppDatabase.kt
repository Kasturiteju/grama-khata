package com.gramakhata.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gramakhata.data.model.Customer
import com.gramakhata.data.model.LedgerEntry

@Database(
    entities = [Customer::class, LedgerEntry::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customers ADD COLUMN ownerKey TEXT NOT NULL DEFAULT 'offline'")
                db.execSQL("ALTER TABLE ledger_entries ADD COLUMN ownerKey TEXT NOT NULL DEFAULT 'offline'")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "grama-khata.db"
                ).addMigrations(migration1To2)
                    .build()
                    .also { instance = it }
            }
    }
}
