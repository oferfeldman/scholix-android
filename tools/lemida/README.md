# Lemida Python exporter

`lemida.py` extracts your Moodle courses, homework links, quizzes, resources,
completion indicators, dates, assignment status tables, and grade report tables.
Hebrew is preserved in UTF-8 JSON. Run from PowerShell in this directory.

## Setup

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m playwright install chromium
```

## Extract the supplied pages

```powershell
.\.venv\Scripts\python.exe lemida.py offline
```

Result: `exports/saved_pages.json`. The supplied files include two course pages,
the public and signed-in homepages, and Microsoft password/Authenticator pages.
They do not include grade reports or individual homework detail pages. Offline
mode cannot recover those missing data or authenticate from the saved HTML.

## Sign in and read current data

```powershell
.\.venv\Scripts\python.exe lemida.py login
.\.venv\Scripts\python.exe lemida.py sync
```

A dedicated browser opens. Enter your credentials there and complete Microsoft
Authenticator, number matching, or an SMS code if Microsoft offers that method
for your account. The script waits for the authenticated Moodle session and
retains it in `.lemida-profile`. It never embeds credentials or replays the saved
Microsoft login forms. SMS is preferred automatically when Microsoft offers it. Use `--mfa manual` to
keep Authenticator selection under your control. CAPTCHA and Authenticator
approvals still need interactive completion; SMS can be entered in the browser
or using the hidden console prompt.
Close another script using that profile before starting a new one.

For SMS, the supplied `Sign in to your account.sms.html` confirms the code field
`#idTxtBx_SAOTCC_OTC`, submit button `#idSubmit_SAOTCC_Continue`, and `OneWaySMS`
verification method. You can prefer SMS and enter the code at a hidden terminal prompt:

```powershell
.\.venv\Scripts\python.exe lemida.py login --mfa sms --console-sms
```

This selects the Authenticator alternative and SMS when recognizable controls
are available. If the method picker differs, select SMS yourself in the browser.
The script submits one entered code and does not automatically resend or retry
failed codes. Any subsequent correction can be made in the browser. Password
entry remains in Microsoft's browser form. The session is reused on later runs.

To read specific courses, or if automatic discovery is incomplete:

```powershell
.\.venv\Scripts\python.exe lemida.py sync --course-id 110304 --course-id 110315
.\.venv\Scripts\python.exe lemida.py sync --headless --course-id 110304
```

`--headless` requires an existing session; run `login` again when it expires.
Course discovery uses the authenticated Moodle JSON AJAX API with pagination,
independent of the visible course filter. Explicit IDs select only those courses.

Results go to `exports/live.json`. Each course contains activities and homework;
homework details contain descriptions, dates, submission/status tables, and file
links. Grade reports preserve table cells rather than guessing numeric values
from Hebrew labels. Moodle errors appear in the export's `errors` list and cause
exit code 2. An expired session saves partial results and stops with exit code 1.

The script reads pages; it does not submit homework, attempt quizzes, change
completion, or download files automatically. Viewing an activity may still count
as a view in Moodle. The profile holds authenticated session data; exports and
the supplied HTML may contain personal academic data. Keep those locally.

## Observed implementation

- The site is Moodle, using RTL Hebrew; its runtime theme is `learnr` and its
  markup also includes Boost Union classes.
- Homepage login: `/auth/multioauth/login.php?userType=teacher` (observed link,
  not an assumption about your account role).
- Microsoft redirects use an OAuth authorization-code flow and `openid` scope;
  the Moodle callback is `/auth/multioauth/login.php`.
- Saved Microsoft forms use tenant `/login` and `/common/SAS/ProcessAuth` for
  interactive second-factor approval. Their transient state cannot be reused.
- Course pages: `/course/view.php?id=...`.
- Homework: `/mod/assign/view.php?id=...`; quizzes: `/mod/quiz/view.php?id=...`.
- Resources: `/mod/resource/view.php?id=...`, `/mod/folder/view.php?id=...`,
  and `/mod/url/view.php?id=...`, depending on the course.
- The grade overview link `/grade/report/overview/index.php` is in the supplied
  HTML. Live mode uses Moodle's conventional per-course user report at
  `/grade/report/user/index.php?id=...`; this needs authenticated verification.

Offline parsing is verified against the supplied course pages. Interactive login
and live export succeeded on the desktop. The updated JSON course-discovery
protocol was verified on the phone; Python discovery has mocked pagination and
expired-session tests. Activity details and grade tables remain HTML reads.

## Parser checks

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

The Android Microsoft picker can also be checked with mocked browser pages:

```powershell
.\.venv\Scripts\python.exe verify_phone_sms.py
```

This intercepts every HTTP request and sends no SMS. It checks selection without
clicking, one request despite a delayed page transition, detached candidates,
code submission, and HTTPS origin restrictions.
