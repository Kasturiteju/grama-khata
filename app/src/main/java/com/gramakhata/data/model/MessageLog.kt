package com.gramakhata.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

data class MessageLog(
    @DocumentId val id: String = "",
    val channel: String = "",
    val status: String = "",
    val customerName: String = "",
    val phoneLast4: String = "",
    val messagePreview: String = "",
    val retryCount: Long = 0,
    val providerStatus: Long = 0,
    val error: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)
