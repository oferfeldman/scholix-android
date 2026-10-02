# Lemida homework on the phone

Open **More → Homework** in Scholix, allow notifications, and tap **Sign in to
Lemida**. Complete Microsoft's password, Authenticator/SMS, and any CAPTCHA in
the visible sign-in window. The sign-in window selects SMS automatically when
Microsoft offers it. In the direct-SMS development build, allow SMS reception
and a fresh Microsoft verification code is entered and submitted automatically.
Other senders and ambiguous messages are ignored; codes are not logged, and no
automatic resend is made. CAPTCHA still requires interactive completion.
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
resource links alone do not count as homework. Tapping an item opens a native
Scholix detail screen with instructions, dates, and submission/grading tables.
Details are refreshed through the phone's browser session and cached for offline
reading. The app does not submit assignments or start quiz attempts.
Instructions retain paragraph breaks and list items instead of collapsing into a
single block. Screen cache polling pauses when the app is in the background;
WorkManager remains responsible for scheduled checks.

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
has a clear button. Course filters are reset when the selected course no longer has
homework in the complete snapshot. The selected detail survives screen recreation;
failed detail reads keep cached content visible and offer **Retry loading homework**.
If Microsoft/Moodle expires the session, the app retains the cache and sends
one sign-in reminder, then waits for interactive sign-in. No SMS resend,
password replay, or CAPTCHA bypass occurs in the background.

Implementation: phone WebView CookieManager cookies are retained in a device-only
Android Keystore encrypted file, so background sync survives process restarts.
All Moodle page reads and AJAX run through the Android WebView using this
browser session. Lemida's Perfdrive anti-bot verification rejects native HTTP
replays even with the same cookies, so OkHttp is not used for Lemida reads.
The sign-in window loads homework through its own browser before closing; a
failure remains visible instead of returning silently to an empty page.
Encrypted-session saves run inside the same recovery flow on an IO dispatcher,
so a storage/Keystore error offers Retry rather than escaping the browser callback.
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
Updates commit only after discovery and every course succeed. Seen IDs are
kept as a union to avoid duplicate alerts after temporary hiding. A different
Moodle `userId` establishes a fresh notification baseline.
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
restart. New CAPTCHA or Microsoft verification still requires the account
holder's interactive session when the university asks for it.

`LemidaHomeworkLayoutTest` is an offline device regression test for separate search,
course-filter and type-filter bounds at normal and 2× font scale on a narrow screen.
`LemidaPhoneSessionTest` requires an existing signed-in phone session and verifies
sync, native cached detail reads, and sign-in closing only after a successful load.
`LemidaAlertsTest` uses isolated preferences and fake notification callbacks to check
concurrent delivery, account switching, paused/failed delivery, and stale sign-in
reminders. These tests send no notifications and read no saved account session.
