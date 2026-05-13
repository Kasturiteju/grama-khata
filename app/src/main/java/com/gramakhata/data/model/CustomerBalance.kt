package com.gramakhata.data.model

import androidx.room.Embedded

data class CustomerBalance(
    @Embedded val customer: Customer,
    val balance: Double
)
