# StarNote in Materials

Materials → StarNote discovers `StarNote/sync/v1/<account>/document` in the
connected user's Drive. It lists note folders with their thumbnails and local
Scholix titles. Discovery is paginated and does not embed any user's Drive IDs.

The reader reconstructs pages from bounded sync ZIP archives, observed protobuf
page records and big-endian stroke points. It supports `geo_layout` paper and
`import_pdf` backgrounds; resource PDFs are downloaded into the private account
cache. Unsupported page/object types are reported. Covers, pen pressure, rich
text, images and undocumented deletion/page-merge semantics are not fully
supported. This is a compatibility preview, not a complete StarNote renderer.
Unsupported notes should be exported as PDF in StarNote and opened in Materials.

Scholix edits include new handwriting, hiding original ink, placed text and
page comments. Text and comments can be changed or deleted. Reading supports
pinch zoom; switch to editing to restore page coordinates. Notes can be named
in Scholix because the inspected sync tree did not expose original note titles.
These names and annotations do not sync back into StarNote. The reader now checks
explicit source title fields in Drive properties, JSON descriptions and document
records. Single-PDF notes fall back to their PDF filename. The inspected Drive
tree still does not expose original handwritten-note titles; a StarNote title
catalog/export is required to recover those, rather than guessing from UUIDs.

Materials has an Arrange filters button: each of Course folders, Shared with me,
My Drive, StarNote and Offline can be placed at the top, bottom, left or right,
with a persisted order and a Restore defaults action. Reader chrome hides the
Materials filters while a note is open. Fit page resets zoom and pan; Fit width
uses the current viewport. Details and backup actions live in compact menus.

Download folder recursively saves subfolders and supported files via WorkManager,
with progress/cancellation and resumable reuse of unchanged copies. Folder scans
deduplicate shortcut targets and reject excessive depth, 5,000 files or 2 GB.
Unavailable and files larger than 100 MB are skipped and reported. Each complete
folder listing is saved; StarNote discovers and opens downloaded folders using
local metadata and file copies without obtaining a Google token. Regular online
reads also retain versioned files and listings for faster reopening. Refresh from
Drive explicitly obtains fresh metadata; locally preferred views show this in
Note information. The worker must finish to register a fully downloaded folder.

Drafts are stored atomically in `noBackupFilesDir/drive-materials/starnote`,
partitioned by Google account. Local persistence does not require a token or
network. Saves attempt a Drive backup after a short debounce; failures retain
the local draft and show an explicit pending/error status. The reader prefers an
unsent local draft over a remote revision. Cloud saves append immutable JSON
revisions in an app-created `Scholix StarNote edits` folder. Prior revisions are
retained, including independent changes from other devices; the latest remote
revision is loaded on reopening if no local draft is pending. Concurrent edits
are not merged. There is no history-selection UI yet.

Cloud backup uses incremental `drive.file` authorization in addition to the
existing `drive.readonly` scope. Declare both in Google Cloud's Data Access page.
Only selecting Enable backups/Back up now opens additional authorization.
StarNote's sync ZIPs, resources, templates, thumbnails and sharing permissions
are never changed. This implementation does not request full Drive write access.
Reference: https://developers.google.com/workspace/drive/api/guides/api-specific-auth

Validation: full Android build and unit suite, malformed protobuf/traversal/ZIP
expansion rejection, edit serialization and source/coordinate validation, and
multipart POST creation of separate files. An optional private-fixture test
uses `SCHOLIX_STARNOTE_FIXTURES` pointing to an external directory containing
`handwriting.zip` and `planner.zip`; user notes must not enter version control.
The inspected fixtures reconstruct three handwritten pages and three PDF pages.
Live OAuth, rendering, pen input and cloud restoration on the phone remain
required device checks; no phone was attached for this implementation.
