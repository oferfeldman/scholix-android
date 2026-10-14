# Drive focused Android checks

Optional fallback when Motion cannot be resolved. Compiles the application's
unmodified `drive/*.kt` files, including the Compose screen, Google authorization,
repository, PDF renderer and WorkManager worker, using the root catalog and app
SDK/auth version. Compiles/runs the actual Drive unit test file.

From the repository root with Android SDK configured:

```powershell
.\gradlew.bat --project-dir tools/drive/android-checks testDebugUnitTest assembleDebug
```

The output is a library AAR. It does not validate main navigation, launch Google
consent, contact Drive, render PDFs on a device or produce an installable APK.
Prefer the full app build when Motion is available.
