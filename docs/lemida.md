# Lemida homework on the phone

Open **More → Homework** in Scholix, allow notifications, and tap **Sign in to
Lemida**. Complete Microsoft's password, Authenticator/SMS, and any CAPTCHA in
the visible sign-in window. The sign-in window selects SMS automatically when
Microsoft offers it. In the direct-SMS development build, allow SMS reception
and a fresh Microsoft verification code is entered and submitted automatically.
Other senders and ambiguous messages are ignored; codes are not logged, and no
automatic resend is made. CAPTCHA still requires interactive completion.
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

WorkManager checks about every 30 minutes when network access is available.
Android battery restrictions can delay a check. The **Refresh** button queues
an immediate check. Cached homework and last-successful-update time remain
visible offline or after failed updates.
The refresh icon shows progress during a read. A manual refresh replaces a delayed
retry so the user does not have to wait for the background retry interval.

The homework overflow menu pauses/resumes automatic updates.
Search and separate course/type filters narrow the list. Activities are grouped
by course and type, then exercise number. Search and filters use separate measured
rows, including at enlarged font sizes. Both list and detail screens reserve space
for Scholix's floating navigation bar. The sign-in row disappears after a successful
sync and returns when the session expires. Dates learned from a detail page remain
in the cached list across subsequent JSON updates.
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
