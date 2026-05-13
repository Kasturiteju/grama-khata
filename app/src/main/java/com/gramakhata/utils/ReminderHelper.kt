package com.gramakhata.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

object ReminderHelper {
    fun creditMessage(customerName: String, amount: Double): String =
        buildString {
            appendLine("ನಮಸ್ಕಾರ $customerName ಅವರೇ,")
            appendLine()
            appendLine("ನಿಮ್ಮ ಬಾಕಿ ಮೊತ್ತ ₹${amount.toInt()}.")
            appendLine("ದಯವಿಟ್ಟು ಅನುಕೂಲವಾದಾಗ ಪಾವತಿಸಿ.")
            appendLine()
            appendLine("Dear $customerName,")
            appendLine()
            appendLine("Your outstanding balance is ₹${amount.toInt()}.")
            appendLine("Please pay at your convenience.")
            appendLine()
            append("- Grama-Khata")
        }

    fun share(context: Context, customerName: String, phone: String, amount: Double) {
        val message = creditMessage(customerName, amount)
        val messageWithQr = buildString {
            append(message)
            appendLine()
            appendLine()
            appendLine("Download QR code:")
            append(qrCodeLink(message))
        }
        openWhatsApp(context, phone, messageWithQr)
    }

    fun sendSms(context: Context, phone: String, rawMessage: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${phone.filter { it.isDigit() }}")
            putExtra("sms_body", rawMessage)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun openWhatsApp(context: Context, phone: String, rawMessage: String) {
        val message = Uri.encode(rawMessage)
        val normalizedPhone = phone.filter { it.isDigit() }
        val whatsappPhone = when {
            normalizedPhone.isBlank() -> ""
            normalizedPhone.length == 10 -> "91$normalizedPhone"
            else -> normalizedPhone
        }
        val whatsappUri = if (whatsappPhone.isBlank()) {
            Uri.parse("https://wa.me/?text=$message")
        } else {
            Uri.parse("https://wa.me/$whatsappPhone?text=$message")
        }
        val intent = Intent(Intent.ACTION_VIEW, whatsappUri).apply {
            setPackage("com.whatsapp")
        }
        val fallbackIntent = Intent(Intent.ACTION_VIEW, whatsappUri)

        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(fallbackIntent) }
    }

    private fun qrCodeLink(data: String): String =
        Uri.Builder()
            .scheme("https")
            .authority("api.qrserver.com")
            .path("v1/create-qr-code/")
            .appendQueryParameter("size", "600x600")
            .appendQueryParameter("format", "png")
            .appendQueryParameter("data", data)
            .build()
            .toString()
}
