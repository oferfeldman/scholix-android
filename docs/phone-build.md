# Combined phone build

`integration/phone-build` combines the latest `main`, Inbar, Lemida, Drive, and Inbar connection/Webtop attachment fixes. Existing development-app data is retained when updating the same package with `adb install -r`.

Build against the current `release/beta13` Motion source rather than a developer-only Maven artifact:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug "-Pscholix.motionSource=C:/path/to/motion-android" "-Pscholix.applicationIdSuffix=.inbar.dev" "-Pscholix.directSms=true"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The source substitution is optional and leaves normal published dependency resolution intact. The combined navigation uses the available Motion navigation API, keeps every feature accessible (including Materials), and preserves Inbar's explicit-access SMS behavior. Course folders use Compose's content transition; destination icons retain the drawable resources supported by the available Motion release.

Direct SMS permission is restricted to this explicitly requested, privately sideloaded debug build. Release builds retain the regular SMS consent flow.

## 2026-10-31 origin/main merge

`integration/phone-build-main-merge` merges origin/main `3698730` (Lockerim, schedule and grades widgets, Schooly, Webtop/OpenU fixes) into phone-build `6bef9fe`. The navigation shell now uses main's `MotionNavFlow`, which the Motion `c97004a` source provides; Inbar's delayed-SMS handling, Lemida PDF view/Downloads, Drive and Materials are retained. With the flags above, 272 unit tests ran: 270 passed, one skipped, and `WebtopPasswordLoginTest.missingCredentialsExposeASafeActionableErrorWithoutNetwork` failed because it calls an unmocked `android.util.Log.w`. That test and `WebtopPlatform` are unchanged from origin/main. The APK builds as `com.feldman.scholix.inbar.dev` with the same development certificate. It has not been installed or checked on the phone yet.

`app/src/main/AndroidManifest.xml` declares RECEIVE_SMS for every build type (added upstream for Mashov/Lockerim before this merge). This contradicts the release-only consent claim in [inbar.md](inbar.md) and needs a separate decision.
