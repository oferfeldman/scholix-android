# Shahaf timetable integration

Analyzed APK: `com.shahafsoft.timetableapp`, version 1.2.2850.
The application is Flutter AOT, not a WebView wrapper. Its MOE sign-in uses a WebView;
data requests use `dart:io` HttpClient. The ARM64 snapshot retains named API methods.

## Confirmed login contract

Base URL: `https://view.shahaf.info/api/`.

MOE entry: `https://timetable.shahaf.info/ShfSignIn.aspx?sid=shfapp&cid=<device UUID>`.
The success redirect is `https://timetable.shahaf.info/SignInSuccess.aspx?s=<payload>`.
Decode the payload by replacing `.` with `=`, `_` with `/`, and `-` with `+`,
then Base64, UTF-8, JSON. It is an auth response, not a token query parameter.
Observed response keys: `Name`, `Schools`, `Student`, `Token`, `Tz`, `VCode`.
Student keys: `Semel`, `Grade`, `Class`, `ClassMid`.

Live verified on 2026-10-13: MOE sign-in succeeds for an account whose school does
not use Shahaf. The resulting token is accepted by `school/student/`, which returns
HTTP 200 with `[]`. Login success does not imply that a timetable is published.
No account credentials or session tokens are included in this document.

Shahaf password login is a separate route:
`GET user/authstudent/?usr=<username>&pwd=<password>`.
Its successful response is parsed as `AuthResponseExt` in the APK; password-login
success has not been tested with a Shahaf account.

## Requests recovered from assembly

All authenticated API calls use `token` in the query string. The earlier cookie /
Bearer hypothesis and guessed `view/student/<school>/classes/` route were incorrect.

- `GET school/student/?token=...` returns a bare school array. Fields: `name`, `semel`, `code`, `status`.
- `GET student/<semel>/classes/?token=...` reads `Classes` with `Id`, `Name`, `Grade`, `Number`.
- `GET view/<semel>/<code>/appdata/?token=...&clsid=...&<view parameters>` returns the published views.
- `GET settings/0/0/create/?token=...&appid=<device UUID>&platform=2&appt=1` creates an official-app instance.
- `GET settings/<semel>/<code>/appprms/?token=...&appid=...&tz=...&cid=...&devtkn=...&msg=...&platform=2&appt=1`
  updates official-app push settings. These notification registration calls are not made by Scholix.

Evidence: `Api._downloadString` at `0x332508` performs getUrl/close/UTF-8 join;
`Api.getAppData` at `0x34ebb8`; `Api.getClassesAsync` at `0x3522d0`;
`Api.getStudentSchoolsAsync` at `0x3d185c`; SSO payload decoder at `0x3eab68`.

## Published view schema

`AppData`: `version`, `views`.
`AppView`: `h` (heading), `t`, `i`, `c`, `tp`, `k`, `d` (data blocks), `tb` (tabs), `p` (parameters).
`ViewTabs`: `l`, `r`, `dt`, `tb`.
`ViewTab`: `t` (title), `id`, `k`, `i` (data blocks).
`ViewParameter`: `id`, `l` (label), `i`, `o` (options with `id`, `l`).
`ViewData`: `t` (title), `k`, `r` (rows).
`ViewDataRow`: `h` (header elements), `i` (content elements), `sep`, `rc`, `hbc`, `hw`, `he`, `hta`, `mbc`, `mw`, `me`, `mta`.
`DataElement`: `t` (text), `u` (link), `s` (style object or style string).

Scholix renders the published text, links, tabs, school/class choices and filters
on its Schedule page. These presentation rows do not provide reliable semantic
subject/teacher/time records, so normalized schedule exports are not implemented.
Server typography/colors are not reproduced. Published-data rendering and filter
semantics still need a linked school with nonempty data for live verification.

## Tool artifacts

`F:/tmp/shahaf_apk/r2out/` contains named functions, classes, reconstructed pool
values and annotated ARM64 assembly. r2flutter + radare2 produced the usable
extraction. A local r2flutter resolver patch exposes VM-base string names when
decoding pool slots, recovering short JSON keys such as `t`, `h`, and `i`.
The standalone flutterdec alpha binary was inspected; it did not provide a usable
decompilation of this target in the prior run.
