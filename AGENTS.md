# Scholix workflow

Always commit and push completed changes before handing work back to the user, unless the user explicitly instructs otherwise. Include the relevant source, tests, and documentation; keep temporary artifacts and credentials local.

## Product UX

Keep connected school providers such as Webtop and Mashov, university providers such as Open University, and other platforms accessible through the original main pages.

The user removed the course-first Home on 13 November 2026. Open Grades when supported by the selected provider, otherwise its first supported page (or Tiktek). Omit Home from navigation and return from a course to its provider's main page. Keep the course switcher and each page's distinct icon. Preview proposed Android designs with the app's actual Compose/Motion components, fonts, colors, shapes, and provider data.

Course pages use a single header with Grades' course picker scoped to the active provider account and distinct section icons; avoid dashboard sections and resume cards.

The normal provider picker scopes all main feature pages and their navigation to supported capabilities. Hide Studies from navigation while retaining assignment study handoff. Assignments has no redundant title bar; retain its course picker and refresh/sign-in controls.

Locker is the single top-level locker page. Keep Contents and the Lockerim integration inside it. Saved numeric and dial combinations are device-local, encrypted, and excluded from backup; preserve leading zeros and each dial step's direction and turn count.

Use an emulator for Android UX previews. Do not remotely access the user's physical Pixel 10 Pro unless the user explicitly requests it again.
