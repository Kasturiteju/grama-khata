package com.gramakhata.data.model

data class DailySummary(
    val creditGiven: Double = 0.0,
    val cashReceived: Double = 0.0
) {
    val netOutstanding: Double
        get() = creditGiven - cashReceived
}
