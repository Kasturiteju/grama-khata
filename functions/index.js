"use strict";

const crypto = require("crypto");
const admin = require("firebase-admin");
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {onSchedule} = require("firebase-functions/v2/scheduler");
const {logger} = require("firebase-functions");

admin.initializeApp();

const db = admin.firestore();
const messaging = admin.messaging();

const PAYMENT_PENDING = "Pending";
const PAYMENT_PAID = "Paid";
const OTP_EXPIRES_MS = 5 * 60 * 1000;
const OTP_RATE_LIMIT_MS = 60 * 1000;
const MAX_OTP_ATTEMPTS = 5;

const DEFAULT_ADD_CUSTOMER_KANNADA =
  "ನಮ್ಮ ಮೌಲ್ಯವಾದ ಗ್ರಾಹಕರಾಗಿರುವುದಕ್ಕೆ ಧನ್ಯವಾದಗಳು.\nನಿಮ್ಮ ಬಾಕಿ ಮೊತ್ತ ₹{balance}.\nಕೆಳಗಿನ UPI ಲಿಂಕ್ ಮೂಲಕ ಪಾವತಿ ಮಾಡಿ.";
const DEFAULT_ADD_CUSTOMER_ENGLISH =
  "Thanks for being a valuable customer.\nYour outstanding balance is ₹{balance}.\nPlease pay using the UPI link below.";
const DEFAULT_CREDIT_KANNADA =
  "ನಮಸ್ಕಾರ {customer_name} ಅವರೇ,\n\nನಿಮ್ಮ ಬಾಕಿ ಮೊತ್ತ ₹{balance}.\nದಯವಿಟ್ಟು ಅನುಕೂಲವಾದಾಗ ಪಾವತಿಸಿ.\n\nಪಾವತಿಗಾಗಿ ಕೆಳಗಿನ UPI ಲಿಂಕ್ ಬಳಸಿ.";
const DEFAULT_CREDIT_ENGLISH =
  "Dear {customer_name},\n\nYour outstanding balance is ₹{balance}.\nPlease pay at your convenience.\n\nUse the UPI payment link below.";

exports.requestPasswordResetOtp = onCall({region: "asia-south1"}, async (request) => {
  const phone = normalizeIndianPhone(String(request.data.phone || ""));
  if (!phone) {
    throw new HttpsError("invalid-argument", "Enter a valid registered mobile number.");
  }

  const userSnapshot = await db.collection("users")
    .where("phoneNormalized", "==", phone)
    .limit(1)
    .get();
  if (userSnapshot.empty) {
    throw new HttpsError("not-found", "No account found for this registered mobile number.");
  }

  const userDoc = userSnapshot.docs[0];
  const now = Date.now();
  const latestOtp = await userDoc.ref.collection("passwordResetOtps")
    .orderBy("createdAtMillis", "desc")
    .limit(1)
    .get();
  const latest = latestOtp.docs[0]?.data();
  if (latest?.createdAtMillis && now - latest.createdAtMillis < OTP_RATE_LIMIT_MS) {
    throw new HttpsError("resource-exhausted", "Please wait before requesting another OTP.");
  }

  const otp = String(crypto.randomInt(100000, 999999));
  const resetRef = userDoc.ref.collection("passwordResetOtps").doc();
  const resetToken = resetRef.id;
  await resetRef.set({
    resetToken,
    uid: userDoc.id,
    phoneHash: sha256(phone),
    otpHash: sha256(`${otp}:${resetToken}`),
    attempts: 0,
    verified: false,
    used: false,
    createdAtMillis: now,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    expiresAt: admin.firestore.Timestamp.fromMillis(now + OTP_EXPIRES_MS),
  });

  await logAudit(userDoc.id, "PASSWORD_RESET_OTP_REQUESTED", {phoneHash: sha256(phone)});
  const settings = await loadSettings(userDoc.id);
  const otpDelivery = await sendSms(phone, `Grama-Khata OTP: ${otp}. This code expires in 5 minutes.`, userDoc.ref, {
    type: "password_reset_otp",
    resetToken,
  }, settings);
  if (!["sent", "delivered", "queued"].includes(otpDelivery.status)) {
    throw new HttpsError("unavailable", "OTP sending failed. Check SMS webhook environment settings.");
  }

  return {resetToken};
});

exports.confirmPasswordResetOtp = onCall({region: "asia-south1"}, async (request) => {
  const resetToken = String(request.data.resetToken || "");
  const otp = String(request.data.otp || "").trim();
  if (!resetToken || !/^\d{6}$/.test(otp)) {
    throw new HttpsError("invalid-argument", "Enter the 6 digit OTP.");
  }

  const reset = await findOtp(resetToken);
  await validateOtp(reset.ref, reset.data, otp, resetToken);

  await reset.ref.set({
    verified: true,
    verifiedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, {merge: true});
  await logAudit(reset.uid, "PASSWORD_RESET_OTP_VERIFIED", {});
  return {verified: true};
});

exports.updatePasswordWithOtp = onCall({region: "asia-south1"}, async (request) => {
  const resetToken = String(request.data.resetToken || "");
  const password = String(request.data.password || "");
  if (password.length < 6) {
    throw new HttpsError("invalid-argument", "Password must be at least 6 characters.");
  }

  const reset = await findOtp(resetToken);
  if (!reset.data.verified || reset.data.used) {
    throw new HttpsError("failed-precondition", "Verify OTP before setting a new password.");
  }
  if (reset.data.expiresAt.toMillis() < Date.now()) {
    throw new HttpsError("deadline-exceeded", "OTP expired. Please request a new code.");
  }

  await admin.auth().updateUser(reset.uid, {password});
  await admin.auth().revokeRefreshTokens(reset.uid);
  await reset.ref.set({
    used: true,
    usedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, {merge: true});
  await logAudit(reset.uid, "PASSWORD_CHANGED", {});
  return {message: "Password changed successfully."};
});

exports.updatePasswordAfterPhoneOtp = onCall({region: "asia-south1"}, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Verify your mobile OTP before setting a new password.");
  }

  const phone = normalizeIndianPhone(String(request.auth.token.phone_number || ""));
  const password = String(request.data.password || "");
  if (!phone) {
    throw new HttpsError("failed-precondition", "Firebase phone verification was not completed.");
  }
  if (password.length < 6) {
    throw new HttpsError("invalid-argument", "Password must be at least 6 characters.");
  }

  const userSnapshot = await db.collection("users")
    .where("phoneNormalized", "==", phone)
    .limit(1)
    .get();
  if (userSnapshot.empty) {
    throw new HttpsError("not-found", "No account found for this registered mobile number.");
  }

  const userDoc = userSnapshot.docs[0];
  const targetUser = await admin.auth().getUser(userDoc.id);
  const hasPasswordLogin = targetUser.providerData.some((provider) => provider.providerId === "password");
  if (!targetUser.email || !hasPasswordLogin) {
    throw new HttpsError("failed-precondition", "This mobile number is not linked to an email/password account.");
  }

  await admin.auth().updateUser(userDoc.id, {password});
  await admin.auth().revokeRefreshTokens(userDoc.id);
  await logAudit(userDoc.id, "PASSWORD_CHANGED_BY_FIREBASE_PHONE_OTP", {
    phoneHash: sha256(phone),
    verifierUid: request.auth.uid,
  });
  return {message: "Password changed successfully."};
});

exports.createUpiPaymentRequest = onCall({region: "asia-south1"}, async (request) => {
  const {userId, customerId, customerName, customerPhone, amount} = validatedPaymentInput(request);
  const settings = await loadSettings(userId);
  if (!settings.upiId) {
    throw new HttpsError("failed-precondition", "Set the shop owner's UPI ID in Admin Settings first.");
  }

  const paymentRef = db.collection("users").doc(userId).collection("payments").doc();
  const referenceId = `GK-${customerId}-${paymentRef.id.slice(0, 8)}`;
  const note = `Grama-Khata ${customerName} ${referenceId}`;
  const paymentLink = buildUpiLink(settings.upiId, settings.shopName, amount, note);

  const payment = {
    customerId,
    customerName,
    customerPhone,
    amount,
    paymentLink,
    qrCodeUrl: "",
    referenceId,
    transactionId: "",
    paymentStatus: PAYMENT_PENDING,
    paymentMethod: "UPI",
    note,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    paidAt: null,
  };
  await paymentRef.set(payment);

  return {
    paymentId: paymentRef.id,
    ...payment,
  };
});

exports.sendCustomerAddedMessage = onCall({region: "asia-south1"}, async (request) => {
  return sendCustomerMessage(request, "customer_added");
});

exports.sendCreditAddedMessage = onCall({region: "asia-south1"}, async (request) => {
  return sendCustomerMessage(request, "credit_added");
});

exports.markUpiPaymentReceived = onCall({region: "asia-south1"}, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Login is required to mark payments.");
  }
  const userId = request.auth.uid;
  const paymentId = String(request.data.paymentId || "");
  const transactionRef = String(request.data.transactionRef || "").trim();
  if (!paymentId) {
    throw new HttpsError("invalid-argument", "Payment ID is required.");
  }

  const paymentRef = db.collection("users").doc(userId).collection("payments").doc(paymentId);
  const paymentDoc = await paymentRef.get();
  if (!paymentDoc.exists) {
    throw new HttpsError("not-found", "Payment record was not found.");
  }

  await paymentRef.set({
    paymentStatus: PAYMENT_PAID,
    transactionId: transactionRef,
    paymentMethod: "UPI",
    paidAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, {merge: true});
  await logAudit(userId, "PAYMENT_MARKED_RECEIVED", {
    paymentId,
    transactionRef: transactionRef ? sha256(transactionRef) : "",
  });
  await sendUserNotification(userId, "Payment received", "UPI payment was marked as received.");
  return {paymentStatus: PAYMENT_PAID};
});

exports.deleteCustomerAndPayments = onCall({region: "asia-south1"}, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Login is required to delete cloud records.");
  }

  const userId = request.auth.uid;
  const customerId = Number(request.data.customerId);
  if (!Number.isFinite(customerId) || customerId <= 0) {
    throw new HttpsError("invalid-argument", "A valid customer id is required.");
  }

  const userRef = db.collection("users").doc(userId);
  const payments = await userRef.collection("payments").where("customerId", "==", customerId).get();
  const batch = db.batch();

  batch.delete(userRef.collection("customers").doc(String(customerId)));
  payments.docs.forEach((doc) => batch.delete(doc.ref));
  await batch.commit();

  return {deletedPayments: payments.size};
});

exports.sendScheduledSmsReminders = onSchedule({
  region: "asia-south1",
  schedule: "every 1 hours",
  timeZone: "Asia/Kolkata",
}, async () => {
  const now = new Date();
  const hhmm = now.toLocaleTimeString("en-GB", {
    timeZone: "Asia/Kolkata",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  const settingsSnapshot = await db.collectionGroup("settings")
    .where("smsAutomationEnabled", "==", true)
    .get();

  for (const settingsDoc of settingsSnapshot.docs) {
    if (settingsDoc.id !== "admin") continue;
    const settings = await loadSettings(settingsDoc.ref.parent.parent.id);
    if (!settings.smsEnabled || !sameHour(settings.reminderTime, hhmm)) continue;
    const userRef = settingsDoc.ref.parent.parent;
    const userId = userRef.id;
    const markerRef = userRef.collection("automationRuns").doc(`sms-${settings.reminderFrequency}`);
    const marker = await markerRef.get();
    if (!shouldRunReminder(marker.data()?.lastRunAt?.toMillis(), settings.reminderFrequency)) continue;

    const customers = await userRef.collection("customers").get();
    const entries = await userRef.collection("ledger_entries").get();
    const balances = computeBalances(entries.docs.map((doc) => doc.data()));

    for (const customerDoc of customers.docs) {
      const customer = customerDoc.data();
      const customerId = Number(customer.localId || customerDoc.id);
      const balance = balances.get(customerId) || 0;
      if (balance <= 0 || !customer.phone) continue;
      const paymentLink = settings.upiId ? buildUpiLink(
        settings.upiId,
        settings.shopName,
        balance,
        `Grama-Khata ${customer.name || "Customer"} GK-${customerId}`,
      ) : "";
      const message = buildCustomerMessage(
        "credit_added",
        customer.name || "Customer",
        balance,
        paymentLink,
        settings,
      );
      await sendSms(normalizeIndianPhone(customer.phone), message, userRef, {
        type: "scheduled_sms_reminder",
        customerId,
        customerName: customer.name || "",
      }, settings);
    }

    await markerRef.set({
      lastRunAt: admin.firestore.FieldValue.serverTimestamp(),
      frequency: settings.reminderFrequency,
    }, {merge: true});
    await logAudit(userId, "SCHEDULED_SMS_REMINDERS_SENT", {frequency: settings.reminderFrequency});
  }
});

async function sendCustomerMessage(request, eventType) {
  const {userId, customerId, customerName, customerPhone, amount} = validatedPaymentInput(request, true);
  const settings = await loadSettings(userId);
  const payment = settings.upiId && amount > 0 ?
    await createPaymentRecord(userId, customerId, customerName, customerPhone, amount, settings) :
    {paymentLink: "", qrCodeUrl: "", referenceId: ""};
  const message = buildCustomerMessage(eventType, customerName, amount, payment.paymentLink, settings);
  const userRef = db.collection("users").doc(userId);
  const results = [];

  if (settings.smsEnabled) {
    results.push(await sendSms(customerPhone, message, userRef, {type: eventType, customerId, paymentId: payment.paymentId || ""}, settings));
  }
  if (settings.whatsappEnabled) {
    results.push(await sendWhatsApp(customerPhone, message, userRef, {type: eventType, customerId, paymentId: payment.paymentId || ""}, settings));
  }
  return {payment, delivery: results};
}

async function createPaymentRecord(userId, customerId, customerName, customerPhone, amount, settings) {
  const paymentRef = db.collection("users").doc(userId).collection("payments").doc();
  const referenceId = `GK-${customerId}-${paymentRef.id.slice(0, 8)}`;
  const note = `Grama-Khata ${customerName} ${referenceId}`;
  const paymentLink = buildUpiLink(settings.upiId, settings.shopName, amount, note);
  const payment = {
    customerId,
    customerName,
    customerPhone,
    amount,
    paymentLink,
    qrCodeUrl: "",
    referenceId,
    transactionId: "",
    paymentStatus: PAYMENT_PENDING,
    paymentMethod: "UPI",
    note,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    paidAt: null,
  };
  await paymentRef.set(payment);
  return {paymentId: paymentRef.id, ...payment};
}

function validatedPaymentInput(request, allowZeroAmount = false) {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Login is required.");
  }
  const customerId = Number(request.data.customerId);
  const customerName = String(request.data.customerName || "").trim();
  const customerPhone = normalizeIndianPhone(String(request.data.customerPhone || ""));
  const amount = Number(request.data.amount);
  if (!Number.isFinite(customerId) || customerId <= 0) {
    throw new HttpsError("invalid-argument", "A valid customer is required.");
  }
  if (!customerName) {
    throw new HttpsError("invalid-argument", "Customer name is required.");
  }
  if (!Number.isFinite(amount) || amount < 0 || (!allowZeroAmount && amount <= 0)) {
    throw new HttpsError("invalid-argument", "Payment amount must be greater than zero.");
  }
  return {userId: request.auth.uid, customerId, customerName, customerPhone, amount};
}

async function loadSettings(userId) {
  const snapshot = await db.collection("users").doc(userId).collection("settings").doc("admin").get();
  const data = snapshot.data() || {};
  return {
    upiId: String(data.upiId || "").trim(),
    shopName: String(data.shopName || "GramaKhataShop").trim(),
    smsEnabled: data.smsEnabled !== false,
    whatsappEnabled: data.whatsappEnabled !== false,
    smsAutomationEnabled: data.smsAutomationEnabled === true,
    reminderFrequency: ["DAILY", "WEEKLY", "MONTHLY"].includes(data.reminderFrequency) ? data.reminderFrequency : "WEEKLY",
    reminderTime: String(data.reminderTime || "09:00").trim(),
    language: ["KANNADA", "ENGLISH", "BOTH"].includes(data.language) ? data.language : "BOTH",
    addCustomerKannadaTemplate: data.addCustomerKannadaTemplate || DEFAULT_ADD_CUSTOMER_KANNADA,
    addCustomerEnglishTemplate: data.addCustomerEnglishTemplate || DEFAULT_ADD_CUSTOMER_ENGLISH,
    creditKannadaTemplate: data.creditKannadaTemplate || DEFAULT_CREDIT_KANNADA,
    creditEnglishTemplate: data.creditEnglishTemplate || DEFAULT_CREDIT_ENGLISH,
  };
}

function buildCustomerMessage(eventType, customerName, amount, paymentLink, settings) {
  const templates = eventType === "customer_added" ?
    [settings.addCustomerKannadaTemplate, settings.addCustomerEnglishTemplate] :
    [settings.creditKannadaTemplate, settings.creditEnglishTemplate];
  const parts = [];
  if (settings.language === "KANNADA" || settings.language === "BOTH") {
    parts.push(renderTemplate(templates[0], customerName, amount));
  }
  if (settings.language === "ENGLISH" || settings.language === "BOTH") {
    parts.push(renderTemplate(templates[1], customerName, amount));
  }
  if (paymentLink) {
    parts.push(`Pay Now: ${paymentLink}`);
  }
  parts.push("- Grama-Khata");
  return parts.join("\n\n");
}

function renderTemplate(template, customerName, amount) {
  return template
    .replaceAll("{customer_name}", customerName)
    .replaceAll("{balance}", formatAmount(amount));
}

function buildUpiLink(upiId, shopName, amount, note) {
  const query = new URLSearchParams({
    pa: upiId,
    pn: shopName,
    am: amount.toFixed(2),
    cu: "INR",
  });
  return `upi://pay?${query.toString()}`;
}

function computeBalances(entries) {
  const balances = new Map();
  entries.forEach((entry) => {
    const customerId = Number(entry.customerLocalId);
    if (!Number.isFinite(customerId)) return;
    const amount = Number(entry.amount || 0);
    const current = balances.get(customerId) || 0;
    balances.set(customerId, current + (entry.type === "TAKE" ? -amount : amount));
  });
  return balances;
}

function sameHour(configured, current) {
  const wantedHour = String(configured || "09:00").slice(0, 2);
  const currentHour = String(current || "").slice(0, 2);
  return wantedHour === currentHour;
}

function shouldRunReminder(lastRunAtMillis, frequency) {
  if (!lastRunAtMillis) return true;
  const elapsed = Date.now() - lastRunAtMillis;
  const day = 24 * 60 * 60 * 1000;
  if (frequency === "DAILY") return elapsed >= day;
  if (frequency === "MONTHLY") return elapsed >= 28 * day;
  return elapsed >= 7 * day;
}

async function sendSms(phone, message, userRef, meta, settings = {}) {
  return sendProviderMessage(
    "sms",
    phone,
    message,
    userRef,
    meta,
    process.env.SMS_WEBHOOK_URL,
    process.env.SMS_API_KEY,
  );
}

async function sendWhatsApp(phone, message, userRef, meta, settings = {}) {
  return sendProviderMessage("whatsapp", phone, message, userRef, meta, process.env.WHATSAPP_WEBHOOK_URL || settings.whatsappProviderUrl, settings.whatsappProviderApiKey);
}

async function sendProviderMessage(channel, phone, message, userRef, meta, webhookUrl, apiKey) {
  const logRef = userRef.collection("messageLogs").doc();
  const baseLog = {
    channel,
    phoneHash: phone ? sha256(phone) : "",
    phoneLast4: phone ? phone.slice(-4) : "",
    customerName: meta.customerName || "",
    messagePreview: message.slice(0, 120),
    status: "pending",
    retryCount: 0,
    meta,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  await logRef.set(baseLog);

  if (!webhookUrl) {
    await logRef.set({
      status: "provider_not_configured",
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
    return {channel, status: "provider_not_configured"};
  }

  let lastError = "";
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      await logRef.set({
        status: "pending",
        retryCount: attempt - 1,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      }, {merge: true});
      const headers = {"Content-Type": "application/json"};
      if (apiKey) headers.Authorization = `Bearer ${apiKey}`;
      const response = await fetch(webhookUrl, {
        method: "POST",
        headers,
        body: JSON.stringify({to: phone, message, channel, meta}),
      });
      const body = await response.text();
      const status = response.ok ? "sent" : "failed";
      await logRef.set({
        status,
        providerStatus: response.status,
        providerResponse: body.slice(0, 500),
        retryCount: attempt - 1,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      }, {merge: true});
      if (response.ok) return {channel, status};
      lastError = body || `HTTP ${response.status}`;
    } catch (error) {
      logger.error(`${channel} delivery failed`, error);
      lastError = error.message;
      await logRef.set({
        status: "failed",
        retryCount: attempt,
        error: lastError,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      }, {merge: true});
    }
  }
  return {channel, status: "failed", error: lastError};
}

async function findOtp(resetToken) {
  const snapshot = await db.collectionGroup("passwordResetOtps")
    .where("resetToken", "==", resetToken)
    .limit(1)
    .get();
  if (snapshot.empty) {
    throw new HttpsError("not-found", "OTP request was not found. Please request a new code.");
  }
  const doc = snapshot.docs[0];
  return {ref: doc.ref, data: doc.data(), uid: doc.data().uid};
}

async function validateOtp(ref, data, otp, resetToken) {
  if (data.used) {
    throw new HttpsError("failed-precondition", "This OTP has already been used.");
  }
  if (data.expiresAt.toMillis() < Date.now()) {
    throw new HttpsError("deadline-exceeded", "OTP expired. Please request a new code.");
  }
  if ((data.attempts || 0) >= MAX_OTP_ATTEMPTS) {
    throw new HttpsError("resource-exhausted", "Too many invalid OTP attempts. Please request a new code.");
  }
  const valid = data.otpHash === sha256(`${otp}:${resetToken}`);
  if (!valid) {
    await ref.set({
      attempts: admin.firestore.FieldValue.increment(1),
      lastFailedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
    throw new HttpsError("invalid-argument", "Invalid OTP. Please check the code and try again.");
  }
}

async function logAudit(userId, action, details) {
  await db.collection("users").doc(userId).collection("auditLogs").add({
    action,
    details,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
}

async function sendUserNotification(userId, title, body) {
  const tokenSnapshot = await db.collection("users").doc(userId).collection("fcmTokens").get();
  const tokens = tokenSnapshot.docs.map((doc) => doc.id);
  if (!tokens.length) return;

  const response = await messaging.sendEachForMulticast({
    tokens,
    notification: {title, body},
    data: {title, body, source: "payments"},
  });

  const cleanup = [];
  response.responses.forEach((result, index) => {
    if (!result.success && isInvalidTokenError(result.error)) {
      cleanup.push(tokenSnapshot.docs[index].ref.delete());
    }
  });
  await Promise.all(cleanup);
}

function normalizeIndianPhone(phone) {
  const digits = phone.replace(/\D/g, "");
  if (!digits) return "";
  if (digits.length === 10) return `+91${digits}`;
  if (digits.startsWith("91")) return `+${digits}`;
  return phone.trim().startsWith("+") ? phone.trim() : `+${digits}`;
}

function formatAmount(amount) {
  return Number(amount).toLocaleString("en-IN", {maximumFractionDigits: 2});
}

function sha256(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

function isInvalidTokenError(error) {
  return [
    "messaging/invalid-registration-token",
    "messaging/registration-token-not-registered",
  ].includes(error?.code);
}
