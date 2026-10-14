# Combined phone build

`integration/phone-build` combines the latest `main`, Inbar, Lemida, Drive, and Inbar connection/Webtop attachment fixes. Existing development-app data is retained when updating the same package with `adb install -r`.

Build against the current `release/beta13` Motion source rather than a developer-only Maven artifact:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug "-Pscholix.motionSource=C:/path/to/motion-android" "-Pscholix.applicationIdSuffix=.inbar.dev" "-Pscholix.directSms=true"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The source substitution is optional and leaves normal published dependency resolution intact. The combined navigation uses the available Motion navigation API, keeps every feature accessible (including Materials), and preserves Inbar's explicit-access SMS behavior. Course folders use Compose's content transition; destination icons retain the drawable resources supported by the available Motion release.

Direct SMS permission is restricted to this explicitly requested, privately sideloaded debug build. Release builds retain the regular SMS consent flow.
