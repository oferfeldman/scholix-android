# Courses and provider views

Connected OpenU, Lemida and Classroom users start on Home. School-only users keep
Grades as their start page. Existing notification intents still take priority.
Home uses saved course/provider data; opening it does not request Inbar SMS or
refresh university sessions. Course-card chips open Assignments or Units directly.
School-provider rows open Grades focused on that provider.

The existing Motion bar and tablet rail switch to the selected course's sections.
The scope chip in the existing header opens global views, provider grades and recent
courses. Course routes retain provider, course and term identity; Lemida routes
also retain the saved account. Recent courses resume their open route, while a
Home tab selection returns to the course list. Course-section saveable state is
kept above transient destination composition.

OpenU sections reuse the provider's existing on-device session. Learning units,
activities, forum content, digital books, available booklets and recording lists
have native navigation; rich activity content preserves tables, images and MathML.
PDFs use the existing reader and recordings use native playback. Book consent is
explicit. Quizzes open OpenU's original controls in the built-in browser; opening
the native overview does not start, save or submit an attempt.

The original OpenU exam/assignment archive requires a separately configured
server-side Google Drive collection. That collection is not connected here, and
Archive states this limitation. Live provider reads, playback, consent and quiz
flows still require signed-in device verification. No device, account session or
installation was changed for this source integration.

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
The final debug APK build, Android test-source compilation and 32 focused JVM
checks passed. Device UI and live provider verification were not run.
