# Inbar (Bar-Ilan) provider

Choose **Inbar (Bar-Ilan)** during onboarding or in Settings → Add provider. Enter the ID/passport and mobile number registered with Bar-Ilan and submit the same login form used by the other providers. Inbar requests and verifies the SMS in the background while a Material `ContainedLoadingIndicator` is shown. There is no SMS form or Verify button. This provider is separate from the existing Bar-Ilan/Michlol math provider.

ID and mobile are saved through the same `PlatformStorage` provider preferences used by other platforms, as soon as the portal accepts an SMS request. Subsequent sign-ins prefill the shared fields with those details. If a grades request finds an expired session, the Grades page signs in behind its loading indicator and retries after verification. Declined consent, invalid credentials/codes, unavailable reception, network failures, and a 90-second SMS timeout return to the existing login error display; Grades offers **Retry login**. Credentials remain editable in the common forms. Only an interactive sign-in requests an SMS, and each successful reauthentication replaces the existing provider under the same ID.

Inbar implements the same `Platform` interface, `LoginFields` constructor and factory registration as the existing providers. Its `toJson` / reflective `fromJson` restoration and `PlatformStorage` saves use the existing provider path. Onboarding, Add Provider, and Edit Provider all render `DynamicLoginFields`; the UI-free `HiddenInbarLogin` handles the two-step protocol, like the hidden sign-in helpers used by other providers. The shared Edit Provider screen supports account naming, course renaming, hiding, and ordering. Reauthentication retains the provider ID, cached courses, and course customizations.

Regular builds request neither READ_SMS nor RECEIVE_SMS. Google Play services' SMS User Consent listener starts before requesting the message; Android asks to share the single verification message. Accepting submits the code immediately, without another app screen or button. If consent is declined, Play services is unavailable, or no eligible message arrives, the login form shows a failure and allows retry.

A privately sideloaded debug build can enable direct reception with `-Pscholix.directSms=true`. This adds RECEIVE_SMS only to the debug manifest. After a one-time permission grant, new Inbar/Bar-Ilan-branded SMS messages containing a unique five-digit code are submitted during the active sign-in, within 90 seconds of its request. The listener registers before the HTTP request and buffers early delivery; the first received code is verified once, without a retry loop. It stops when sign-in ends, does not read the inbox, and does not log or save messages or codes. Already-granted permission skips Play services startup and consent. Permission remains subject to Android revocation, including unused-app permission resets. If permission is unavailable, single-message consent is used. Release builds always omit this permission; Google Play restricts SMS permissions to approved use cases ([policy](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)).

## Portal protocol

This is a direct HTTPS Web Forms client, not a browser automation dependency or a JSON grades API. The authenticated endpoint `https://inbar.biu.ac.il/Live/StudentGradesList.aspx` returns server-rendered course rows, final grades, and hidden assignment/attempt tables. Inspection of the supplied pages and authenticated requests did not identify a separate grades AJAX endpoint.

Login uses `/Live/Login.aspx`, followed by the SMS form `/Live/Authenticate.aspx`. Each POST preserves the current form's successful controls, including `__PageDataKey` and `__EVENTVALIDATION`, and submits only the chosen button. Switching academic years posts the year selector with `__EVENTTARGET` and fresh form state. Requests preserve cookies, validate TLS normally, and reject redirects outside the portal before sending credentials.

The login ReturnUrl goes directly to grades. If verification lands on the grades endpoint, its response is reused rather than fetched again. Year switches post the latest grades form directly, removing a GET before each older-year request; refreshing the same year still fetches fresh data. Restored providers reuse HTTPS connections with separate cookie jars. This removes redundant client work; portal response and mobile SMS delivery times still depend on the services. Debug timing logs contain durations only, never account details, messages, or codes.

Available years are loaded when adding the provider. Course keys include the year, so opening an older course requests its own academic year. Blank final grades remain pending. Schedule, attendance, and messages are not implemented. An expired session uses hidden SMS reauthentication from Grades or the shared provider form; background refresh never requests an SMS.

Teaching groups sharing the same course-code prefix, subject, year, and period are grouped for display. Empty companion rows stay in `relatedGroups` metadata rather than creating duplicate empty course entries. Independently graded groups remain separate, and subjects spanning multiple years show the year in the course picker. Existing cached courses are normalized when restoring the provider.

Session cookies are encrypted using an app-specific Android Keystore AES-GCM key. Verification codes are never persisted. Identity, registered mobile, and cached course/grade data use the existing app-private provider storage. Restoring data to a device without the Keystore key requires sign-in again.

## Build with current Motion source

The app uses Motion `beta49-local`, which is not publicly published. Clone the private `feldmandev/motion-android` repository and check out `release/beta13` (tested source commit `3914022`). Access to that repository is required. The branch name and artifact version are distinct. Scholix's scaffolds, settings rows, pickers, buttons, fonts, palette preferences, and navigation contrast have been migrated to this branch's APIs.

Install JDK 25 and Android SDK platform 37 / build tools 37.0.0, configure `local.properties` with `sdk.dir`, and accept the SDK licenses. The Gradle wrapper downloads Gradle automatically. The app and Motion use Android Gradle plugin 9.3.3.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug "-Pscholix.motionSource=C:/path/to/motion-android" "-Pscholix.applicationIdSuffix=.inbar.dev"
```

`scholix.motionSource` enables composite dependency substitution without publishing or copying the private Motion library. `scholix.applicationIdSuffix` installs a debug app alongside an existing Scholix installation. For Firebase-enabled builds, supply your own ignored `app/google-services.json`; otherwise the Google Services plugin is skipped for local builds.

To build the private automatic-SMS variant, add `"-Pscholix.directSms=true"` to the full app command above, install the APK, and allow SMS in Android's app permissions. This flag does not affect release manifests or the focused harness.

The focused optional device harness compiles the same production Inbar sources and tests without Motion or Firebase:

```powershell
.\gradlew.bat :inbar-testapp:testDebugUnitTest :inbar-testapp:assembleDebug "-Pscholix.inbarTestApp=true"
```

Tests use synthetic HTML and cover fresh login state, wrong-code retry, year postbacks, nested grade tables, pending courses, expired sessions, resend cooldown, redirect restrictions, cookie restore, and SMS extraction. Real student pages, credentials, cookies, and grades must stay outside the repository.

## Validation

The full development APK builds with the composite Motion source, and all 26 app unit tests pass (15 Inbar tests and 11 existing Webtop tests). The focused Inbar harness passes the same 15 Inbar tests. The request tests verify that a grades landing response is reused, successive year switches POST the latest form state, and same-year refresh still GETs fresh data. Five instrumentation tests pass on the connected Android device: Keystore encryption round trips with random IVs, tampered ciphertext rejection, reflective provider restore with cached teaching-group normalization and missing-key recovery, saved-details/provider-ID/course-customization preservation through the common storage path, and the `LoginFields` constructor. Real SMS consent autofill, verification, and grade display have been user-tested. A live hidden direct-SMS sign-in completed on the device in about 3.6 seconds: request 0.657 s, code received 2.522 s after starting, then verification and grades 1.088 s. The saved account remained logged in with one provider entry. These timings describe one successful attempt, not a delivery-time guarantee. The release manifest excludes RECEIVE_SMS even when the debug opt-in is enabled. The development application ID is `com.feldman.scholix.inbar.dev`, leaving an existing Scholix installation separate. Final-only grades display without a component-average card.

Build the device tests with the same source/suffix properties:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest "-Pscholix.motionSource=C:/path/to/motion-android" "-Pscholix.applicationIdSuffix=.inbar.dev"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.feldman.scholix.api.platforms.InbarSessionTest com.feldman.scholix.inbar.dev.test/androidx.test.runner.AndroidJUnitRunner
```

These synthetic instrumentation tests use isolated preferences. Installing with `-r` and running instrumentation directly retains development app data; Gradle's connected-test task can uninstall the target app afterward.
