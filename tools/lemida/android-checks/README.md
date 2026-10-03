# Focused Lemida Android compile check

Run from the repository root with an installed Android SDK available through
`ANDROID_HOME` or this project's ignored `local.properties`:

```powershell
.\gradlew.bat --project-dir tools/lemida/android-checks testDebugUnitTest assembleDebug
```

The library compiles the app's actual Lemida login Activity, SMS receivers and
timer binding, WebView transport, encrypted cookie store, repository, sync worker
and Android-independent state/parser code. It reads AGP and library versions from
the root catalog, uses the same Android SDK/minimum API and Java target as the app,
and runs the existing focused unit tests. Gradle copies the selected production
files unmodified into ignored build directories; none of these Lemida classes are
replaced by test implementations. SDK levels, Java targets and the Play services
SMS dependency are read from the actual app build file.

The existing `LemidaBrowserLifecycleTest` device suite is also copied unmodified
and inherited by a Robolectric SDK 37 test runner. Eight cases drive the actual
browser through a controlled WebView subclass: page cancellation, client cleanup,
passive recovery, interactive Microsoft detection and delayed callbacks across
navigation or a reload at the same URL. The host fixture replaces the Main
coroutine dispatcher with an unconfined dispatcher for these controlled callbacks.
It does not render a browser, run the page JavaScript or contact Microsoft/Moodle.
The library check passed all 75 unit cases plus these eight host cases (83 total).
The two new reload cases reproduced the production failure before the fix and
passed afterward; their execution on a physical phone remains pending.

The worker's `MainActivity` destination resolves to a minimal compilation fixture;
the notification icon is the app's actual resource. The Motion homework screen and
the real MainActivity/navigation are excluded. This check builds an Android library
AAR, **not an installable Scholix APK**. It checks native Kotlin/API bindings,
unit behavior and controlled WebView callback handling under Robolectric; it does
not execute a real WebView engine, Google Play services, SMS delivery, Keystore,
WorkManager or notification routing on a device. An unmodified full app
build and the pending device checks remain required before shipping an updated APK.
