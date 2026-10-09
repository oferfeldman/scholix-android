# Google Classroom setup

Scholix connects a student's Google account through Google Play services authorization and the official Classroom REST API. Google owns the consent and account selection UI. Access tokens stay in memory and are renewed through Play services; Scholix does not store Google passwords, access tokens, or refresh tokens.

## Google Cloud configuration

1. In the Scholix Google Cloud project, enable **Google Classroom API**.
2. Configure **Google Auth Platform → Branding / Audience**. For accounts outside the project's Workspace organization, choose External. While publishing status is Testing, add the school account under Test users. School administrators may also need to allow this app.
3. Under **Data Access**, add these scopes:
   - `https://www.googleapis.com/auth/classroom.courses.readonly`
   - `https://www.googleapis.com/auth/classroom.coursework.me.readonly`
   - `https://www.googleapis.com/auth/classroom.courseworkmaterials.readonly`
   - `https://www.googleapis.com/auth/classroom.announcements.readonly`
   - `https://www.googleapis.com/auth/classroom.profile.emails`
   - `https://www.googleapis.com/auth/userinfo.email`
4. Under **Clients**, create an **Android** OAuth client:
   - Package name: `com.feldman.scholix`
   - Current local debug/release signing SHA-1: `7A:22:BA:FC:ED:E1:72:AB:6D:1A:8E:F1:01:13:DF:B3:70:B6:B1:87`
   - Verify fingerprints after changing signing configuration with `./gradlew :app:signingReport`. If Google Play App Signing uses another certificate, register that SHA-1 too. Builds with an application ID suffix need a matching Android client.
5. No client ID or client secret needs to be copied into the Android app. The authorization API identifies the registered Android application by package and signing certificate. Public distribution may require Google's OAuth verification for sensitive scopes.

## School accounts and MOE login

MOE sign-in identifies the student's school Google account. Google must separately authorize Scholix to read Classroom; an MOE session does not grant Google API access.

If Google authorization returns `SERVICE_DISABLED` for the school account while a personal account works, ask the school's Google Workspace administrator to check Scholix's app access for the student's organizational unit. This error alone does not identify the exact policy. Google's own Classroom app working does not establish permission for Scholix.

The direct browser authorization test on October 3, 2026 also failed with `admin_policy_enforced`, requesting only `classroom.courses.readonly` through the project's existing Web client. Google explicitly reported that the organization's policies restrict account data access. Changing the Android account picker or using browser authorization does not resolve this restriction.

Administrator handoff:

- Application: **Scholix**, Android package `com.feldman.scholix`.
- OAuth client ID: `826282782447-76e1d0gbr9mc5gf4uqdlg8k05c22e8ka.apps.googleusercontent.com`.
- In the Admin console, inspect **Security → Access and data control → API controls → Manage third-party app access** for this client and the student's organizational unit. Configure access to the scopes listed above if school policy permits.
- For users designated under 18, an administrator must configure third-party app access in the Admin console; students and application code cannot configure it. See [Google's Classroom administrator guidance](https://developers.google.com/workspace/classroom/guides/key-concepts/admin-actions).

## Using Classroom

- Add **Google Classroom** from the initial provider chooser or Settings → Providers → Add provider. Choose a Google account and grant all requested permissions. Multiple accounts are supported.
- **Grades:** courses include active and archived classes. Published assignment grades use percentages in the shared grade UI; the assignment title retains the original points. Draft grades and ungraded work are excluded from the average. Zero-point results on graded assignments count in the average. The app's average is an assignment mean, not Classroom's category-weighted overall grade.
- **Homework:** select the Classroom account in the provider picker. Active courses include assignments/questions, materials, descriptions, due dates in local time, late/submission state, available grades, answers, and attachment links. The latest complete homework snapshot is cached; Refresh reloads it. Opening the page refreshes automatically. Existing Lemida automatic notifications continue independently.
- **Messages:** choose Classroom to view class announcements, their full text and attachments. Classroom has no API for attendance, private messages, or stream/private comments, so these are not presented as supported features.
- Open attachments through their Google/website links; Google handles any additional document permissions. **Open in Google Classroom** handles submission and discussion. The API's turn-in, reclaim, and modification methods restrict access to the developer project that created the coursework, so Scholix cannot perform those actions on ordinary existing teacher assignments.
- Reconnect from Settings → Providers → Edit provider, or the homework error action. Reconnecting the same account preserves its ID, custom course names, hidden courses, and cached homework.

Official references: [Android authorization](https://developer.android.com/identity/authorization), [Classroom scopes](https://developers.google.com/workspace/classroom/guides/auth), [API resources](https://developers.google.com/workspace/classroom/reference/rest), [turn-in restrictions](https://developers.google.com/workspace/classroom/reference/rest/v1/courses.courseWork.studentSubmissions/turnIn).
