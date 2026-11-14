# Scholix workflow

Always commit and push completed changes before handing work back to the user, unless the user explicitly instructs otherwise. Include the relevant source, tests, and documentation; keep temporary artifacts and credentials local.

## Product UX

Keep connected school providers such as Webtop and Mashov, university providers such as Open University, and other platforms accessible through the original main pages.

The user removed the course-first Home on 13 November 2026. Open Grades when supported by the selected provider, otherwise its first supported page (or Tiktek). Omit Home from navigation and return from a course to its provider's main page. Keep the course switcher and each page's distinct icon. Preview proposed Android designs with the app's actual Compose/Motion components, fonts, colors, shapes, and provider data.

Use one Grades-style picker for providers and courses. Only accounts with two or more courses get a provider folder; single courses appear directly. Do not add an All courses/main courses option. Course pages keep a single header and distinct section icons; avoid dashboard sections and resume cards.

Order OpenU courses as current, upcoming, then completed. Show available final and exam grades in the picker, or the available assessment average when no final grade exists. Derive course navigation from the selected route before content finishes loading.
Switching provider accounts opens the selected course's Overview when supported; switching courses within one provider keeps the current supported section.
OpenU uses one Grades page for grades and all assignments, including ungraded work; assignment rows open their details. Do not show a separate OpenU Assignments navigation item.

Assignment details use the compact rounded Open fstudy and Upload files buttons with a 2 dp gap. Read the real assignment/submission details and display populated sections inline; omit empty sections.

The normal provider picker scopes all main feature pages and their navigation to supported capabilities. Hide Studies from navigation while retaining assignment study handoff. Assignments has no redundant title bar; retain its course picker and refresh/sign-in controls.

Materials is currently available only for Lemida and OpenU. Settings is always the last visible navigation item, including when another page comes from overflow; Settings itself has a Back button. Attendance portions settings belong to a provider account and subject, and open from that subject's attendance card rather than general app settings.

Assignment study handoff transfers real fstudy questions and sources. Documents offers a folder choice on creation and owns the study's stable ID, name, location, answers and progress. Reopening an assignment must retain that ID after renaming or moving the study, refresh Scholix's displayed reference, and never overwrite existing work.

Locker is the single top-level locker page. Keep Contents and the Lockerim integration inside it. Saved numeric and dial combinations are device-local, encrypted, and excluded from backup; preserve leading zeros and each dial step's direction and turn count.

Use an emulator for Android UX previews. Do not remotely access the user's physical Pixel 10 Pro unless the user explicitly requests it again.
