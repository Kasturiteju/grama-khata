# ಗ್ರಾಮ-ಖಾತಾ · Grama-Khata

> **A digital replacement for the traditional Vahi (ವಹಿ) ledger book used by village shopkeepers.**

Grama-Khata is a micro-finance ledger app for non-technical users in rural areas. It replaces fragile physical credit books with a simple, offline-first mobile app that tracks the "Give/Take" relationship between a shopkeeper and their customers — and sends polite WhatsApp reminders with one tap.

---

## The Problem

Village shopkeepers in Karnataka run informal credit systems. A customer takes goods, the shopkeeper writes it in a physical Vahi, and repayment happens later. This system breaks when:

- The physical book is lost, damaged, or destroyed
- Customers "forget" their debt
- There is no verifiable record to show either party

## The Solution

A simple Android app that:

- Keeps every transaction stored locally (no internet required)
- Shows who owes what at a glance
- Lets the shopkeeper send a bilingual (Kannada + English) WhatsApp reminder in one tap

---

## Screens

### 1. Due Dashboard (Home)
- Lists all customers sorted by highest balance owed
- Shows customer name, avatar/photo, and current balance
- Total outstanding amount displayed at the top

### 2. Customer Detail & Transaction Log
- Full transaction history for each customer
- Large **➕ Gave Credit** and **➖ Took Payment** buttons (thumb-friendly)
- Instant balance update on every tap
- One-tap WhatsApp reminder with a pre-filled Kannada + English message

### 3. Add New Customer
- Capture name, phone number, photo, and opening balance
- Avatar auto-generated from initials if no photo provided

### 4. Daily Collection Report
- Summary of all transactions made during the day
- Total credit given, cash received, and net outstanding

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | XML Layouts / Jetpack Compose |
| Local Database | Room DB |
| State Management | ViewModel + LiveData |
| WhatsApp / SMS | `Intent.ACTION_SEND` |
| Architecture | MVVM |
| Min SDK | Android 6.0 (API 23) |

---

## Architecture Overview

```
com.graмakhata/
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt          # Room database instance
│   │   ├── CustomerDao.kt          # Customer CRUD queries
│   │   └── TransactionDao.kt       # Transaction queries
│   ├── model/
│   │   ├── Customer.kt             # @Entity: id, name, phone, photoUri
│   │   └── Transaction.kt          # @Entity: id, customerId, type, amount, note, date
│   └── repository/
│       └── LedgerRepository.kt     # Single source of truth for ViewModels
│
├── ui/
│   ├── dashboard/
│   │   ├── DashboardFragment.kt    # Customer list sorted by balance
│   │   └── DashboardViewModel.kt   # Aggregates total due, sorts list
│   ├── detail/
│   │   ├── DetailFragment.kt       # Give/Take buttons, txn log, reminder
│   │   └── DetailViewModel.kt      # Live balance calculation
│   ├── addcustomer/
│   │   └── AddCustomerFragment.kt  # New customer form
│   └── report/
│       └── ReportFragment.kt       # Daily summary view
│
└── utils/
    └── ReminderHelper.kt           # Builds WhatsApp Intent with message
```

---

## Room Database Schema

```kotlin
// Customer entity
@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phone: String?,
    val photoUri: String?,
    val createdAt: Long = System.currentTimeMillis()
)

// Transaction entity
@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val customerId: Int,
    val type: String,       // "give" | "take"
    val amount: Double,
    val note: String?,
    val date: Long = System.currentTimeMillis()
)
```

---

## WhatsApp Reminder

The reminder message is pre-filled in Kannada and English:

```
ನಮಸ್ಕಾರ [Customer Name] ಅವರೇ 🙏

ನಿಮ್ಮ ಅಂಗಡಿ ಖಾತೆಯಲ್ಲಿ ₹[Amount] ಬಾಕಿ ಇದೆ.
ದಯವಿಟ್ಟು ಅನುಕೂಲದಾಗ ಪಾವತಿಸಿ.

Dear [Customer Name],
Your outstanding balance is ₹[Amount].
Please pay at your convenience.

— Grama-Khata
```

Triggered via:
```kotlin
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    setPackage("com.whatsapp")
    putExtra(Intent.EXTRA_TEXT, message)
}
startActivity(intent)
```

---

## Design Principles

- **Offline-First** — Room DB means zero dependency on internet connectivity
- **One-Hand UI** — all primary buttons are large (min 56dp), placed in the bottom half of the screen, thumb-reachable
- **Real-Time Balance** — ViewModel + LiveData ensures balance updates instantly after every Give/Take action, even on screen rotation
- **Bilingual** — Kannada primary, English secondary throughout the UI
- **No Login Required** — data stays on the device; no account, no cloud, no friction

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

### Build & Run

```bash
# Clone the repository
git clone https://github.com/your-username/grama-khata.git
cd grama-khata

# Open in Android Studio
# File → Open → select the project folder

# Run on device or emulator
./gradlew assembleDebug
```

### Dependencies (`build.gradle`)

```groovy
dependencies {
    // Room
    implementation "androidx.room:room-runtime:2.6.1"
    implementation "androidx.room:room-ktx:2.6.1"
    kapt "androidx.room:room-compiler:2.6.1"

    // ViewModel + LiveData
    implementation "androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0"
    implementation "androidx.lifecycle:lifecycle-livedata-ktx:2.7.0"

    // Navigation
    implementation "androidx.navigation:navigation-fragment-ktx:2.7.7"
    implementation "androidx.navigation:navigation-ui-ktx:2.7.7"

    // Glide (for customer photos)
    implementation "com.github.bumptech.glide:glide:4.16.0"
}
```

---

## Success Criteria

| Criteria | Status |
|---|---|
| Works fully offline | ✅ Room DB |
| Balance updates instantly on Give/Take | ✅ ViewModel LiveData |
| One-hand usable (large buttons) | ✅ 56dp+ touch targets |
| WhatsApp reminder in one tap | ✅ Intent.ACTION_SEND |
| Bilingual Kannada + English UI | ✅ |
| No data loss on screen rotation | ✅ ViewModel survives rotation |

---

## Why This Matters

India's rural economy runs on informal credit. The *Vahi* system has existed for generations — it is not broken in concept, only in its physical vulnerability. Grama-Khata does not replace the trust between a shopkeeper and their community. It protects it.

---

## License

MIT License. Free to use, modify, and distribute.

---

*Built as an internship project. Designed for Karnataka, adaptable for any region.*
