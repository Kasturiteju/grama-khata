package com.gramakhata.data.model

data class AdminSettings(
    val smsEnabled: Boolean = true,
    val whatsappEnabled: Boolean = true,
    val smsAutomationEnabled: Boolean = false,
    val reminderFrequency: ReminderFrequency = ReminderFrequency.WEEKLY,
    val reminderTime: String = "09:00",
    val language: MessageLanguage = MessageLanguage.BOTH,
    val addCustomerKannadaTemplate: String = DEFAULT_ADD_CUSTOMER_KANNADA,
    val addCustomerEnglishTemplate: String = DEFAULT_ADD_CUSTOMER_ENGLISH,
    val creditKannadaTemplate: String = DEFAULT_CREDIT_KANNADA,
    val creditEnglishTemplate: String = DEFAULT_CREDIT_ENGLISH
)

enum class MessageLanguage {
    KANNADA,
    ENGLISH,
    BOTH
}

enum class ReminderFrequency {
    DAILY,
    WEEKLY,
    MONTHLY
}

const val DEFAULT_ADD_CUSTOMER_KANNADA = "ನಮ್ಮ ಮೌಲ್ಯವಾದ ಗ್ರಾಹಕರಾಗಿರುವುದಕ್ಕೆ ಧನ್ಯವಾದಗಳು.\nನಿಮ್ಮ ಬಾಕಿ ಮೊತ್ತ ₹{balance}.\nದಯವಿಟ್ಟು ಅನುಕೂಲವಾದಾಗ ಪಾವತಿಸಿ."
const val DEFAULT_ADD_CUSTOMER_ENGLISH = "Thanks for being a valuable customer.\nYour outstanding balance is ₹{balance}.\nPlease pay at your convenience."
const val DEFAULT_CREDIT_KANNADA = "ನಮಸ್ಕಾರ {customer_name} ಅವರೇ,\n\nನಿಮ್ಮ ಬಾಕಿ ಮೊತ್ತ ₹{balance}.\nದಯವಿಟ್ಟು ಅನುಕೂಲವಾದಾಗ ಪಾವತಿಸಿ."
const val DEFAULT_CREDIT_ENGLISH = "Dear {customer_name},\n\nYour outstanding balance is ₹{balance}.\nPlease pay at your convenience."
