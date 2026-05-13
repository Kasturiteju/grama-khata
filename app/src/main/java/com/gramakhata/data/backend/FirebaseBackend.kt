package com.gramakhata.data.backend

import android.app.Activity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.gramakhata.data.model.Customer
import com.gramakhata.data.model.LedgerEntry
import com.gramakhata.data.model.AdminSettings
import com.gramakhata.data.model.MessageLog
import com.gramakhata.data.model.MessageLanguage
import com.gramakhata.data.model.ReminderFrequency
import java.util.concurrent.TimeUnit

data class UserProfile(
    val email: String = "",
    val name: String = "",
    val phone: String = "",
    val photoUri: String? = null
)

class FirebaseBackend {
    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    private val functions: FirebaseFunctions
        get() = FirebaseFunctions.getInstance("asia-south1")

    fun currentUserEmail(): String? = runCatching {
        auth.currentUser?.email
    }.getOrNull()

    fun currentUserId(): String? = runCatching {
        auth.currentUser?.uid
    }.getOrNull()

    fun registerMessagingToken() {
        runCatching {
            val userId = auth.currentUser?.uid ?: return
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    firestore.collection("users")
                        .document(userId)
                        .collection("fcmTokens")
                        .document(token)
                        .set(
                            mapOf(
                                "token" to token,
                                "platform" to "android",
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                }
        }
    }

    fun signIn(email: String, password: String, onResult: (Result<String>) -> Unit) {
        runFirebase(onResult) {
            auth
                .signInWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener {
                    saveUserProfile()
                    onResult(Result.success(email.trim()))
                }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    fun createAccount(email: String, password: String, onResult: (Result<String>) -> Unit) {
        runFirebase(onResult) {
            auth
                .createUserWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener {
                    saveUserProfile()
                    onResult(Result.success(email.trim()))
                }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    fun sendPasswordResetEmail(email: String, onResult: (Result<Unit>) -> Unit) {
        runFirebase(onResult) {
            auth.sendPasswordResetEmail(email.trim())
                .addOnSuccessListener { onResult(Result.success(Unit)) }
                .addOnFailureListener { onResult(Result.failure(friendlyAuthError(it))) }
        }
    }

    fun requestPasswordResetOtp(activity: Activity, phone: String, onResult: (Result<String>) -> Unit) {
        runFirebase(onResult) {
            val normalizedPhone = normalizePhoneForAuth(phone)
            if (normalizedPhone.isBlank()) {
                onResult(Result.failure(IllegalStateException("Enter a valid registered mobile number.")))
                return@runFirebase
            }
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    signInWithPhoneResetCredential(credential) { result ->
                        result
                            .onSuccess { onResult(Result.success(AUTO_VERIFIED_RESET_TOKEN)) }
                            .onFailure { onResult(Result.failure(friendlyAuthError(it))) }
                    }
                }

                override fun onVerificationFailed(error: com.google.firebase.FirebaseException) {
                    onResult(Result.failure(friendlyAuthError(error)))
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    onResult(Result.success(verificationId))
                }
            }
            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(normalizedPhone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        }
    }

    fun confirmPasswordResetOtp(resetToken: String, otp: String, onResult: (Result<Unit>) -> Unit) {
        runFirebase(onResult) {
            if (resetToken == AUTO_VERIFIED_RESET_TOKEN) {
                onResult(Result.success(Unit))
                return@runFirebase
            }
            val credential = PhoneAuthProvider.getCredential(resetToken, otp)
            signInWithPhoneResetCredential(credential) { result ->
                result
                    .onSuccess { onResult(Result.success(Unit)) }
                    .onFailure { onResult(Result.failure(friendlyAuthError(it))) }
            }
        }
    }

    fun updatePasswordWithVerifiedPhone(password: String, onResult: (Result<Unit>) -> Unit) {
        runFirebase(onResult) {
            functions
                .getHttpsCallable("updatePasswordAfterPhoneOtp")
                .call(mapOf("password" to password))
                .addOnSuccessListener {
                    auth.signOut()
                    onResult(Result.success(Unit))
                }
                .addOnFailureListener { onResult(Result.failure(friendlyAuthError(it))) }
        }
    }

    fun signOut() {
        runCatching { auth.signOut() }
    }

    fun syncCustomer(customer: Customer) {
        runCatching {
            val userId = requireUserId()
            firestore.collection("users")
                .document(userId)
                .collection("customers")
                .document(customer.id.toString())
                .set(
                    mapOf(
                        "localId" to customer.id,
                        "name" to customer.name,
                        "phone" to customer.phone,
                        "photoUri" to customer.photoUri,
                        "createdAt" to customer.createdAt,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
        }
    }

    fun syncLedgerEntry(entry: LedgerEntry) {
        runCatching {
            val userId = requireUserId()
            firestore.collection("users")
                .document(userId)
                .collection("ledger_entries")
                .document(entry.id.toString())
                .set(
                    mapOf(
                        "localId" to entry.id,
                        "customerLocalId" to entry.customerId,
                        "type" to entry.type,
                        "amount" to entry.amount,
                        "note" to entry.note,
                        "date" to entry.date,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
        }
    }

    fun notifyCustomerAdded(customer: Customer, balance: Double, onResult: (Result<Unit>) -> Unit = { _ -> }) {
        callNotification("sendCustomerAddedMessage", customer, balance, onResult)
    }

    fun notifyCreditAdded(customer: Customer, balance: Double, onResult: (Result<Unit>) -> Unit = { _ -> }) {
        callNotification("sendCreditAddedMessage", customer, balance, onResult)
    }

    fun deleteCustomerAndPayments(customerId: Long, onResult: (Result<Unit>) -> Unit) {
        runFirebase(onResult) {
            functions
                .getHttpsCallable("deleteCustomerAndPayments")
                .call(mapOf("customerId" to customerId))
                .addOnSuccessListener { onResult(Result.success(Unit)) }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    fun submitFeedback(
        email: String?,
        message: String,
        rating: Int,
        onResult: (Result<Unit>) -> Unit
    ) {
        runFirebase(onResult) {
            val userId = requireUserId()
            val payload = mapOf(
                "userId" to userId,
                "userEmail" to email.orEmpty(),
                "message" to message.trim(),
                "rating" to rating,
                "createdAt" to FieldValue.serverTimestamp(),
                "app" to "Grama-Khata Android"
            )
            firestore
                .collection("customer_feedback")
                .add(payload)
                .addOnSuccessListener { onResult(Result.success(Unit)) }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    fun loadUserProfile(onResult: (Result<UserProfile>) -> Unit) {
        runFirebase(onResult) {
            val user = auth.currentUser ?: error("Please login before viewing profile.")
            firestore.collection("users")
                .document(user.uid)
                .get()
                .addOnSuccessListener { snapshot ->
                    onResult(
                        Result.success(
                            UserProfile(
                                email = snapshot.getString("email").orEmpty().ifBlank { user.email.orEmpty() },
                                name = snapshot.getString("name").orEmpty(),
                                phone = snapshot.getString("phone").orEmpty(),
                                photoUri = snapshot.getString("photoUri")
                            )
                        )
                    )
                }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    fun loadAdminSettings(onResult: (Result<AdminSettings>) -> Unit) {
        runFirebase(onResult) {
            val user = auth.currentUser ?: error("Please login before viewing settings.")
            firestore.collection("users")
                .document(user.uid)
                .collection("settings")
                .document("admin")
                .get()
                .addOnSuccessListener { snapshot ->
                    onResult(
                        Result.success(
                            AdminSettings(
                                smsEnabled = snapshot.getBoolean("smsEnabled") ?: true,
                                whatsappEnabled = snapshot.getBoolean("whatsappEnabled") ?: true,
                                smsAutomationEnabled = snapshot.getBoolean("smsAutomationEnabled") ?: false,
                                reminderFrequency = runCatching {
                                    ReminderFrequency.valueOf(snapshot.getString("reminderFrequency").orEmpty())
                                }.getOrDefault(ReminderFrequency.WEEKLY),
                                reminderTime = snapshot.getString("reminderTime").orEmpty().ifBlank { "09:00" },
                                language = runCatching {
                                    MessageLanguage.valueOf(snapshot.getString("language").orEmpty())
                                }.getOrDefault(MessageLanguage.BOTH),
                                addCustomerKannadaTemplate = snapshot.getString("addCustomerKannadaTemplate")
                                    ?: AdminSettings().addCustomerKannadaTemplate,
                                addCustomerEnglishTemplate = snapshot.getString("addCustomerEnglishTemplate")
                                    ?: AdminSettings().addCustomerEnglishTemplate,
                                creditKannadaTemplate = snapshot.getString("creditKannadaTemplate")
                                    ?: AdminSettings().creditKannadaTemplate,
                                creditEnglishTemplate = snapshot.getString("creditEnglishTemplate")
                                    ?: AdminSettings().creditEnglishTemplate
                            )
                        )
                    )
                }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    fun updateAdminSettings(settings: AdminSettings, onResult: (Result<AdminSettings>) -> Unit) {
        runFirebase(onResult) {
            val user = auth.currentUser ?: error("Please login before saving settings.")
            firestore.collection("users")
                .document(user.uid)
                .collection("settings")
                .document("admin")
                .set(
                    mapOf(
                        "smsEnabled" to settings.smsEnabled,
                        "whatsappEnabled" to settings.whatsappEnabled,
                        "smsAutomationEnabled" to settings.smsAutomationEnabled,
                        "reminderFrequency" to settings.reminderFrequency.name,
                        "reminderTime" to settings.reminderTime.trim(),
                        "language" to settings.language.name,
                        "addCustomerKannadaTemplate" to settings.addCustomerKannadaTemplate,
                        "addCustomerEnglishTemplate" to settings.addCustomerEnglishTemplate,
                        "creditKannadaTemplate" to settings.creditKannadaTemplate,
                        "creditEnglishTemplate" to settings.creditEnglishTemplate,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .addOnSuccessListener { onResult(Result.success(settings)) }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    fun observeMessageLogs(
        onChange: (List<MessageLog>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration? {
        return runCatching {
            val userId = requireUserId()
            firestore.collection("users")
                .document(userId)
                .collection("messageLogs")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        onError(error)
                        return@addSnapshotListener
                    }
                    onChange(
                        snapshot
                            ?.toObjects(MessageLog::class.java)
                            .orEmpty()
                            .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                    )
                }
        }.getOrElse {
            onError(it as? Exception ?: IllegalStateException(it.message, it))
            null
        }
    }


    fun updateUserProfile(profile: UserProfile, onResult: (Result<UserProfile>) -> Unit) {
        runFirebase(onResult) {
            val user = auth.currentUser ?: error("Please login before editing profile.")
            val savedProfile = profile.copy(email = user.email.orEmpty())
            firestore.collection("users")
                .document(user.uid)
                .set(
                    mapOf(
                        "email" to savedProfile.email,
                        "name" to savedProfile.name.trim(),
                        "phone" to savedProfile.phone.trim(),
                        "phoneNormalized" to normalizePhoneForAuth(savedProfile.phone),
                        "photoUri" to savedProfile.photoUri,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "app" to "Grama-Khata Android"
                    ),
                    SetOptions.merge()
                )
                .addOnSuccessListener { onResult(Result.success(savedProfile)) }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    private fun saveUserProfile() {
        val user = auth.currentUser ?: return
        firestore.collection("users")
            .document(user.uid)
            .set(
                mapOf(
                    "email" to user.email.orEmpty(),
                    "lastLoginAt" to FieldValue.serverTimestamp(),
                    "app" to "Grama-Khata Android"
                ),
                SetOptions.merge()
            )
    }

    private fun signInWithPhoneResetCredential(
        credential: PhoneAuthCredential,
        onResult: (Result<Unit>) -> Unit
    ) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    private fun callNotification(
        functionName: String,
        customer: Customer,
        balance: Double,
        onResult: (Result<Unit>) -> Unit
    ) {
        runFirebase(onResult) {
            val payload = hashMapOf(
                "customerId" to customer.id,
                "customerName" to customer.name,
                "customerPhone" to customer.phone,
                "amount" to balance.coerceAtLeast(0.0)
            )
            functions
                .getHttpsCallable(functionName)
                .call(payload)
                .addOnSuccessListener { onResult(Result.success(Unit)) }
                .addOnFailureListener { onResult(Result.failure(it)) }
        }
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid ?: error("Please login before using Firebase backend.")
    }

    private fun normalizePhoneForAuth(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.isBlank() -> ""
            digits.length == 10 -> "+91$digits"
            digits.startsWith("91") -> "+$digits"
            phone.trim().startsWith("+") -> phone.trim()
            else -> "+$digits"
        }
    }

    private fun friendlyAuthError(error: Throwable): Exception {
        val code = error.message.orEmpty().lowercase()
        val message = when {
            "invalid" in code && ("phone" in code || "number" in code) -> "Enter a valid phone number with country code."
            "invalid" in code && ("code" in code || "verification" in code) -> "Invalid OTP. Please check the code and try again."
            "expired" in code || "session" in code -> "OTP expired. Please request a new code."
            "quota" in code || "too-many" in code || "blocked" in code -> "Too many requests. Please wait and try again."
            "billing" in code || "billing_not_enabled" in code || "17499" in code ->
                "Mobile OTP needs Firebase billing. Upgrade the Firebase project to Blaze, then try again."
            "app-not-authorized" in code ->
                "Phone Auth is not ready. Enable Firebase Phone Authentication and add SHA1/SHA256 in Firebase."
            "network" in code -> "Network issue. Check your internet connection and try again."
            else -> error.message ?: "Firebase authentication failed."
        }
        return IllegalStateException(message, error)
    }

    private fun <T> runFirebase(onResult: (Result<T>) -> Unit, block: () -> Unit) {
        runCatching {
            block()
        }.onFailure {
            onResult(
                Result.failure(
                    IllegalStateException(
                        "Firebase is not configured. Add app/google-services.json from Firebase Console.",
                        it
                    )
                )
            )
        }
    }

    private companion object {
        const val AUTO_VERIFIED_RESET_TOKEN = "firebase_auto_verified"
    }
}
