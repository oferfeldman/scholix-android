# Schedule widget

Add **Scholix → Schedule** from the launcher's widget picker, then choose a saved schedule provider. The daily view uses the app's subject colors, icons, lesson details, and free-period rules. After the last active lesson ends, it shows tomorrow's schedule. Cancelled lessons and subjects marked as free periods do not delay that switch.

The widget shows only lessons and a small refresh arrow in the bottom corner. Tap the schedule to open the schedule page in Scholix. The lesson list scrolls and the widget can be resized down to one row. To choose another provider, use the launcher's widget reconfiguration action.

Setup fetches the first schedule before completing. Background refresh runs every 15 minutes, with additional updates when the schedule screen opens, at the end of the school day, at midnight, and after clock or timezone changes. Android can defer background work to save battery; this is not a real-time push feed. Failed refreshes retry automatically and keep cached lessons visible. The refresh button also retries without opening the main app. Providers that require interactive sign-in still need that sign-in if their saved session can no longer be renewed.
