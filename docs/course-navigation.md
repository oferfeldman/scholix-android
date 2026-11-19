# Courses and provider views

Scholix opens the selected provider's Grades when supported, or its first
supported page. Notification intents still take priority. The shared picker
switches between providers and courses, with a folder for accounts with two or
more courses. Saved courses, including Mashov subjects, are available in the
picker while accounts refresh. Back from a course returns to its provider's main page.

**Sign in on Webtop** opens the official login in Scholix's browser. The
action is available while Webtop grades load and from Webtop's error states.
Native sync still uses its own provider session. When refresh fails or takes
more than 20 seconds, Grades shows any saved result for the selected term.

The Motion bar and rail switch to the selected course's sections. When switching
providers, the navigation updates from the selected provider's capabilities while
data loads. Course routes retain provider, course and term identity. Recent
courses resume their last open route.

OpenU sections reuse the provider's existing on-device session. Learning units,
activities, forum content, digital books, available booklets and recording lists
have native navigation; rich activity content preserves tables, images and MathML.
PDFs use the existing reader and recordings use native playback. Book consent is
explicit. Quizzes open OpenU's original controls in the built-in browser; opening
the native overview does not start, save or submit an attempt.

OpenU Overview contains the course facts and learning units. Wider windows keep
the list beside the selected unit, recording or assignment. Grades includes
assessment marks and assignments, including work awaiting a grade.
Saved Units routes open Overview and saved Assignments routes open Grades.
Saved Archive routes also open Overview. The archive's separate Drive collection
is unavailable in Scholix.

Lemida keeps its original assignment detail, PDF reader and built-in hand-in page.
Files lists saved assignment PDF attachments and links to the official course for
other files. Classroom Materials is scoped to the selected course. Course Studies
filters existing assignment links by stable identity and records course scope for
new general links without changing existing study IDs or Documents receipts.

Save homework for PC previews and exports all courses in the complete committed
Lemida snapshot. It deliberately does not label a course subset as a complete
snapshot. The existing PC importer accepts the `scholix-homework` format; sessions,
credentials and handwriting are excluded. Studies retain their separate export.

Focused JVM checks cover course/provider/term identity, navigation ancestry and
global excursions, OpenU parsing/resource isolation, study-reference compatibility
and complete homework export with incomplete/account-mismatched cache rejection.
The initial integration's debug APK build, Android test-source compilation and
32 focused JVM checks passed. The 4 November UX correction also passes the five
focused CourseScopeTest checks and Kotlin compilation. Local visual QA was blocked:
device_open failed, then failed again after a device-list refresh because the
codex_documents_qa emulator exited with code 1. No rendered or live provider
verification is claimed for this correction.
