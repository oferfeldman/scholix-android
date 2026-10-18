# Lockerim integration

Scholix exposes **לוקרים** in the provider picker and as a separate navigation page.
Connect with the mobile number already registered with Lockerim, enter the SMS
code, and open the page to see school locker subscriptions. Open/Lock requests
Nearby devices permission on Android 12+ or location permission on Android 11.
Stand near the locker, enable Bluetooth, and wake the lock before operating it.
Reconnect is available on the page and in provider settings. Rental checkout
opens the official Lockerim website.

SMS code entry supports Android keyboard autofill and fresh-message reception
while signing in. With SMS permission, only Lockerim messages containing one
unambiguous code fill the field. Without permission, Android can offer access to
one selected message. Reception starts before send/resend; old inbox or spam
messages are not read. Review the filled code and tap Connect to finish.

## Reverse-engineering evidence

Inspected the public Android package `com.lockerimrb`, version **5.1.0.2354**
(version code 2354), retrieved from the APKPure latest-package download on
2026-10-18. Base APK SHA-256:
`60267e1685a51ddd2394e01acbdad0bf05672de94bef068543b890fb1410dc5f`.
The React Native bundle uses Hermes bytecode version 96. Its authentication
service, subscription mapper, PEM command builder, and Bluetooth transport were
disassembled and inspected. The package and disassembly remain local; no vendor
binary, assets, user identity, SMS code, or account tokens are committed.

Production origin: `https://rbapi.lockerim.co.il`.

| Method | Path | Request / response content |
| --- | --- | --- |
| POST | `/Account/Login` | `phoneNumber`, `RecaptchaKey` → `otpToken` |
| POST | `/Account/ResendLoginOtp` | `otpToken`, `RecaptchaKey` → replacement `otpToken` |
| POST | `/Account/ValidateLoginOtp` | `otpToken`, `otp`, `RecaptchaKey` → `token`, `refreshToken` |
| POST | `/Account/RefreshToken` | `refreshToken` → rotated token pair |
| GET | `/Subscription/GetMySubscriptions` | Bearer token → `subscriptions` array in `content` |
| POST | `/Subscription/ECRecognition` | Account subscription and recognition responses from its physical lock |

The server wraps results in `content`, `isSucceeded`, and `errors`. An empty
login was checked against production and returned HTTP 200 with a validation
error; an unauthenticated subscription request returned HTTP 401. No SMS was
sent for verification. reCAPTCHA uses the official app's public site key,
`https://localhost:3000/` WebView origin, and empty action, and must succeed before
each SMS request/verification. Sessions use Scholix's existing provider storage.

SMS request and resend responses must contain a nonempty JSON string
`otpToken`; missing, null, blank, numeric, and literal `"null"` tokens are
rejected before showing code entry. Android's `JSONObject.getString` coerces
JSON null to the text `"null"`, so it must not be used to validate a challenge.
A returned challenge confirms only that Lockerim accepted the request, not
that an SMS reached the handset; the UI describes that distinction explicitly.

## PEM Bluetooth interoperability

Service UUID: `6e406060-b5a3-f393-e0a9-e50e24dcca9e`.
Notifications: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`.
Writes: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`.
Devices advertise `PEM` followed by their MAC address without colons.

Only the signed-in account's server-returned subscriptions are offered. Initial
enrollment uses the account's `ec`, then reports the decrypted `^`/`%` recognition
responses. Lock and unlock use the APK's AES-128-ECB command format, account
`UserECId`, subscription `skda`, and a persisted increasing per-user/per-lock
counter. A `<PIC…>` response updates the counter for the next attempt. The UI
reports success only after receiving `<PUS>` (opened) or `<PLS>` (locked).
Scanning and GATT connections close on success, failure, timeout, or cancellation.

## Verification and limits

Focused JVM tests cover SMS request payloads, invalid challenge responses, error envelopes, refresh/retry with
rotated tokens, saved session/subscription data, and a Bluetooth encryption vector
calculated independently with Node's crypto implementation. Real SMS delivery,
authenticated subscription responses, initial physical enrollment, and physical
open/lock operation require a registered account and nearby hardware and have
not been verified end to end. This integration covers school PEM lockers;
residential SmartBox deliveries, permission sharing, and firmware updates are
not implemented.

Run the focused tests with:
`gradlew.bat :app:testDebugUnitTest --tests com.feldman.scholix.lockerim.LockerimTest`.
