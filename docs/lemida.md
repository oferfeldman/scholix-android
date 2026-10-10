# Lemida homework on the phone

Open **More → Homework** in Scholix and allow notifications. A remembered
Microsoft account is selected by tapping its matching email tile automatically,
before considering an email-entry field. In the direct-SMS development build,
automatic recovery runs in an owned, invisible WebView during homework sync:
it selects SMS and enters a fresh matching verification code without opening a
sign-in Activity. Enable SMS reception on the phone first. **Automatic sign-in**
in Homework's menu lets you save the university email and, optionally, a password
using Android's autofill/Password Manager UI. The account and optional password
are encrypted with Android Keystore under `noBackupFilesDir`; they are not
included in homework exports or logs. An existing Microsoft session can recover
with a remembered email alone. A password page without a matching saved password,
an absent/ambiguous account tile, a different account, a visible error or CAPTCHA
requires **Open sign-in screen**. Background recovery never opens a consent dialog.
The foreground sign-in window also selects the matching tile and SMS automatically.
Other senders and ambiguous messages are ignored; codes are not logged, and no
automatic resend is made. CAPTCHA still requires interactive completion.
If password/CAPTCHA entry takes a long time, the consent listener is renewed before
the first SMS choice so its remaining time covers the challenge. A manual code-entry
screen also renews an old listener. Listener renewal does not send a code or reset
the MFA deadline. Startup failure gets one challenge retry and a five-second wait
limit; outdated startup callbacks cannot release a newer wait.
The timing follows [Google's five-minute SMS User Consent window](https://developers.google.com/identity/sms-retriever/user-consent/request).
The picker is first inspected without clicking. Native request flags are recorded
before clicking a discovered method, so a page navigation cannot turn a lost
JavaScript callback into a repeated automatic SMS request. A changed/detached
picker leaves manual method selection available.
Seeing an existing code-entry screen also locks further automatic method requests.
Challenge timing is kept in memory: repeated probes cannot extend the three-minute
window, early codes can wait for the input, and a code is consumed at most once.
The sign-in Activity preserves its WebView and challenge during orientation/size
changes instead of starting another login. A stalled SMS consent startup stops
blocking method selection after five seconds; manual entry remains available.
Ordinary builds start Android's SMS User Consent listener before selecting SMS.
When Android offers the verification message, accepting the single-message consent
fills and submits the code. Denied consent, unavailable Play services, and messages
that do not match leave manual code entry available in the visible browser.
This session lives on the phone; no desktop Python
process or server is required. Homework can also be pinned using Customize
navigation.

The first successful update loads existing homework without alerting for every
old assignment. Subsequent updates refresh the cached list and notify for newly
seen Moodle activity IDs. Assignments, quizzes, and workshops are included;
resource links alone do not count as homework.
Each successful update also saves the complete enrolled-course catalog. Newly
enrolled courses appear automatically in the course filters, including courses
with no homework yet. Existing installations derive their initial filters from
saved homework until the first complete refresh. Failed or partial updates retain
the previous catalog; changing accounts replaces it together with the homework.
Tapping an item opens a native
Scholix detail screen with instructions, dates, and submission/grading tables.
Details are refreshed through the phone's browser session and cached for offline
reading. The app does not submit assignments or start quiz attempts.
Instructions retain paragraph breaks and list items instead of collapsing into a
single block. The screen observes saved-data changes and decodes snapshots off the
UI thread, without periodic polling. WorkManager remains responsible for scheduled checks.

WorkManager checks about every 30 minutes when network access is available.
Android battery restrictions can delay a check. The **Refresh** button queues
an immediate check. Cached homework and last-successful-update time remain
visible offline or after failed updates.
The refresh icon shows progress during a read. A manual refresh replaces a delayed
retry so the user does not have to wait for the background retry interval.

The homework overflow menu pauses/resumes automatic updates.
Manual **Refresh** still works for a signed-in account while automatic updates are
paused; it updates the list without delivering queued homework notifications.
The page shows **Automatic updates paused** beside the sync summary.
Search and separate course/type filters narrow the list. Activities are grouped
by course and type, then exercise number. Search and filters use separate measured
rows, including at enlarged font sizes. Both list and detail screens reserve space
for Scholix's floating navigation bar. The sign-in row disappears after a successful
sync and returns when the session expires. Dates learned from a detail page remain
in the cached list across subsequent JSON updates.
Search matches all entered words across the course and activity title, independently
of their order, and combines the homework field with Scholix's app search. The field
has a clear button. Course filters are reset when the selected course leaves the
complete enrollment catalog. The selected detail survives screen recreation;
failed detail reads keep cached content visible and offer **Retry loading homework**.
If a detail read requires interactive verification, it offers **Sign in to Lemida**
directly. Successful sign-in returns to the newly loaded list, so a changed account
cannot leave the old selected detail on screen; cancelling leaves that detail open.
Before requiring interactive sign-in, a homepage read follows the observed
university login page and Microsoft provider entry once each, allowing an existing
Microsoft session to redirect back to Lemida automatically. A session rejected
partway through AJAX discovery restarts the entire snapshot once with a fresh
session key. Expired homework-detail reads also get one dashboard reconnect and
retry; they verify the saved account before caching any content. Detail recovery
does not advance the full homework sync timestamp.
Automatic recovery requires an enabled remembered account and direct SMS
permission. It is bounded to four minutes overall, with the existing fixed
three-minute MFA challenge deadline. Email/password/tile actions are attempted
once per flow; navigation, Retry and lost acknowledgements cannot repeat them.
A persisted SMS reservation limits foreground and background flows together to
one automatic request per 15 minutes, including after process restart. No automatic
resend or CAPTCHA bypass occurs. Manual-only builds retain passive SSO and visible
SMS consent. If recovery cannot complete, the app retains the cache and offers
interactive sign-in. Automatic sign-in can be paused separately from updates.
The visible sign-in window also follows the university entries
once each. Invalid/external or ambiguous provider links are ignored, and stale
callbacks cannot navigate another document. An explicit **Retry** invalidates
pending checks and starts a fresh bounded portal/provider attempt. Existing MFA
request/submission guards and the challenge deadline remain in place, so retrying
a page cannot automatically request another SMS. Secondary login-entry callbacks
also check their probe generation, including when a retry returns to the same URL.
`LemidaRealExpiryTest` requires the explicit `allow_real_lemida_expiry=true` runner
argument. It calls the actual Moodle logout endpoint without following a separate
Microsoft logout redirect, verifies that authenticated AJAX is rejected, and then
checks automatic detail loading and a complete homework sync using the retained
Microsoft session. This real test passed on the connected phone on 2026-10-03,
recovering the same account and all 19 homework items without user input or SMS.
It invalidates the real Moodle session and must run only with the account holder's
authorization. It does not demonstrate unattended recovery after Microsoft itself
requires a new verification or CAPTCHA. The separate
`resumeAutomaticUpdatesForConnectedSession` check requires
`resume_real_lemida_sync=true`; it validates the current session, enables automatic
updates and verifies a pending periodic worker. This also passed on the phone;
notifications were allowed and automatic updates were enabled at completion.
`LemidaExpiryTest` exercises logged-out pages, rejected session keys, Microsoft
redirects, successful recovery and ordinary network errors using synthetic browser
responses and separate preferences. Its seven device tests also check full-snapshot
restart after mid-sync expiry, detail retry and rejection of another account's detail.
Its native screen check verifies that sign-in returns while cached homework remains
visible. An additional detail-screen check verifies that cached instructions remain
available and interactive sign-in is offered only as an explicit action. This new
check compiled but has not run on a device; USB disconnected before this follow-up. These tests do not expire the real
account, read cookies, contact Microsoft/Moodle or send a verification SMS.

Implementation: phone WebView CookieManager cookies are retained in a device-only
Android Keystore encrypted file, so background sync survives process restarts.
All Moodle page reads and AJAX run through the Android WebView using this
browser session. Lemida's Perfdrive anti-bot verification rejects native HTTP
replays even with the same cookies, so OkHttp is not used for Lemida reads.
Main-page network and HTTP errors fail promptly without treating broken images
as sync failures. AJAX requests have a deadline covering startup and polling,
are aborted on timeout/cancellation, and detect navigation during an update.
Page cancellation stops loading on the UI thread before the browser is reused,
and document snapshots check the completed URL as well as the origin. Late page
callbacks whose URL no longer matches the document are ignored; cleanup is idempotent and avoids
touching a sign-in WebView after its Activity has been destroyed.
Cookie file encryption and reads run on an IO dispatcher.
The sign-in window loads homework through its own browser before closing; a
failure remains visible instead of returning silently to an empty page.
Encrypted-session saves run inside the same recovery flow on an IO dispatcher,
so a storage/Keystore error offers Retry rather than escaping the browser callback.
Navigation releases unfinished sign-in and MFA checks; a late callback cannot
confirm another document or block its check. The MFA timer schedules its next
check independently of JavaScript completion. Only one browser sequence is pending
at a time across picker inspection, code preparation and submission acknowledgement.
Slow readiness callbacks remain valid across timer ticks. After five seconds a lost
callback is abandoned, allowing the next sequence.
An old timeout cannot abandon a newer query. Navigation and Retry invalidate old
picker/readiness callbacks, including same-URL navigation. Failed pages pause MFA
selection until Retry or a fresh navigation, while cached codes and one-submit
state retain their original deadline. Confirmation requires the current URL,
HTTPS origin, Moodle user ID and logout marker. Main-frame sign-in network/HTTP
failures show Retry; failed subresources do not interrupt a usable login page.
Course discovery uses Moodle's session-authenticated
`core_course_get_enrolled_courses_by_timeline_classification` AJAX method with
`allincludinghidden` and offset pagination. Activity discovery uses the working
`core_courseformat_get_state` JSON AJAX method. It includes visible assignments,
quizzes and workshops with stable course/module IDs; unrelated resources are ignored.
This is an authenticated Moodle API, not an authentication bypass. Read-only probes
on this deployment returned `servicenotavailable` for `core_course_get_contents`,
`mod_assign_get_assignments`, and `mod_quiz_get_quizzes_by_courses`.
Homework instructions and submission/grading tables still come from HTML pages:
the discovered API supplies activity metadata, not all detail content.
Activity URLs are parsed by endpoint and complete ID parameter, allowing reordered
query parameters while rejecting ambiguous IDs and other origins. Moodle error
pages fail detail loading instead of overwriting previously cached instructions;
ordinary homework warnings (such as overdue submission) remain readable.
Native details show activity notices beside instructions and grading tables,
including overdue/availability/attempt notices that were previously only in the
fallback text. Hidden notices are excluded and repeated messages are deduplicated;
cached details from earlier versions remain readable.
Nested grading tables contribute each row only to its own table; empty layout
wrappers are omitted, while blank grade values are retained. Feedback paragraphs
and line breaks survive extraction, and native grading sections show table captions.
The Python exporter uses the same row-ownership and feedback-formatting rules.
Updates commit only after discovery and every course succeed.
Course pagination accepts completion only when an empty response keeps the
current offset. Stalled nonempty pages, backwards offsets, and malformed paging
data fail the update and preserve the previous snapshot/export. This follows
[Moodle's offset-plus-processed-count contract](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/course/externallib.php#L3818-L3821)
in Android and Python; the bounded page limit remains in place.
Seen IDs are kept as a union to avoid duplicate alerts after temporary hiding. A different
Moodle `userId` establishes a fresh notification baseline.
Both discovery calls share response validation. Known session errors and HTML
login redirects restore sign-in; unavailable methods and malformed responses
preserve homework without falsely marking the session expired. Reconnect is also
available in Homework's overflow menu, while the connected screen hides the
sign-in row. Returning from sign-in refreshes the list immediately.
Pending alerts use current titles and dates from the latest complete snapshot;
withdrawn homework is removed from the notification queue. Delivery and acknowledgement
share the sync/account lock, so overlapping workers cannot deliver the same queue twice
and an old worker cannot acknowledge another account's homework. Paused updates and
sessions awaiting sign-in retain their queued alerts. Sign-in reminder delivery rechecks
the current session state, suppressing stale reminders after successful recovery.

Unit tests cover Hebrew names, dates, activity type and origin filtering,
baseline behavior, renames, new IDs, per-course identity, expired login pages,
and observed Moodle config casing. A device test has verified the saved phone
session can load both enrolled courses and all 19 homework items after process
restart. CAPTCHA and verification methods other than the supported SMS flow still
require the account holder's interactive session when the university asks for them.
Microsoft picker inspection ignores hidden/disabled controls. An existing disabled
OTP field still prevents another SMS request. Received codes stay buffered while
the form is disabled or Verify awaits input validation, within the original fixed
challenge deadline; readiness checks fill the field without clicking Verify.
The native one-submit guard is recorded only once the form is ready.

`LemidaHomeworkLayoutTest` is an offline device regression test for separate search,
course-filter and type-filter bounds at normal and 2× font scale on a narrow screen.
`LemidaPhoneSessionTest` requires an existing signed-in phone session and verifies
sync, native cached detail reads, and sign-in closing only after a successful load.
`LemidaAlertsTest` uses isolated preferences and fake notification callbacks to check
concurrent delivery, account switching, paused/failed delivery, and stale sign-in
reminders. These tests send no notifications and read no saved account session.
Homework notification taps reuse MainActivity when present and navigate to a fresh
homework list, clearing prior local filters and detail selection. Other navigation
stacks remain available. A warm `open_homework` intent was verified on the phone;
an actual notification tap and recreation still need device verification.
`LemidaBrowserLifecycleTest` uses controlled, non-network WebView loads to check
page cancellation before reuse, idempotent client cleanup, passive university/SSO
navigation, interactive Microsoft detection and stale snapshot rejection. Its original six
tests passed on the connected phone. It does not prepare cookies or read the saved
session. Together with seven expiry, two layout and four alert tests, all 19 offline
device regressions passed on 2026-10-03.
`tools/lemida/verify_browser_requests.py` exercises the Android request scripts
in Chromium with intercepted traffic, including late completion after cancellation,
network failure, page navigation and HTTPS origin checks; no live account is used.

The focused JVM checks compile the app's actual parser, session exceptions, SMS
scripts, MFA/consent state and login probes, and run their existing unit tests:

```powershell
.\gradlew.bat --project-dir tools/lemida/core-checks test
```

All 75 focused tests passed after rebasing onto main's 2026-10-03 updates. This
standalone project reads the root version catalog and requires neither Android
nor Motion; it does not validate UI, WebView, Keystore or background scheduling.
Session exceptions were moved into an Android-independent source file without
changing their names or behavior. Browser request and SMS script checks also passed
with intercepted traffic. On 2026-10-03 the full app build was blocked by the
unavailable `Motion beta52-local` artifact; upstream styling/navigation and
dependency versions were preserved. The 2026-10-04 validation below supersedes
that dependency blocker.

The three additional probe-timeout tests passed in the focused JVM check. Native
MFA timer wiring also passed the focused Android compilation described below;
the full app build and phone verification remain pending.

`LemidaMfaPoll` contains the actual browser-callback sequence independently of
Android. Eight controlled-callback regressions cover slow preparation/submission,
lost picker/SMS callbacks, same-URL navigation, listener readiness, expired or
changed codes and disabled Verify. They use the production scripts/state and send
no messages or network requests. Activity timer/receiver binding now compiles in
the focused Android project. Full app compilation and actual phone behavior still
need validation once Motion and a device are available.

Native homework instructions and Python detail exports preserve numbered, lettered
and Roman lists, including explicit starting values, item restarts and descending
lists. Nested lists start on separate lines and keep their own counters; unordered
items remain bullets. The same formatting applies to teacher feedback cells and
fallback detail text. Python also recognizes `#intro` and combines separate
instruction sections without duplicating nested wrappers. Six additional actual
parser regressions passed in the focused JVM checks; the equivalent Python suite
passed 24 tests, with two private-HTML checks intentionally skipped. These checks
use synthetic pages and do not access a live account or validate phone rendering.

Course-state discovery uses `uservisible` to decide whether the current account can
access an activity. `accessvisible` describes general availability to everyone;
requiring it incorrectly omitted homework restricted to a group even when the
signed-in student could open it. The distinction follows Moodle's
[course-state export](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/course/format/classes/output/local/state/cm.php#L67-L96)
and [account-specific visibility checks](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/lib/modinfolib.php#L2445-L2499).
Three focused regressions cover accessible restricted assignments/quizzes/workshops,
unavailable items, and one alert when restricted homework becomes available. The
API authorization, accepted activity types and same-university URL checks still
apply. The new visibility cases have not been verified with a live phone session.

The focused Android check compiles 19 actual Lemida production source files,
excluding the Motion homework screen, including the current login Activity's native
timer/receiver binding, WebView transport, encrypted cookie store, repository and
worker. It uses the upstream AGP/Kotlin versions and reads SDK levels, Java targets
and the SMS dependency from the app build file:

```powershell
# Set ANDROID_HOME to your installed Android SDK, or use ignored local.properties.
.\gradlew.bat --project-dir tools/lemida/android-checks testDebugUnitTest assembleDebug
```

This check passed with AGP 9.4.1, Gradle 9.8.0 and SDK 37; all 75 existing focused
unit tests and eight controlled browser host cases passed (83 total at that time). The selected source files and
notification icon were verified byte-identical to production. It builds a library
AAR, not a Scholix APK. MainActivity is a compilation fixture used only to resolve
the worker's notification destination; the real MainActivity, navigation and Motion
screen are excluded. This validates native compilation without downgrading or
substituting Motion. It does not execute SMS delivery, Play services, Keystore,
WebView, worker scheduling or notification routing on a phone. See the
[checker boundaries](../tools/lemida/android-checks/README.md) before interpreting
the results. The full app/device validation below is not inferred from this AAR.

The browser transport now invalidates document-read and Microsoft-control callbacks
on each main-frame page start, even when a reload keeps the same URL and client.
Without that guard, delayed anonymous HTML could interrupt the new page with a
sign-in redirect, or an old Microsoft control check could prematurely fail passive
recovery. Two additional `LemidaBrowserLifecycleTest` cases reproduced those failures
before the fix and passed afterward, while current-page recovery still works.
All eight methods now run under Robolectric SDK 37 in the focused Android project,
using the actual device suite and production browser with controlled callbacks.
The host-only Main dispatcher fixture and WebView subclass do not render pages,
execute JavaScript, prepare cookies or read a saved session. This adds local
callback/lifecycle coverage; all eight also passed on the phone on 2026-10-04. They do not
validate real Microsoft SSO, SMS delivery or full app behavior.

Python live sync now publishes its JSON snapshot only after all selected course,
grade and homework reads succeed. Previously a midway failure or expired session
could replace the last complete export with partial data. Such attempts now write
a separate `.failed.json` diagnostic file and preserve the successful export;
read errors still return exit code 2 and session expiry returns exit code 1.
JSON publication uses a temporary file in the destination directory and atomic
replacement, preserving the previous file if publication fails. Five synthetic
regressions reproduced premature publication/data replacement before the fix and
passed afterward, including course and grade/homework errors, session expiry and
failed file replacement. All 29 Python tests passed; two private HTML cases were
skipped. No live browser session, SMS or phone operation was used for these checks.

The desktop Python login also inspects the existing Microsoft OTP field before
choosing a verification method. Observing that field locks subsequent alternative
and SMS requests even if it disappears during navigation. The alternative-method
attempt is now recorded before clicking, as the SMS attempt already was, preventing
a lost navigation callback from repeating the choice. Three mocked-client cases
reproduced the prior behavior and now pass; a fourth confirms that an interrupted
SMS click still requests only once. All 33 Python tests passed with two private
HTML skips. These tests run the actual Python login loop with mocked controls;
they neither send SMS nor verify a real Microsoft session or phone behavior.

## 2026-10-04 automatic sign-in validation

Upstream Motion `release/beta13` at `bfbc2cf` now supplies `beta52-local`.
It was built and published to Maven Local in a separate checkout, disabling only
publication signing there. The original Motion checkout's local properties/font
edits were preserved. The complete app, including the real Motion screen,
MainActivity/navigation and pulled Google Drive feature, builds with the upstream
dependency versions. The development APK was installed without clearing data.
All 151 app unit tests passed; the Android focused check passed 83 production
unit cases plus eight controlled Robolectric browser cases (91 total), and the
Android-independent check passed 83 cases. These are overlapping suites,
not independent counts to add together.

`LemidaCredentialsDeviceTest` exercises the real phone's Keystore and WebView
JavaScript engine using synthetic Microsoft-origin documents with network access
disabled. Its 16 cases cover encrypted restart, account-tile priority, account
mismatch, detached/changed controls, errors/CAPTCHA, quoted passwords, input
validation and persistent SMS reservation. The hidden controller case pauses
while a page is not ready, then selects the account, requests SMS once, accepts a
synthetic decoded message, fills the OTP and submits without an Activity. It does
not send a real SMS, prove cellular delivery or force real Microsoft MFA expiry.
A foreground sign-in recovered the live phone's two courses and 19 homework
items on this date; no password was extracted from desktop Chrome. The phone's
preferred university account is saved, but no university password is saved.
Full real-server background MFA and notification-tap checks remain pending.
The latest offline device batch passed 34 of 38 cases: all 16 credential/hidden
controller cases, eight browser lifecycle cases, six non-UI expiry cases and four
alert cases. The two layout and two Compose expiry/detail cases failed to find a
Compose hierarchy while the phone was locked/dozing; they remain pending on an
unlocked phone. The SMS-reservation fixture now clears only its own test
preferences before/after execution so repeated runs start from an isolated state.
