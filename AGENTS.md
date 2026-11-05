# Scholix workflow

Always commit and push completed changes before handing work back to the user, unless the user explicitly instructs otherwise. Include the relevant source, tests, and documentation; keep temporary artifacts and credentials local.

## Product UX

Home must combine connected school providers such as Webtop and Mashov, university providers such as Open University, and other connected platforms. Keep each provider's distinct capabilities accessible; Home must not become an Open University-only course list.

Home shows the same navigation bar as the other top-level pages (the user asked for it on 5 November 2026, after Home had none). Outside a course nothing is pinned to the bar's end, so its slots go to pages in the user's order; each page has its own icon. Course pages need an easy course switcher at the top and a Home button. Preview proposed Android designs with the app's actual Compose/Motion components, fonts, colors, shapes, and provider data.

Keep Home to a simple course list with the standard provider picker. Course pages use a single header with Grades' course picker scoped to the active provider account, distinct section icons, and Home pinned in the course's own navigation bar; avoid dashboard sections and resume cards.

Use an emulator for Android UX previews. Do not remotely access the user's physical Pixel 10 Pro unless the user explicitly requests it again.
