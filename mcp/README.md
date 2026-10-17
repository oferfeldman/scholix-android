# Scholix in Feldman Home

The existing owner console at `https://feldman-mcp.duckdns.org/tools/` connects provider accounts. The existing combined MCP URL remains `https://feldman-mcp.duckdns.org/tools/combined/mcp`; Scholix also has `/tools/scholix/mcp`. Start with `scholix__providers` and `scholix__accounts`, then use the exact names published by `tools/list`.

OpenU is a special provider inside Scholix. Every existing `openu__*` action remains available and is also published as `scholix__openu_*`. The same encrypted OpenU session serves both names. Other provider tools require an explicit `connection_id`; multiple accounts can be connected for each provider.

## Reused app code and added adapters

`native/prepare.py` builds a headless slice directly from the Android app's existing provider sources: Webtop, Mashov, Bar-Ilan/Michlol, Inbar, Education Portal, Shahaf, Demo and the Classroom API/mapping. It removes Android's attachment-opening UI method and supplies small JVM compatibility shims. Provider parsing, authentication, Webtop mailbox operations and timetable/calendar requests remain the app's implementation. TLS verification remains enabled. Android-encrypted Inbar state requires a fresh SMS sign-in; state is encrypted by the hub instead of Android Keystore.

`Worker.kt` adds JSON IPC dispatch and attachment transfers. Python adapters add MCP schemas, encrypted account storage, owner connection forms, provider browser sessions, Google Web OAuth, and provider-specific dispatch. Lemida parsing comes directly from `tools/lemida/lemida.py`. The existing OpenU TypeScript app client remains unchanged in the Home hub.

`openu-worker.cjs` corrects the MCP assessment output to use Sheilta's `examinationDate` and actual document-availability flags. `openu_downloads.py` returns an unguessable private PDF link valid for 15 minutes, backed by encrypted temporary storage; `output=base64` remains available. This avoids browser PDF export and oversized base64 tool output. The download route requires possession of that private link and does not require a second login. Treat the link as private. The underlying TypeScript client is unchanged.

Common tools cover courses, grades, subjects, current/original timetables, attendance, messages and account information where the actual provider supports them. Unsupported actions return an explicit error. These are not claims that every provider implements every action.

Webtop additionally exposes the complete existing mailbox: Inbox/Sent/Drafts/Trash, search, body/attachments, recipients and directory, teachers, reply preparation, drafts, sending, folders, read state, deletion/recovery/moving, image upload, signatures and notification settings/children/list/read/delete. Its student weekly calendar schedule (`GetPupilScheduale`) and Events & Tests board are separate tools, with date/week/view/year filters.

Classroom exposes courses, coursework, submissions, announcements, materials, teachers, students, grades and homework. Drive exposes the operations present in the app: list/search, file metadata, download/export and create. Both require a Google Web OAuth client configured in the owner console, followed by account consent. Education Portal and Lemida have an interactive sign-in browser; Webtop and Shahaf also support it. Passwords and OTPs are entered only in the owner console. Importing an existing session is owner-only and validates it against the provider.

## OpenU portal additions

`openu_portal.py` adds contracts inspected from Sheilta's shipped JavaScript on 2026-10-17:

- Full request history and detail links, request-category/search catalog, general type/subtype tree, form explanations, dynamic parameters, course choices, permissions and attachment requirements.
- General request creation, attachment upload/download/list/delete. Specialized and legacy forms retain the existing authenticated browser controls. A request is submitted only by an explicit write-tool call; reads never submit one. Submission can return a payment requirement, but the adapter does not follow checkout or pay.
- New exam/task system permissions, pending exams/tasks, submissions, state, questionnaire metadata, messages and file constraints; legacy exam-registration viewing.
- Offered registration semesters and course/group searches, including upcoming semesters; public course descriptions and academic timetable; personal timetable.
- Official personal study programs, obligations, recognition, program search/display XML and planner course/filter data.
- The exact bundled course catalog, degree tree and complete degree requirement JSON used by the owner's OpenU website. These are source snapshots, not a claim of live registration availability. The website's private Firebase course-plan document/local selection is separate; this integration does not read that document or change the user's saved plan.

Portal APIs were inspected read-only. No real service request, message, registration, exam attempt or assignment was submitted during development. The upstream `/main/api/assignmentsApi/tasks` returned HTTP 500 for the connected account; errors remain visible. Write operations are declared as non-read-only, require the existing `hub:control` scope, and are never automatically retried. Live writes remain untested.

## Build and export

From the Scholix repository root, with Java 17+ available:

```powershell
python mcp/native/prepare.py
./gradlew.bat -p mcp/native test installDist --console=plain
python mcp/export.py ../../reverse-engineer/hazi-hinam --openu-source ../../reverse-engineer/openu-api-source
```

The OpenU source snapshot used here is `oferfeldman/openu-api` commit `899f9bc17d3e1a557eeb957cd989d7de03288cd3`. Generated provider slices, tests and dependency JARs are ignored; they are regenerated from the app. The export includes no credentials or account state.

`home.patch` records the small changes to the existing Home host, Docker runtime and owner console. It is already deployed. For a fresh host checkout, review/apply that patch before exporting the adapters; do not reapply it to an already integrated host. The Python tests in `mcp/tests` run against the host's `gateway` package; the Node regressions use `OPENU_WORKER_PATH` pointing to its `gateway/openu-client/worker.cjs`.

Home hub integration also wires `ScholixService` and console routes into `gateway/app.py`, registers `openu_portal` in `gateway/openu.py`, installs BeautifulSoup and a Java 17 runtime, and includes the generated worker/catalog files in its existing Docker image. OAuth, scopes, HTTPS endpoint and existing Hazi Hinam/Sally connections remain managed by that hub.

The browser reuses a portal-read tab, supports legacy framesets, and caps renderer processes. The container process limit is 512 to accommodate Chromium and the JVM; its memory limit remains unchanged. The specialized Google list/file tools use a new Python REST adapter; Classroom grades/homework reuse the app's mapping code.

Live verification: connected OpenU course/grade/profile, assignment/date/notebook, calendar, course page, recording collections, forums, books/booklet, request history/details/catalog/forms, exam-system reads, registration offerings, public timetable/descriptions, personal calendar frames and bundled degree data. The actual combined-MCP notebook response contains a compact private link, and both aliases download the same verified PDF. Sheilta's tasks endpoint and a study-program XML request returned upstream HTTP 500; catalog/degree requirement tools remain available. Other real providers require their accounts to be connected before live verification. No real write workflow was exercised.
