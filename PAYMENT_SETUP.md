# Grama Khata Real Payment Setup

## Razorpay

1. Create a Razorpay account and complete KYC.
2. In Razorpay Dashboard, copy the live `Key ID` and `Key Secret`.
3. Add a webhook:
   - URL: `https://asia-south1-YOUR_PROJECT_ID.cloudfunctions.net/razorpayWebhook`
   - Events: `payment_link.paid`, `payment_link.cancelled`, `payment_link.expired`
   - Secret: create a strong random value and save it for Firebase Functions.

## Firebase Functions

From the project root:

```bash
cd functions
npm install
npx firebase-tools login
npx firebase-tools functions:secrets:set RAZORPAY_KEY_ID
npx firebase-tools functions:secrets:set RAZORPAY_KEY_SECRET
npx firebase-tools functions:secrets:set RAZORPAY_WEBHOOK_SECRET
npx firebase-tools deploy --only functions,firestore:rules,firestore:indexes
```

The Functions code expects these environment values:

```text
RAZORPAY_KEY_ID
RAZORPAY_KEY_SECRET
RAZORPAY_WEBHOOK_SECRET
PAYMENT_CALLBACK_URL optional
```

For Firebase Functions v2, prefer secrets or parameterized config in production. Do not put Razorpay secrets in Android resources, BuildConfig, Firestore, or Remote Config.

## Android

1. Keep `app/google-services.json` from the same Firebase project.
2. In Firebase Console, enable Email/Password and Phone sign-in providers.
3. Add the app SHA-1 and SHA-256 fingerprints in Project settings.
4. Phone Auth OTP requires Firebase billing/Blaze for production quotas.
5. Build after Java is available:

```bash
set JAVA_HOME=C:\Path\To\JDK
.\gradlew.bat :app:assembleDebug
```

6. Login with Firebase Auth in the app.
7. Open a customer with a positive balance.
8. Tap `Send Payment Link`.
9. Confirm the amount. The app calls Firebase Functions, stores the real Razorpay short URL in Firestore, and opens WhatsApp directly with the secure link already attached.

## Firestore Indexes

`firestore.indexes.json` contains the required composite indexes for payment history and filters:

```text
payments: customerId ASC + createdAt DESC
payments: paymentStatus ASC + createdAt DESC
payments: customerId ASC + paymentStatus ASC
```

Deploy them with:

```bash
npx firebase-tools deploy --only firestore:indexes
```

## Firestore Data

Payment records are stored at:

```text
users/{uid}/payments/{paymentId}
```

Fields:

```json
{
  "customerId": 1,
  "customerName": "John",
  "amount": 500,
  "paymentLink": "https://rzp.io/i/abcd1234",
  "transactionId": "plink_xxx or pay_xxx",
  "paymentStatus": "Pending | Paid | Failed | Expired",
  "paymentMethod": "upi | card | netbanking | wallet | empty until paid",
  "createdAt": "server timestamp",
  "paidAt": "server timestamp or null"
}
```

Clients can read payment records. Only Firebase Admin SDK in Cloud Functions writes them.

## Forgot Password

- Email reset uses Firebase Auth `sendPasswordResetEmail`.
- Phone reset sends a Firebase Phone Auth OTP, verifies the code, then updates the password for the verified phone-auth account.
- If your existing users were created only with email/password, link their phone provider before relying on phone password reset for the same Firebase Auth user.
- Handle common setup errors by checking Phone Auth is enabled, SHA-1/SHA-256 are added, billing is active, and `google-services.json` is from the same Firebase project.

## Testing

1. Use Razorpay test credentials first.
2. Deploy Functions and rules.
3. Generate a payment link from the app.
4. Confirm a document appears under `users/{uid}/payments`.
5. Open the WhatsApp link and complete a test payment.
6. Confirm Razorpay calls the webhook and Firestore changes from `Pending` to `Paid`.
7. Open `View Payment History` in the app and confirm the status updates without refreshing.
8. Delete a customer and confirm local UI removal plus deletion of related cloud payment records.
