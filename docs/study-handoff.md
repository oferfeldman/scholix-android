# Studies in Documents

Scholix keeps its existing native course/homework UI. Lemida and Classroom
coursework can create or link a study in Documents. More → Studies contains
assignment-linked and general references. Materials and announcements do not
receive assignment study actions.

Documents owns study files and the original editor. The version-1
`com.feldman.documents.action.STUDY` activity contract exchanges bounded JSON in
`com.feldman.documents.STUDY_HANDOFF`. Requests carry a UUID, title, optional
assignment key, optional local study ID, explicit chooser flag and saved assignment
context. Replies echo the UUID and return the actual study ID, title and revision.
Scholix validates the reply before saving or opening it. Multiple installed
Documents variants require explicit choice. Missing support shows an update/install
message. Cancellation saves nothing in Scholix.

Assignment keys retain Hub's canonical SHA-256 identity: provider, account, course,
activity kind and activity ID. Lemida course IDs are JSON integers; Classroom IDs
are opaque strings, using the normalized account and classroom.googleapis.com
provider. Titles are display text. No credentials or sessions cross the boundary.

The local `StudyCatalog` stores `StudyReference` records. A null assignment key
means a general study. Catalog identity is separate from the Documents package,
study ID and local revision. Optional `StudyPublication` metadata carries a future
publication ID, opaque published revision and download URL. The local copy also
retains the publication revision it was imported from, separately from its local
editing revision and the latest available publication revision. Attaching a local
copy preserves catalog identity; reopening preserves publication metadata. This is the
base for future general/assignment uploads and reviewed updates, not an upload
service. No upload/download, automatic replacement or cross-app file access exists.
Future updates must use Documents' import preview/revision rules and preserve the
user's answer-note identities and progress; a remote version is never proof of a
newer local editable revision.

Documents stores assignment context separately from note/study files. Answer notes
are submission candidates through their existing owner-study identity. The editor
and cards display saved provider wording or explicitly unavailable status. Preparing
hand-in exports a note using the existing PDF flow and can return to Scholix's
homework list. It does not submit or set provider status. Live OpenU assignment
discovery, precise submission routing and actual hand-in are a separate investigation.

Validation: four contract/catalog JVM tests and five Lemida PDF tests pass.
App/test APKs build. Native catalog preservation and actual cross-app
create/reopen/cancel tests pass on the existing Seamless server emulator with
isolated QA package IDs and synthetic data. No physical app or account was changed.
