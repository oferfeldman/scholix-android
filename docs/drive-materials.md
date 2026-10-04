# Google Drive materials

Materials is available from More and can be pinned in Settings → Navigation.
It is independent of university/Moodle sign-in.

Connect Google Drive once on the phone, browse My Drive or Shared with me, open
a course folder, and tap Follow as course folder. Followed folders appear under
Course folders. Their direct children refresh when the Materials page is visible
(every minute), and WorkManager schedules a refresh roughly every six hours
while the phone has a network connection. Android decides the precise background
execution time. Subfolders refresh when opened; the app does not crawl the entire
Drive or download documents during metadata sync.

PDFs open inside Scholix with page navigation and pinch zoom. Native Google Docs,
Sheets and Slides are exported to PDF on demand for this reader. Other formats
open in Google Drive or a suitable installed viewer. Save offline explicitly stores
a document in Scholix's private storage; Remove offline removes that copy only.
Offline copies are snapshots, with manual Update offline copy to replace them.
A newer-version label compares the online listing with the saved modification time.
Google's native export limit is 10 MB; other saved files are capped at 100 MB.

## Android authorization setup

The Codex Google Drive plugin connection does **not** authorize the Android app.
Scholix uses Google Identity Services AuthorizationClient and `drive.readonly`.
This scope is needed to list existing course folder hierarchies and read their
contents; `drive.file` only accesses files specifically granted to that app.
There are no upload, edit, sharing, deletion, server-token exchange or write-scope
operations. Google considers `drive.readonly` restricted; public distribution
requires the applicable consent/verification process. Personal development can
use External/Testing with explicitly listed test users. Testing grants may expire
and require another in-app authorization; domain administrators may block access.

1. Enable Google Drive API in a Google Cloud project.
2. Configure the OAuth app name, support/contact email, External audience and
   test users. Add `https://www.googleapis.com/auth/drive.readonly` to data access.
3. Create an Android OAuth client for `com.feldman.scholix` and the SHA-1 of the
   certificate actually signing the installed APK. Use `:app:signingReport` or
   `apksigner verify --print-certs` to verify it. A package suffix or different
   debug/release/Play signing certificate needs its own matching Android client.
4. Install this APK and authorize from Materials → Connect Google Drive.

Android registration is matched by package/certificate. No client secret,
Google-services JSON, Web OAuth client or backend is required for this device-only
flow. Do not embed the Codex connector's credentials or private folder IDs.

Access tokens stay in memory or Google's own token cache. Reauthorization is
silent when Google still permits it. A 401 clears Google's cached token and retries
once; a background job never launches a consent screen. The page offers reconnect
when consent is required. Folder IDs, listing metadata, account name and chosen
offline files are held under `noBackupFilesDir/drive-materials`, excluding them
from Android backup and device transfer. On-demand PDF previews and copies for
external viewers use private cache directories. FileProvider exposes only the
Drive share-cache subdirectory for these copies.
Only the current online PDF preview and current external-viewer copy are retained
in these cache directories; opening more materials does not accumulate previews.

Disconnect clears followed folders, cached metadata, offline copies and preview
files, and cancels automatic updates. It does not revoke Google's app grant;
the user can remove that grant in Google account settings. Cache removal must
succeed before a different account is installed. Failed metadata pagination or
downloads preserve the previous complete listing/copy; downloads use bounded
streaming and atomic replacement. Shared-drive parameters, resource keys and
shortcut targets are supported. No bearer token is forwarded on redirects.

## Validation

2026-10-04: built the actual Motion `beta52-local` from remote
`release/beta13` commit `bfbc2cf` in an isolated checkout, then installed its
development artifact in Maven Local. Only artifact signing was disabled in that
temporary checkout for local publication; the original Motion checkout's local
changes and public signing configuration were preserved. The complete Scholix
debug APK compiled and all 139 app unit tests passed, including 11 Drive tests.
APK signing verification confirmed the development certificate matched the
Gradle signing report. This does not constitute a device OAuth/PDF/worker check.

Full application: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
Drive's 11 MockWebServer tests execute actual production HTTP code: multi-page
and empty-page listings, sorting, partial failure, repeated token detection,
shared-with-me query, resource-key/shortcut routing, native PDF export, restricted
download rejection, interrupted/oversized download preservation, and redirect
credential handling. They do not contact Google or prove real OAuth/device behavior.

When the private Motion artifact is unavailable, the optional focused checker
compiles byte-identical Drive production code including its Compose page and
Google authorization/worker integration: `./gradlew --project-dir
tools/drive/android-checks testDebugUnitTest assembleDebug`. It builds an AAR,
not an installable Scholix APK, and excludes the real main navigation.

Manual device checks: connect and cancel consent; browse nested shared folders;
follow/unfollow; read a multi-page PDF and pinch zoom; save, enable airplane mode,
reopen and remove an offline file; reconnect after revoked permissions; disconnect
and confirm cached/offline copies are gone. No phone was connected for this batch.
