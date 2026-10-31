# Schooly provider

Schooly supports native national-ID sign-in with a verification code sent to the school's registered phone number or email. If the account belongs to several schools, choose the school after verification. Parent accounts expose grades separately for each linked student; choose the student whose schedule should appear on the Schedule page and in schedule widgets. The selection and session survive app restarts. Edit the Schooly provider in Settings to change students or renew an expired login.

Grades include the selected year's assessment periods, with duplicate assessment IDs removed. Numeric zero and textual evaluations are retained. Deleted, draft, unpublished, and other students' assessments are excluded. Timetables use the student's weekly schedule, published changes, cancellations, rooms, teacher names, school hour offsets, and bell times. The original view uses fixed timetable groups. Background session checks never send verification codes.

## Protocol evidence

Inspected the official Google Play build of [`il.co.schoolapps.schoolyapp`](https://play.google.com/store/apps/details?id=il.co.schoolapps.schoolyapp): version **1.2.6**, version code **51**, updated October 21, 2026. The base APK SHA-256 is `42e42fae61e54a9dd8835d13d5c4763bae5f468bb5eec835e74daed8aaa58679`. It was downloaded through Google Play and pulled from the device, together with its ARM64 split. Extracted Flutter AOT strings identify the production host, auth repository, pupil-card grade models, and timetable endpoints. These were cross-checked against the public production web client at [app.schooly.co.il](https://app.schooly.co.il/), including its pupil-card and student-weekly-timetable parsers. APKs and extracted third-party code stay local under `.tmp/schooly/`.

API host: `https://api.schooly.co.il`.

| Purpose | Endpoint |
| --- | --- |
| Find registered contact methods | `POST /api/auth/identify` with `userId` |
| Request the user-selected verification code | `POST /api/auth/send-otp` with `userId`, `contactType`, `contactValue` |
| Verify code and select school | `POST /api/auth/verify-otp` with `userId`, `otpCode`, `otp`, optional `school_id` |
| Set school scope | `POST /api/schools/switch-school` with `school_id` |
| Restore profile and linked students | `GET /api/users/me` |
| Assessment periods | `GET /v1/schools/periods` |
| Published student grades | `GET /v1/academic/pupil-card?student_login_id=…&year=…&period=…` |
| Student timetable and published overlays | `GET /v1/schedule/weeklyTimeTable?ttDate=yyyyMMdd&pupilId=…&includeOverlay=true&overlayEventsMode=published` |
| Bell schedule | `GET /v1/schedule/lesson-hours?scope_id=…` |

The client preserves session cookies and the returned bearer token. It uses normal HTTPS certificate validation. An unauthenticated request to the live profile endpoint was confirmed to return JSON with HTTP 401. Automated tests cover fixture-based OTP/school selection, session headers, student separation, grade visibility and period merging, timetable response variants, cancellations, and session expiry. Authenticated account access still requires a user's live sign-in; fixture tests do not establish that a particular school exposes grades or a timetable to that account.
