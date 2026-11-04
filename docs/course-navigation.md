# Courses and provider views

Connected OpenU, Lemida and Classroom users start on Home. School-only users keep
Grades as their start page. Existing notification intents still take priority.
Home uses saved course data; opening it does not request Inbar SMS or refresh
university sessions. Provider-grouped course rows use the same icons, type and
connected surfaces as assignment lists. Rows open Overview; Resume restores the
last course location. Home no longer duplicates general-page or provider-grade
shortcuts, aggregate grade summaries, or per-card action chips.

The existing Motion bar and tablet rail switch to the selected course's sections.
Normal page titles and existing general-page pickers are retained. A compact
All platforms icon in Home, Grades and course/item headers opens direct provider
grades first, including empty-cache accounts, then global views and recent courses.
It replaces the old title chip and extra header rows. Provider rows include the
account label. Home/course-to-provider navigation takes two taps before subject
selection, loading or sign-in; consent and SMS remain separate interactions.
Course routes retain provider, course and term identity; Lemida routes
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
The initial integration's debug APK build, Android test-source compilation and
32 focused JVM checks passed. The 4 November UX correction also passes the five
focused CourseScopeTest checks and Kotlin compilation. Local visual QA was blocked:
device_open failed, then failed again after a device-list refresh because the
codex_documents_qa emulator exited with code 1. No rendered or live provider
verification is claimed for this correction.
