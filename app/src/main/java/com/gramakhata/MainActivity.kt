package com.gramakhata

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.google.firebase.firestore.ListenerRegistration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import com.gramakhata.data.backend.FirebaseBackend
import com.gramakhata.data.backend.UserProfile
import com.gramakhata.data.model.AdminSettings
import com.gramakhata.data.model.CustomerBalance
import com.gramakhata.data.model.DailySummary
import com.gramakhata.data.model.ENTRY_GIVE
import com.gramakhata.data.model.ENTRY_TAKE
import com.gramakhata.data.model.LedgerEntry
import com.gramakhata.data.model.MessageLanguage
import com.gramakhata.data.model.MessageLog
import com.gramakhata.data.model.ReminderFrequency
import com.gramakhata.data.repository.LedgerRepository
import com.gramakhata.utils.ReminderHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            GramaKhataTheme {
                GramaKhataAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: LedgerRepository = (application as GramaKhataApp).repository
    private val backend = FirebaseBackend()

    var userEmail by mutableStateOf(backend.currentUserEmail())
        private set
    var offlineMode by mutableStateOf(false)
        private set
    var authMessage by mutableStateOf<String?>(null)
        private set
    var authLoading by mutableStateOf(false)
        private set
    var resetMessage by mutableStateOf<String?>(null)
        private set
    var resetLoading by mutableStateOf(false)
        private set
    var resetVerificationId by mutableStateOf<String?>(null)
        private set
    var resetOtpVerified by mutableStateOf(false)
        private set
    var feedbackMessage by mutableStateOf<String?>(null)
        private set
    var userProfile by mutableStateOf(UserProfile(email = userEmail.orEmpty()))
        private set
    var profileMessage by mutableStateOf<String?>(null)
        private set
    var profileLoading by mutableStateOf(false)
        private set
    var paymentMessage by mutableStateOf<String?>(null)
        private set
    var adminSettings by mutableStateOf(AdminSettings())
        private set
    var settingsLoading by mutableStateOf(false)
        private set
    var settingsMessage by mutableStateOf<String?>(null)
        private set
    var loginLanguage by mutableStateOf(LoginLanguage.ENGLISH)
        private set
    private val ownerKey = MutableStateFlow(currentOwnerKey())

    init {
        if (userEmail != null) {
            loadProfile()
            loadAdminSettings()
            backend.registerMessagingToken()
        }
    }

    val canUseApp: Boolean
        get() = userEmail != null || offlineMode

    val customers = ownerKey.flatMapLatest { key ->
        repository.customerBalances(key)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    val totalDue = customers.map { list -> list.sumOf { it.balance.coerceAtLeast(0.0) } }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0.0
    )

    val dailySummary = ownerKey.flatMapLatest { key ->
        repository.dailySummary(key)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DailySummary()
    )

    fun customer(customerId: Long) = repository.customerBalance(customerId, ownerKey.value)

    fun entries(customerId: Long) = repository.entries(customerId, ownerKey.value)

    fun observeMessageLogs(
        onChange: (List<MessageLog>) -> Unit,
        onError: (String) -> Unit
    ) = backend.observeMessageLogs(onChange) { error ->
        onError(error.message ?: "Message logs could not be loaded.")
    }

    fun addCustomer(
        name: String,
        phone: String,
        photoUri: String?,
        openingBalance: Double,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val created = repository.addCustomer(ownerKey.value, name, phone, photoUri, openingBalance)
            if (userEmail != null) {
                backend.syncCustomer(created.customer)
                created.openingEntry?.let { backend.syncLedgerEntry(it) }
                if (phone.isNotBlank()) {
                    backend.notifyCustomerAdded(created.customer, openingBalance) { result ->
                        paymentMessage = result.fold(
                            onSuccess = { "Customer saved and message queued." },
                            onFailure = { optionalBackendMessage(it, "Customer saved. Message delivery failed.") }
                        )
                    }
                }
            }
            onSaved()
        }
    }

    fun giveCredit(customerId: Long, amount: Double, note: String?) {
        viewModelScope.launch {
            repository.giveCredit(ownerKey.value, customerId, amount, note)?.let { entry ->
                if (userEmail != null) backend.syncLedgerEntry(entry)
                customers.value.firstOrNull { it.customer.id == customerId }?.let { customer ->
                    if (userEmail != null && customer.customer.phone.isNotBlank()) {
                        backend.notifyCreditAdded(customer.customer, customer.balance + amount) { result ->
                            paymentMessage = result.fold(
                                onSuccess = { "Credit saved and message queued." },
                                onFailure = { optionalBackendMessage(it, "Credit saved. Message delivery failed.") }
                            )
                        }
                    }
                }
            }
        }
    }

    fun takePayment(customerId: Long, amount: Double, note: String?) {
        viewModelScope.launch {
            repository.takePayment(ownerKey.value, customerId, amount, note)?.let { entry ->
                if (userEmail != null) backend.syncLedgerEntry(entry)
            }
        }
    }

    fun deleteCustomer(customerId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteCustomer(ownerKey.value, customerId)
            if (userEmail != null) {
                backend.deleteCustomerAndPayments(customerId) { result ->
                    paymentMessage = result.fold(
                        onSuccess = { "Customer deleted." },
                        onFailure = { optionalBackendMessage(it, "Customer deleted locally. Cloud cleanup failed.") }
                    )
                }
            } else {
                paymentMessage = "Customer deleted."
            }
            onDeleted()
        }
    }

    fun signIn(email: String, password: String) {
        if (!validLoginInput(email, password)) return
        authLoading = true
        authMessage = null
        backend.signIn(email, password) { result ->
            authLoading = false
            result
                .onSuccess {
                    userEmail = it
                    userProfile = userProfile.copy(email = it)
                    offlineMode = false
                    ownerKey.value = currentOwnerKey()
                    loadProfile()
                    loadAdminSettings()
                    backend.registerMessagingToken()
                }
                .onFailure { authMessage = it.message ?: "Login failed" }
        }
    }

    fun createAccount(email: String, password: String) {
        if (!validLoginInput(email, password)) return
        authLoading = true
        authMessage = null
        backend.createAccount(email, password) { result ->
            authLoading = false
            result
                .onSuccess {
                    userEmail = it
                    userProfile = userProfile.copy(email = it)
                    offlineMode = false
                    ownerKey.value = currentOwnerKey()
                    loadProfile()
                    loadAdminSettings()
                    backend.registerMessagingToken()
                }
                .onFailure { authMessage = it.message ?: "Account creation failed" }
        }
    }

    fun sendPasswordResetEmail(email: String, onSent: () -> Unit) {
        if (email.isBlank()) {
            resetMessage = "Enter your registered email address."
            return
        }
        resetLoading = true
        resetMessage = null
        backend.sendPasswordResetEmail(email) { result ->
            resetLoading = false
            result
                .onSuccess {
                    resetMessage = "Password reset email sent. Open the link to set a new password."
                    onSent()
                }
                .onFailure { resetMessage = it.message ?: "Could not send reset email." }
        }
    }

    fun sendResetOtp(activity: Activity, phone: String, onCodeSent: () -> Unit, onAutoVerified: () -> Unit) {
        if (phone.isBlank()) {
            resetMessage = "Enter your registered phone number."
            return
        }
        resetLoading = true
        resetMessage = null
        resetVerificationId = null
        resetOtpVerified = false
        backend.requestPasswordResetOtp(activity, phone) { result ->
            resetLoading = false
            result
                .onSuccess {
                    resetVerificationId = it
                    if (it == "firebase_auto_verified") {
                        resetOtpVerified = true
                        resetMessage = "Phone verified. Set a new password."
                        onAutoVerified()
                    } else {
                        resetMessage = "OTP sent by Firebase SMS. It expires soon."
                        onCodeSent()
                    }
                }
                .onFailure { resetMessage = it.message ?: "Could not send OTP." }
        }
    }

    fun verifyResetOtp(otp: String, onVerified: () -> Unit) {
        val verificationId = resetVerificationId
        if (verificationId.isNullOrBlank()) {
            resetMessage = "Request a new OTP before verifying."
            return
        }
        if (otp.length < 6) {
            resetMessage = "Enter the 6 digit OTP."
            return
        }
        resetLoading = true
        resetMessage = null
        backend.confirmPasswordResetOtp(verificationId, otp) { result ->
            resetLoading = false
            result
                .onSuccess {
                    resetOtpVerified = true
                    resetMessage = "OTP verified. Set a new password."
                    onVerified()
                }
                .onFailure { resetMessage = it.message ?: "OTP verification failed." }
        }
    }

    fun updateResetPassword(password: String, confirmPassword: String, onUpdated: () -> Unit) {
        when {
            !resetOtpVerified -> {
                resetMessage = "Verify OTP before setting a new password."
                return
            }
            password.length < 6 -> {
                resetMessage = "Password must be at least 6 characters."
                return
            }
            password != confirmPassword -> {
                resetMessage = "Passwords do not match."
                return
            }
        }
        resetLoading = true
        resetMessage = null
        backend.updatePasswordWithVerifiedPhone(password) { result ->
            resetLoading = false
            result
                .onSuccess {
                    resetMessage = "Password changed successfully."
                    resetVerificationId = null
                    resetOtpVerified = false
                    onUpdated()
                }
                .onFailure { resetMessage = it.message ?: "Password could not be updated." }
        }
    }

    fun clearResetFlow() {
        resetMessage = null
        resetLoading = false
        resetVerificationId = null
        resetOtpVerified = false
    }

    fun continueOffline() {
        offlineMode = true
        ownerKey.value = currentOwnerKey()
        authMessage = null
    }

    fun signOut() {
        backend.signOut()
        userEmail = null
        userProfile = UserProfile()
        profileMessage = null
        adminSettings = AdminSettings()
        offlineMode = false
        ownerKey.value = currentOwnerKey()
    }

    fun switchLoginLanguage(language: LoginLanguage) {
        loginLanguage = language
    }

    fun submitFeedback(message: String, rating: Int) {
        if (message.isBlank()) {
            feedbackMessage = "Please enter feedback before sending."
            return
        }
        feedbackMessage = "Sending..."
        backend.submitFeedback(userEmail, message, rating) { result ->
            feedbackMessage = result.fold(
                onSuccess = { "Feedback sent. Thank you!" },
                onFailure = { it.message ?: "Feedback could not be sent." }
            )
        }
    }

    fun loadProfile() {
        if (userEmail == null) {
            userProfile = UserProfile(email = "Offline mode")
            return
        }
        profileLoading = true
        profileMessage = null
        backend.loadUserProfile { result ->
            profileLoading = false
            result
                .onSuccess { userProfile = it }
                .onFailure { profileMessage = it.message ?: "Profile could not be loaded." }
        }
    }

    fun updateProfile(name: String, phone: String, photoUri: String?) {
        if (userEmail == null) {
            userProfile = UserProfile(
                email = "Offline mode",
                name = name.trim(),
                phone = phone.trim(),
                photoUri = photoUri
            )
            profileMessage = "Profile saved on this device for offline mode."
            return
        }
        profileLoading = true
        profileMessage = null
        backend.updateUserProfile(
            userProfile.copy(name = name, phone = phone, photoUri = photoUri)
        ) { result ->
            profileLoading = false
            result
                .onSuccess {
                    userProfile = it
                    profileMessage = "Profile updated."
                }
                .onFailure { profileMessage = it.message ?: "Profile could not be saved." }
        }
    }

    fun loadAdminSettings() {
        if (userEmail == null) {
            settingsMessage = "Login with Firebase to sync admin settings."
            return
        }
        settingsLoading = true
        settingsMessage = null
        backend.loadAdminSettings { result ->
            settingsLoading = false
            result
                .onSuccess { adminSettings = it }
                .onFailure { settingsMessage = it.message ?: "Settings could not be loaded." }
        }
    }

    fun updateAdminSettings(settings: AdminSettings) {
        settingsLoading = true
        settingsMessage = null
        backend.updateAdminSettings(settings) { result ->
            settingsLoading = false
            result
                .onSuccess {
                    adminSettings = it
                    settingsMessage = "Settings saved."
                }
                .onFailure { settingsMessage = it.message ?: "Settings could not be saved." }
        }
    }

    fun sendReminder(customer: CustomerBalance, onDone: (String) -> Unit) {
        if (customer.balance <= 0.0) {
            onDone("${customer.customer.name}: no pending balance.")
            return
        }
        if (customer.customer.phone.isBlank()) {
            onDone("${customer.customer.name}: phone number missing.")
            return
        }
        backend.notifyCreditAdded(customer.customer, customer.balance) { result ->
            onDone(
                result.fold(
                    onSuccess = { "${customer.customer.name}: reminder queued." },
                    onFailure = {
                        optionalBackendMessage(it, "reminder failed.")
                            ?.let { message -> "${customer.customer.name}: $message" }
                            ?: "${customer.customer.name}: reminder opened on this phone."
                    }
                )
            )
        }
    }

    private fun validLoginInput(email: String, password: String): Boolean {
        return when {
            email.isBlank() -> {
                authMessage = "Enter your email address."
                false
            }
            password.length < 6 -> {
                authMessage = "Password must be at least 6 characters."
                false
            }
            else -> true
        }
    }

    private fun currentOwnerKey(): String = userEmail?.lowercase(Locale.ROOT) ?: "offline"

    private fun optionalBackendMessage(error: Throwable, fallback: String): String? {
        val message = error.message.orEmpty()
        val lower = message.lowercase(Locale.ROOT)
        return if ("not_found" in lower || "not-found" in lower || "not found" in lower) {
            null
        } else {
            message.ifBlank { fallback }
        }
    }

}

enum class LoginLanguage {
    ENGLISH,
    KANNADA
}

private enum class AuthFlow {
    LOGIN,
    FORGOT,
    OTP,
    RESET_PASSWORD
}

private val LoginLanguage.label: String
    get() = when (this) {
        LoginLanguage.ENGLISH -> "English"
        LoginLanguage.KANNADA -> "ಕನ್ನಡ"
    }

@Composable
private fun GramaKhataTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF2E7D32),
        secondary = Color(0xFF795548),
        tertiary = Color(0xFF1565C0),
        background = Color(0xFFF7F3EA),
        surface = Color(0xFFFFFCF5),
        error = Color(0xFFC62828)
    )

    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GramaKhataAppScreen(viewModel: LedgerViewModel = viewModel()) {
    val appContext = LocalContext.current
    if (!viewModel.canUseApp) {
        var authFlow by remember { mutableStateOf(AuthFlow.LOGIN) }
        when (authFlow) {
            AuthFlow.LOGIN -> LoginScreen(
                loading = viewModel.authLoading,
                message = viewModel.authMessage,
                language = viewModel.loginLanguage,
                onLanguageChange = viewModel::switchLoginLanguage,
                onSignIn = viewModel::signIn,
                onCreateAccount = viewModel::createAccount,
                onForgotPassword = {
                    viewModel.clearResetFlow()
                    authFlow = AuthFlow.FORGOT
                },
                onContinueOffline = viewModel::continueOffline
            )
            AuthFlow.FORGOT -> ForgotPasswordScreen(
                loading = viewModel.resetLoading,
                message = viewModel.resetMessage,
                onBack = { authFlow = AuthFlow.LOGIN },
                onEmailReset = { email -> viewModel.sendPasswordResetEmail(email) { authFlow = AuthFlow.LOGIN } },
                onPhoneReset = { phone ->
                    val activity = appContext as? Activity
                    if (activity != null) {
                        viewModel.sendResetOtp(
                            activity = activity,
                            phone = phone,
                            onCodeSent = { authFlow = AuthFlow.OTP },
                            onAutoVerified = { authFlow = AuthFlow.RESET_PASSWORD }
                        )
                    }
                }
            )
            AuthFlow.OTP -> OtpVerificationScreen(
                loading = viewModel.resetLoading,
                message = viewModel.resetMessage,
                onBack = { authFlow = AuthFlow.FORGOT },
                onVerify = { otp -> viewModel.verifyResetOtp(otp) { authFlow = AuthFlow.RESET_PASSWORD } }
            )
            AuthFlow.RESET_PASSWORD -> ResetPasswordScreen(
                loading = viewModel.resetLoading,
                message = viewModel.resetMessage,
                onBack = { authFlow = AuthFlow.LOGIN },
                onReset = { password, confirm ->
                    viewModel.updateResetPassword(password, confirm) { authFlow = AuthFlow.LOGIN }
                }
            )
        }
        return
    }

    val navController = rememberNavController()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ಗ್ರಾಮ-ಖಾತಾ · Grama-Khata", fontWeight = FontWeight.Bold)
                        Text(
                            viewModel.userEmail ?: "Offline mode",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::signOut) {
                        Text("Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = false,
                    onClick = { navController.navigate("dashboard") },
                    icon = { Text("ಖಾತೆ") },
                    label = { Text("Ledger") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { navController.navigate("report") },
                    icon = { Text("ದಿನ") },
                    label = { Text("Today") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { navController.navigate("reminders") },
                    icon = { Text("ಮತ") },
                    label = { Text("Remind") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {
                        viewModel.loadProfile()
                        navController.navigate("profile")
                    },
                    icon = { Text("Me") },
                    label = { Text("Profile") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {
                        viewModel.loadAdminSettings()
                        navController.navigate("settings")
                    },
                    icon = { Text("Set") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") {
                val customers by viewModel.customers.collectAsState()
                val totalDue by viewModel.totalDue.collectAsState()
                DashboardScreen(
                    customers = customers,
                    totalDue = totalDue,
                    onOpenCustomer = { navController.navigate("detail/$it") },
                    onAddCustomer = { navController.navigate("add") }
                )
            }
            composable("add") {
                AddCustomerScreen(
                    onBack = { navController.popBackStack() },
                    onSave = { name, phone, photoUri, opening ->
                        viewModel.addCustomer(name, phone, photoUri, opening) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable(
                route = "detail/{customerId}",
                arguments = listOf(navArgument("customerId") { type = NavType.LongType })
            ) { backStackEntry ->
                val customerId = backStackEntry.arguments?.getLong("customerId") ?: 0L
                val customer by viewModel.customer(customerId).collectAsState(initial = null)
                val entries by viewModel.entries(customerId).collectAsState(initial = emptyList())
                DetailScreen(
                    customer = customer,
                    entries = entries,
                    paymentMessage = viewModel.paymentMessage,
                    onBack = { navController.popBackStack() },
                    onGive = { amount, note -> viewModel.giveCredit(customerId, amount, note) },
                    onTake = { amount, note -> viewModel.takePayment(customerId, amount, note) },
                    onDeleteCustomer = {
                        viewModel.deleteCustomer(customerId) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable("report") {
                val summary by viewModel.dailySummary.collectAsState()
                ReportScreen(summary = summary)
            }
            composable("reminders") {
                val customers by viewModel.customers.collectAsState()
                ReminderSelectionScreen(
                    customers = customers,
                    onSendOne = { customer, done -> viewModel.sendReminder(customer, done) },
                    onOpenWhatsApp = { customer ->
                        ReminderHelper.share(
                            appContext,
                            customer.customer.name,
                            customer.customer.phone,
                            customer.balance
                        )
                    },
                    onOpenSms = { customer ->
                        ReminderHelper.sendSms(
                            appContext,
                            customer.customer.phone,
                            ReminderHelper.creditMessage(customer.customer.name, customer.balance)
                        )
                    }
                )
            }
            composable("feedback") {
                FeedbackScreen(
                    userEmail = viewModel.userEmail,
                    message = viewModel.feedbackMessage,
                    onSubmit = viewModel::submitFeedback,
                    onSignOut = viewModel::signOut
                )
            }
            composable("profile") {
                ProfileScreen(
                    profile = viewModel.userProfile,
                    loading = viewModel.profileLoading,
                    message = viewModel.profileMessage,
                    onSave = viewModel::updateProfile,
                    onRefresh = viewModel::loadProfile
                )
            }
            composable("settings") {
                SettingsScreen(
                    settings = viewModel.adminSettings,
                    loading = viewModel.settingsLoading,
                    message = viewModel.settingsMessage,
                    onSave = viewModel::updateAdminSettings,
                    onRefresh = viewModel::loadAdminSettings,
                    observeLogs = { onChange, onError -> viewModel.observeMessageLogs(onChange, onError) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    loading: Boolean,
    message: String?,
    language: LoginLanguage,
    onLanguageChange: (LoginLanguage) -> Unit,
    onSignIn: (String, String) -> Unit,
    onCreateAccount: (String, String) -> Unit,
    onForgotPassword: () -> Unit,
    onContinueOffline: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var languageExpanded by remember { mutableStateOf(false) }
    val text = loginText(language)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        ExposedDropdownMenuBox(
            expanded = languageExpanded,
            onExpandedChange = { languageExpanded = !languageExpanded }
        ) {
            OutlinedTextField(
                value = language.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = languageExpanded,
                onDismissRequest = { languageExpanded = false }
            ) {
                LoginLanguage.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onLanguageChange(option)
                            languageExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(text.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(text.subtitle)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(text.email) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(text.password) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = !loading,
            onClick = { onSignIn(email, password) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(if (loading) text.loading else text.login)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            enabled = !loading,
            onClick = { onCreateAccount(email, password) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(text.createAccount)
        }
        TextButton(
            enabled = !loading,
            onClick = onForgotPassword,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Forgot Password?")
        }
        TextButton(
            onClick = onContinueOffline,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text.continueOffline)
        }
    }
}

private data class LoginCopy(
    val title: String,
    val subtitle: String,
    val email: String,
    val password: String,
    val login: String,
    val loading: String,
    val createAccount: String,
    val continueOffline: String
)

@Composable
private fun ForgotPasswordScreen(
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onEmailReset: (String) -> Unit,
    onPhoneReset: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Forgot Password", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Reset with email, or verify your registered phone number with OTP.")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Registered email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Button(
            enabled = !loading && email.isNotBlank(),
            onClick = { onEmailReset(email) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(if (loading) "Sending..." else "Send reset email")
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            enabled = !loading && phone.isNotBlank(),
            onClick = { onPhoneReset(phone) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(if (loading) "Sending..." else "Send OTP")
        }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = if (message.contains("sent", true) || message.contains("verified", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to login")
        }
    }
}

@Composable
private fun OtpVerificationScreen(
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onVerify: (String) -> Unit
) {
    var otp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Verify OTP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Enter the 6 digit code sent by Firebase.")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = otp,
            onValueChange = { otp = it.filter(Char::isDigit).take(6) },
            label = { Text("OTP") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !loading && otp.length == 6,
            onClick = { onVerify(otp) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(if (loading) "Verifying..." else "Verify OTP")
        }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = if (message.contains("verified", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Use another phone")
        }
    }
}

@Composable
private fun ResetPasswordScreen(
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onReset: (String, String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Set New Password", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Use at least 6 characters.")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("New password") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm password") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !loading && password.length >= 6 && confirm.length >= 6,
            onClick = { onReset(password, confirm) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(if (loading) "Updating..." else "Update password")
        }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = if (message.contains("updated", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to login")
        }
    }
}

private fun loginText(language: LoginLanguage): LoginCopy {
    return when (language) {
        LoginLanguage.ENGLISH -> LoginCopy(
            title = "Grama-Khata",
            subtitle = "Login to sync ledger data with Firebase.",
            email = "Email",
            password = "Password",
            login = "Login",
            loading = "Please wait...",
            createAccount = "Create account",
            continueOffline = "Continue offline for now"
        )
        LoginLanguage.KANNADA -> LoginCopy(
            title = "ಗ್ರಾಮ-ಖಾತಾ",
            subtitle = "Firebase ಜೊತೆ ಖಾತೆ ಡೇಟಾ sync ಮಾಡಲು login ಮಾಡಿ.",
            email = "ಇಮೇಲ್",
            password = "ಪಾಸ್‌ವರ್ಡ್",
            login = "ಲಾಗಿನ್",
            loading = "ಸ್ವಲ್ಪ ಕಾಯಿರಿ...",
            createAccount = "ಖಾತೆ ರಚಿಸಿ",
            continueOffline = "ಈಗ offline ಮುಂದುವರಿಸಿ"
        )
    }
}

@Composable
private fun DashboardScreen(
    customers: List<CustomerBalance>,
    totalDue: Double,
    onOpenCustomer: (Long) -> Unit,
    onAddCustomer: () -> Unit
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCustomer) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TotalDuePanel(totalDue)
            }
            if (customers.isEmpty()) {
                item {
                    EmptyState()
                }
            } else {
                items(customers, key = { it.customer.id }) { item ->
                    CustomerRow(item, onClick = { onOpenCustomer(item.customer.id) })
                }
            }
        }
    }
}

@Composable
private fun TotalDuePanel(totalDue: Double) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("ಒಟ್ಟು ಬಾಕಿ · Total outstanding", color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                currency(totalDue),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ಇನ್ನೂ ಗ್ರಾಹಕರು ಇಲ್ಲ", style = MaterialTheme.typography.titleLarge)
        Text("Add your first customer to begin the ledger.")
    }
}

@Composable
private fun CustomerRow(item: CustomerBalance, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CustomerAvatar(item.customer.name, item.customer.photoUri)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.customer.name, style = MaterialTheme.typography.titleMedium)
                Text(item.customer.phone.ifBlank { "No phone" }, color = Color.DarkGray)
            }
            Text(
                currency(item.balance),
                color = if (item.balance > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AddCustomerScreen(
    onBack: () -> Unit,
    onSave: (String, String, String?, Double) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var opening by remember { mutableStateOf("") }
    val openingAmount = opening.toDoubleOrNull() ?: 0.0
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            photoUri = uri
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let {
            photoUri = saveLocalPhoto(context, it)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ಹೊಸ ಗ್ರಾಹಕ · Add customer", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CustomerAvatar(name.ifBlank { "Grama Khata" }, photoUri?.toString())
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { photoPicker.launch(arrayOf("image/*")) }) {
                    Text("Gallery")
                }
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            cameraLauncher.launch(null)
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    }
                ) {
                    Text("Camera")
                }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = opening,
            onValueChange = { opening = it },
            label = { Text("Opening balance") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.weight(1f))
        Button(
            enabled = name.isNotBlank(),
            onClick = { onSave(name, phone, photoUri?.toString(), openingAmount) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Save customer")
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun DetailScreen(
    customer: CustomerBalance?,
    entries: List<LedgerEntry>,
    paymentMessage: String?,
    onBack: () -> Unit,
    onGive: (Double, String?) -> Unit,
    onTake: (Double, String?) -> Unit,
    onDeleteCustomer: () -> Unit
) {
    val context = LocalContext.current
    var entryMode by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (entryMode != null) {
        AmountDialog(
            title = if (entryMode == ENTRY_GIVE) "Gave credit" else "Took payment",
            onDismiss = { entryMode = null },
            onSave = { amount, note ->
                if (entryMode == ENTRY_GIVE) onGive(amount, note) else onTake(amount, note)
                entryMode = null
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete customer?") },
            text = { Text("This removes the customer and local ledger entries.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteCustomer()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        customer?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CustomerAvatar(it.customer.name, it.customer.photoUri)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.customer.name, style = MaterialTheme.typography.headlineSmall)
                    Text("Balance ${currency(it.balance)}", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { ReminderHelper.share(context, it.customer.name, it.customer.phone, it.balance) },
                enabled = it.balance > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("WhatsApp Reminder")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Delete Customer")
            }
        }
        if (paymentMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                paymentMessage,
                color = if (
                    paymentMessage.contains("deleted", true) ||
                    paymentMessage.contains("queued", true)
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { entryMode = ENTRY_GIVE },
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                Text("+ Credit")
            }
            Button(
                onClick = { entryMode = ENTRY_TAKE },
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                Text("- Payment")
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Transaction log", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.isEmpty()) {
                item { Text("No transactions yet.") }
            } else {
                items(entries, key = { it.id }) { entry ->
                    EntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun AmountDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: (Double, String?) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = amountText.toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(enabled = amount > 0.0, onClick = { onSave(amount, note) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EntryRow(entry: LedgerEntry) {
    val isCredit = entry.type == ENTRY_GIVE
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (isCredit) "Gave credit" else "Took payment", fontWeight = FontWeight.Bold)
                Text(entry.note ?: date(entry.date), color = Color.DarkGray)
            }
            Text(
                (if (isCredit) "+" else "-") + currency(entry.amount),
                color = if (isCredit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ReportScreen(summary: DailySummary) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ದಿನದ ವರದಿ · Daily report", style = MaterialTheme.typography.headlineSmall)
        ReportMetric("Credit given", summary.creditGiven, MaterialTheme.colorScheme.error)
        ReportMetric("Cash received", summary.cashReceived, MaterialTheme.colorScheme.primary)
        ReportMetric("Net outstanding today", summary.netOutstanding, MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun ReportMetric(label: String, amount: Double, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f))
            Text(currency(amount), color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FeedbackScreen(
    userEmail: String?,
    message: String?,
    onSubmit: (String, Int) -> Unit,
    onSignOut: () -> Unit
) {
    var feedback by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf(5) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Customer feedback", style = MaterialTheme.typography.headlineSmall)
        Text(userEmail ?: "Offline mode: feedback needs Firebase login to reach backend.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { value ->
                OutlinedButton(onClick = { rating = value }) {
                    Text(if (value <= rating) "★" else "☆")
                }
            }
        }
        OutlinedTextField(
            value = feedback,
            onValueChange = { feedback = it },
            label = { Text("Write feedback") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSubmit(feedback, rating) },
            enabled = userEmail != null && feedback.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Send feedback to backend")
        }
        if (message != null) {
            Text(
                message,
                color = if (message.contains("sent", ignoreCase = true)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Logout")
        }
    }
}

@Composable
private fun ProfileScreen(
    profile: UserProfile,
    loading: Boolean,
    message: String?,
    onSave: (String, String, String?) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    var phone by remember(profile.phone) { mutableStateOf(profile.phone) }
    var photoUri by remember(profile.photoUri) { mutableStateOf(profile.photoUri) }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            photoUri = uri.toString()
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let {
            photoUri = saveLocalPhoto(context, it).toString()
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Profile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !loading) {
                Text("Refresh")
            }
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CustomerAvatar(name.ifBlank { profile.email.ifBlank { "Grama Khata" } }, photoUri)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "Add your name" }, style = MaterialTheme.typography.titleLarge)
                    Text(profile.email.ifBlank { "Offline profile" }, color = Color.DarkGray)
                }
            }
        }

        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { galleryPicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Gallery")
                }
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            cameraLauncher.launch(null)
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Camera")
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !loading,
                onClick = {
                    onSave(name, phone, photoUri)
                    editing = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(if (loading) "Saving..." else "Save profile")
            }
            OutlinedButton(
                onClick = {
                    name = profile.name
                    phone = profile.phone
                    photoUri = profile.photoUri
                    editing = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Cancel")
            }
        } else {
            ProfileField("Name", profile.name.ifBlank { "Not added" })
            ProfileField("Phone", profile.phone.ifBlank { "Not added" })
            Button(
                onClick = { editing = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Edit profile")
            }
        }

        if (message != null) {
            Text(
                message,
                color = if (message.contains("updated", ignoreCase = true) || message.contains("saved", ignoreCase = true)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ReminderSelectionScreen(
    customers: List<CustomerBalance>,
    onSendOne: (CustomerBalance, (String) -> Unit) -> Unit,
    onOpenWhatsApp: (CustomerBalance) -> Unit,
    onOpenSms: (CustomerBalance) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var dueOnly by remember { mutableStateOf(true) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var status by remember { mutableStateOf<String?>(null) }
    val filtered = customers.filter {
        (!dueOnly || it.balance > 0.0) &&
            (query.isBlank() || it.customer.name.contains(query, true) || it.customer.phone.contains(query))
    }
    val selectedCustomers = filtered.filter { it.customer.id in selectedIds }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Customer Reminders", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search customer") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsToggle("Show due customers only", dueOnly) { dueOnly = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { selectedIds = filtered.map { it.customer.id }.toSet() },
                modifier = Modifier.weight(1f)
            ) { Text("Select all") }
            OutlinedButton(
                onClick = { selectedIds = emptySet() },
                modifier = Modifier.weight(1f)
            ) { Text("Clear") }
        }
        Button(
            enabled = selectedCustomers.isNotEmpty(),
            onClick = {
                status = "Queueing ${selectedCustomers.size} reminders..."
                selectedCustomers.forEach { customer ->
                    onSendOne(customer) { status = it }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Send selected automatically")
        }
        if (status != null) {
            Text(status.orEmpty(), color = MaterialTheme.colorScheme.primary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered.isEmpty()) {
                item { Text("No customers found.") }
            } else {
                items(filtered, key = { it.customer.id }) { customer ->
                    ReminderCustomerRow(
                        item = customer,
                        checked = customer.customer.id in selectedIds,
                        onChecked = { checked ->
                            selectedIds = if (checked) {
                                selectedIds + customer.customer.id
                            } else {
                                selectedIds - customer.customer.id
                            }
                        },
                        onWhatsApp = { onOpenWhatsApp(customer) },
                        onSms = { onOpenSms(customer) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderCustomerRow(
    item: CustomerBalance,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onWhatsApp: () -> Unit,
    onSms: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onChecked)
                Column(Modifier.weight(1f)) {
                    Text(item.customer.name, fontWeight = FontWeight.Bold)
                    Text(item.customer.phone.ifBlank { "No phone" }, color = Color.DarkGray)
                }
                Text(currency(item.balance), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onWhatsApp, modifier = Modifier.weight(1f), enabled = item.customer.phone.isNotBlank()) {
                    Text("WhatsApp")
                }
                OutlinedButton(onClick = onSms, modifier = Modifier.weight(1f), enabled = item.customer.phone.isNotBlank()) {
                    Text("SMS")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AdminSettings,
    loading: Boolean,
    message: String?,
    onSave: (AdminSettings) -> Unit,
    onRefresh: () -> Unit,
    observeLogs: ((List<MessageLog>) -> Unit, (String) -> Unit) -> ListenerRegistration?
) {
    var smsEnabled by remember(settings.smsEnabled) { mutableStateOf(settings.smsEnabled) }
    var whatsappEnabled by remember(settings.whatsappEnabled) { mutableStateOf(settings.whatsappEnabled) }
    var smsAutomationEnabled by remember(settings.smsAutomationEnabled) { mutableStateOf(settings.smsAutomationEnabled) }
    var reminderFrequency by remember(settings.reminderFrequency) { mutableStateOf(settings.reminderFrequency) }
    var reminderTime by remember(settings.reminderTime) { mutableStateOf(settings.reminderTime) }
    var language by remember(settings.language) { mutableStateOf(settings.language) }
    var addKn by remember(settings.addCustomerKannadaTemplate) { mutableStateOf(settings.addCustomerKannadaTemplate) }
    var addEn by remember(settings.addCustomerEnglishTemplate) { mutableStateOf(settings.addCustomerEnglishTemplate) }
    var creditKn by remember(settings.creditKannadaTemplate) { mutableStateOf(settings.creditKannadaTemplate) }
    var creditEn by remember(settings.creditEnglishTemplate) { mutableStateOf(settings.creditEnglishTemplate) }
    var languageExpanded by remember { mutableStateOf(false) }
    var frequencyExpanded by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<MessageLog>>(emptyList()) }
    var logsError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val registration = observeLogs(
            { logs = it },
            { logsError = it }
        )
        onDispose { registration?.remove() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Admin Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
        }
        Text("Reminder messages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SettingsToggle("Enable SMS", smsEnabled) { smsEnabled = it }
        SettingsToggle("Enable WhatsApp", whatsappEnabled) { whatsappEnabled = it }
        SettingsToggle("SMS automation", smsAutomationEnabled) { smsAutomationEnabled = it }

        ExposedDropdownMenuBox(expanded = frequencyExpanded, onExpandedChange = { frequencyExpanded = !frequencyExpanded }) {
            OutlinedTextField(
                value = reminderFrequency.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() },
                onValueChange = {},
                readOnly = true,
                label = { Text("Reminder frequency") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = frequencyExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = frequencyExpanded, onDismissRequest = { frequencyExpanded = false }) {
                ReminderFrequency.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            reminderFrequency = option
                            frequencyExpanded = false
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = reminderTime,
            onValueChange = { reminderTime = it.take(5) },
            label = { Text("Reminder time HH:mm") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        ExposedDropdownMenuBox(expanded = languageExpanded, onExpandedChange = { languageExpanded = !languageExpanded }) {
            OutlinedTextField(
                value = language.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() },
                onValueChange = {},
                readOnly = true,
                label = { Text("Message language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                MessageLanguage.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            language = option
                            languageExpanded = false
                        }
                    )
                }
            }
        }

        Text("Customer added template", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TemplateField("Kannada", addKn) { addKn = it }
        TemplateField("English", addEn) { addEn = it }
        Text("Credit added template", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TemplateField("Kannada", creditKn) { creditKn = it }
        TemplateField("English", creditEn) { creditEn = it }

        Button(
            enabled = !loading,
            onClick = {
                onSave(
                    AdminSettings(
                        smsEnabled = smsEnabled,
                        whatsappEnabled = whatsappEnabled,
                        smsAutomationEnabled = smsAutomationEnabled,
                        reminderFrequency = reminderFrequency,
                        reminderTime = reminderTime,
                        language = language,
                        addCustomerKannadaTemplate = addKn,
                        addCustomerEnglishTemplate = addEn,
                        creditKannadaTemplate = creditKn,
                        creditEnglishTemplate = creditEn
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(if (loading) "Saving..." else "Save settings")
        }
        if (message != null) {
            Text(
                message,
                color = if (message.contains("saved", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        Text("Message delivery logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (logsError != null) {
            Text(logsError.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        if (logs.isEmpty() && logsError == null) {
            Text("No delivery logs yet.")
        } else {
            logs.take(12).forEach { log ->
                MessageLogRow(log)
            }
        }
    }
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun MessageLogRow(log: MessageLog) {
    val statusColor = when (log.status.lowercase(Locale.ROOT)) {
        "sent", "delivered" -> MaterialTheme.colorScheme.primary
        "failed", "provider_not_configured" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(log.channel.uppercase(Locale.ROOT), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(log.status.ifBlank { "pending" }, color = statusColor, fontWeight = FontWeight.Bold)
            }
            Text(log.customerName.ifBlank { "Customer" }, color = Color.DarkGray)
            Text(log.messagePreview.ifBlank { "No preview" }, maxLines = 2)
            Text("Retries: ${log.retryCount}", color = Color.DarkGray)
        }
    }
}

@Composable
private fun TemplateField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CustomerAvatar(name: String, photoUri: String?) {
    if (photoUri.isNullOrBlank()) {
        InitialsAvatar(name)
    } else {
        AsyncImage(
            model = photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
        )
    }
}

@Composable
private fun InitialsAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials(name),
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun initials(name: String): String =
    name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "GK" }

private fun currency(amount: Double): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(amount)

private fun date(timestamp: Long): String =
    SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(timestamp))

private fun saveLocalPhoto(context: Context, bitmap: Bitmap): Uri {
    val file = File(context.filesDir, "photo-${System.currentTimeMillis()}.jpg")
    file.outputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    }
    return Uri.fromFile(file)
}
