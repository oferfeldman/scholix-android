# OpenU assignments

Select an OpenU account in **Homework**. Current courses load automatically; the course picker includes previous and upcoming semesters. Search and status filters operate on the selected courses. Course number, exact semester, activity module and Moodle activity ID identify each item. Failed or partial refreshes retain the last successful per-course snapshot and its original check time.

The implementation follows `Feldman/openu`'s `api/src/openu.ts` assignment index/detail contracts and its native Android assignment pages. It uses Scholix's existing direct OpenU provider session, not the OpenU app's Firebase/backend account. Opal course entry is `/ouil/course.php?course=c{courseId}&semester={term}`. Assignment, quiz and assessment rows include available dates, raw status, grades and weights. Viewing a quiz does not start an attempt. Due dates remain dates; an elapsed due date is not treated as a provider permission decision.

Details show instructions, submission/grading fields (including the receipt), original task files, submitted files and checked feedback. File discovery comes from OpenU, independent of which app uploaded them. PDFs use the existing native reader; other formats can be opened with a compatible installed app. Any attachment can be saved through the system document picker. Downloads use the selected provider's authenticated cookie jar, stream to private storage and are bounded at 300 MiB. Login/error HTML is not opened as a downloaded file. Documents study actions use the existing versioned handoff and include the assignment's last-known status/check time.

## An unsent draft open in the website

Opal allocates different draft IDs to different edit forms. Opening a new form does not discover another browser's unsent files. The **OpenU website draft** handoff reads the exact existing draft instead:

1. Tap **Connect a website draft** in an assignment, then copy the browser bookmark code. Save it as a bookmark's URL in the browser used for Opal. The source is [openu-draft-handoff.js](../app/src/main/assets/openu-draft-handoff.js). On Android Chrome, a synced bookmark can be invoked by typing its name in the address bar.
2. Open the assignment's upload/update form in the official Opal site and add the file there. Run the bookmark while that form remains open.
3. Scholix opens the draft screen. Select the same connected OpenU account and course. The app validates the course/activity/internal assignment binding, then lists the known draft through `repository/draftfiles_ajax.php?action=list`. Refresh reads changes made in the website. File preview/download works independently of local upload history.

The handoff contains only the Moodle course/activity/assignment IDs, draft item ID and form client ID. It never includes passwords, cookies or `sesskey`; Scholix obtains its own authenticated session token. Unrecognized link fields are rejected. A canceled draft can retain server files, so the screen labels these as unsubmitted draft files and never interprets their existence as an active edit or accepted hand-in. It does not submit, replace, delete, or cancel work. Native upload/submission and automatic enumeration of all browser drafts are outside this viewing change; **Open in OpenU** provides the user-controlled official workflow.

## Verification

`OpenUAssignmentsTest` covers list parsing, date/status labels, semester identity, failure versus empty lists, receipt preservation, files added by other clients, exact activity query parameters, fresh Opal grades, course mismatch, allowed attachment URLs and token-free draft references. All nine focused tests and debug APK assembly passed. The compiled Kotlin parser was also checked against previously recorded provider HTML (11 assignment rows, status/due date, receipt, accepted PDF, preexisting DOCX); the bookmark's selectors and token-free output were checked against the recorded active form. Private examples and credentials are not committed.

The Device panel failed to boot `Pixel_10_API37` twice with `Failed to allocate an emulator port`; visual portrait/landscape/theme checks and end-to-end cross-app bookmark invocation remain unverified. No new academic submission was performed for this change.
