# Schedule widget

Add **Scholix → Schedule** from the launcher's widget picker, then choose a saved schedule provider. The daily view uses the app's subject colors, icons, lesson details, and free-period rules. After the last active lesson ends, it shows tomorrow's schedule. Cancelled lessons and subjects marked as free periods do not delay that switch.

The widget shows only lessons and a small refresh arrow in the bottom corner. Hebrew lesson text is right-aligned; English lesson text is left-aligned. Tap the schedule to open the schedule page in Scholix. The lesson list scrolls and the widget can be resized down to one row. To choose another provider, use the launcher's widget reconfiguration action.

The widget starts one lesson before the current lesson where possible, or before the next lesson during a break. Tomorrow's view starts at the beginning of the day. The full day's lessons remain in the list, so you can scroll back to the morning or forward to later lessons. Hours and times use a consistent appearance without a circular highlight. The scroll indicator is hidden.

Setup fetches the first schedule before completing. Background refresh runs every 15 minutes, with additional updates when the schedule screen opens, at the end of the school day, at midnight, and after clock or timezone changes. Android can defer background work to save battery; this is not a real-time push feed. Failed refreshes retry automatically and keep cached lessons visible. The refresh button also retries without opening the main app. Renewed Webtop sessions are saved for subsequent app and widget requests. Providers that require interactive sign-in still need that sign-in if their saved session can no longer be renewed.
