# Scholix workflow

Always commit and push completed changes before handing work back to the user, unless the user explicitly instructs otherwise. Include the relevant source, tests, and documentation; keep temporary artifacts and credentials local.

## Product UX

Home must combine connected school providers such as Webtop and Mashov, university providers such as Open University, and other connected platforms. Keep each provider's distinct capabilities accessible; Home must not become an Open University-only course list.

Home should have no bottom navigation bar. Course pages need an easy course switcher at the top and a Home button. Preview proposed Android designs with the app's actual Compose/Motion components, fonts, colors, shapes, and provider data.

Keep Home to a simple course list with the standard provider picker. Reuse Grades' course picker on course pages and keep the course's own navigation bar; avoid dashboard sections and resume cards.

Use an emulator for Android UX previews. Do not remotely access the user's physical Pixel 10 Pro unless the user explicitly requests it again.
