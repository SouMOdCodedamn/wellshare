# WellShare security test plan

This plan lists every security test for WellShare: what each one proves, how it runs, and when.
Most tests run automatically on GitHub; the rest are short manual checks before a release.

**Test IDs** (like `AND-03`) are printed by the automated checks, so a failure points straight to the row below.

---

## How WellShare stays secure (the model being tested)

1. **The database decides who can do what.** Row Level Security and locked-down functions in `supabase/schema.sql` are the real protection. The app and dashboard are open source, so security must never depend on hiding code.
2. **Phones have one narrow door.** With the public key, a phone can only register, upload its own data, and send SOS. It can never read anyone's data.
3. **Each phone has its own secret token.** 256 random bits, stored hashed on the server, bound to that phone, and encrypted on the phone.
4. **Mentors sign in** and can read and change only their own group.
5. **Everything travels over HTTPS.** Plain HTTP is refused.
6. **Real secrets are never in the code:** the Supabase secret key, the database password and the signing key. Scanners enforce this.

---

## When the tests run

| When | What runs | Where to see it |
|---|---|---|
| Every push and pull request | Secret scan, 115 security rules, 86 database tests, app logic tests, CodeQL | GitHub **Actions** tab; CodeQL results under **Security → Code scanning** |
| Every app build (release or "Run workflow") | APK checks: debuggable, backups, HTTPS-only, permissions, signature, obfuscation, secrets inside the app | "Android app" run → step **Security checks on the APK** |
| Every dashboard deploy | Strict Content-Security-Policy added; secret key refused; published site scanned for secrets | "Mentor dashboard" run |
| Every Monday (and on demand) | **Live check** of your real Supabase project and dashboard from the outside | Actions → **Live security check** → run summary |
| Weekly | Dependabot checks the add-ons the build uses for updates | **Pull requests** tab |
| Before each release | Manual checklist (bottom of this page) | You |

To run the automatic checks on your own computer:

```bash
python3 security/scan_secrets.py          # secrets in the code
python3 security/check_policy.py          # 115 security rules
cd supabase/test && npm install && npm test   # 86 database tests (in-memory Postgres)
SUPABASE_URL=... SUPABASE_KEY=... DASHBOARD_URL=... python3 security/live_check.py   # live check
```

---

## 1. Sandbox verification

### 1a. Android app sandbox

Android gives every app a private sandbox. These tests make sure WellShare doesn't open holes in it.

| ID | Test | Proves | How |
|---|---|---|---|
| AND-01 | Backups disabled (`allowBackup=false`, data-extraction rules exclude everything) | The token and website log can't be copied off the phone through backups or device transfer | Auto: rules + APK check |
| AND-02 | Not debuggable | Nobody can attach a debugger or read the app's private files over USB | Auto: APK check |
| AND-03 | HTTPS only (`usesCleartextTraffic=false`, network config `cleartextTrafficPermitted=false`) | No data is ever sent unencrypted, even by mistake | Auto: rules + APK check |
| AND-04 | Only the phone's built-in certificate authorities are trusted | A certificate the user (or malware) installs can't be used to intercept traffic | Auto: rules |
| AND-05 | Permissions match the reviewed list (`security/expected-permissions.txt`) | No permission is added without a deliberate review | Auto: rules + APK check |
| AND-06 | Only the launcher screen and the Accessibility service are reachable by other apps, and the service only by the system | Other apps can't start hidden screens or services | Auto: rules |
| AND-07 | No logging, WebView, code loaded at runtime, shared storage, shell commands, TLS overrides or `http://` URLs | Common Android leak and injection paths are absent | Auto: rules |
| AND-08 | Every PendingIntent is immutable | Other apps can't hijack the notification's SOS button | Auto: rules |

**Manual check on a test phone or emulator (before each release):**
1. `adb shell run-as org.wellshare.app ls` should fail ("package not debuggable").
2. `adb shell dumpsys package org.wellshare.app | grep -A3 "exported=true"` should list only MainActivity and Watcher.
3. Optionally, scan the APK with **MobSF** (free, runs in Docker). Expect no high-severity findings. "Accessibility service", "call phone" and "location" appear as *declared on purpose*.

### 1b. Test sandbox for the backend

| ID | Test | How |
|---|---|---|
| DB-T | All database rules are tested in a throwaway, in-memory Postgres (PGlite) that copies Supabase's roles. Nothing touches real data. 86 checks, including the upgrade from v1 | Auto: "Tests" workflow |
| STG | Manual tests (installing, timers, SOS) use a **separate test group** (e.g. join code `TEST01`) or, ideally, a **second free Supabase project** used only for testing. Never test with real students' data. | Manual |

### 1c. Dynamic test: watch what the app sends

On an emulator with a **debug** build: route traffic through mitmproxy and use the app for 10 minutes. Expected:
- Connections go only to `https://<project>.supabase.co`.
- Requests contain only the data listed in `PRIVACY.md`: totals, app names, site names, status, and location only after SOS.
- With the **release** build, interception fails. That is test AND-04 working.

---

## 2. API keys and authentication (what is "hardcoded")

### What is built into the app and dashboard, and why that's safe

| Item | Where | Secret? | What protects it |
|---|---|---|---|
| Supabase project URL | App, dashboard | No (public address) | — |
| Supabase **publishable / anon key** | App, dashboard | **No, public by design.** Anyone can extract it from the APK. | It grants nothing by itself. Tests KEY-02 and LIVE-01…05 prove it can't read or change data. |
| Per-phone upload token | Phone only, encrypted | **Yes** | Random 256-bit, stored hashed (SHA-256) on the server, only works with its own device ID, replaced on re-register |
| Mentor email + password | Mentor's head | **Yes** | Supabase Auth (passwords stored hashed); sign-ups off; MFA recommended |
| Supabase **secret / service_role key**, database password | **Nowhere in WellShare** | **Yes: full access** | Never used. Scanners fail the build if it appears. |
| Android signing key | GitHub secret only | **Yes** | Never in the repo (`.gitignore`, scanner) |

| ID | Test | Proves | How |
|---|---|---|---|
| KEY-01 | No secret keys, private keys, database URLs with passwords, tokens or keystore files in the repo, the APK or the website. JWT keys are decoded: a `service_role` key fails the build. | Secrets can't leak through the code | Auto: `scan_secrets.py` on every push, every APK and every deploy |
| KEY-02 | With only the public key: every table is unreadable and unwritable; mentor functions, `setup_group` and internal helpers are refused | The public key is harmless | Auto: database tests + weekly live check (LIVE-01…04) |
| KEY-03 | The dashboard deploy refuses a secret key or a malformed project URL | A mistake in GitHub secrets can't publish a secret | Auto: `dashboard/build.py` |
| KEY-04 | Phone tokens: wrong token, token from another phone, or old token after re-register are all refused | Stolen or guessed tokens don't work | Auto: database tests |
| KEY-05 | Public sign-ups are switched off | Strangers can't create mentor accounts | Auto: live check (LIVE-06) |
| KEY-06 | Mentors use strong, unique passwords and turn on two-factor sign-in (Supabase supports TOTP) | Mentor accounts resist password guessing | Manual |

**If a key leaks:**
- **Publishable key:** low risk. Rotate it in Supabase (Settings → API Keys), update the `SUPABASE_KEY` GitHub secret, re-run the dashboard deploy and publish a new app release.
- **Secret key or database password:** rotate it **immediately** in Supabase, then check the data for changes.

---

## 3. Encryption

| ID | What | How it's done | Test |
|---|---|---|---|
| ENC-01 | **In transit** | HTTPS/TLS for all traffic (Supabase API, GitHub Pages). Plain HTTP refused by the app. | AND-03, LIVE-07 |
| ENC-02 | **At rest on the server** | Supabase encrypts all customer data at rest with AES-256 | Supabase's responsibility (shared-responsibility model) |
| ENC-03 | **At rest on the phone** | Upload token, device ID and website log are encrypted with AES-256-GCM. The key lives in the Android Keystore and can't be exported. | Auto: rules check the code uses it |
| ENC-04 | **The code** | Release builds are shrunk and obfuscated with R8: class and method names are scrambled and unused code removed | Auto: rules + APK check confirms internal class names are gone |

**About "encrypting all the code":** code a phone has to run can't be truly encrypted, because the phone must be able to read it. Obfuscation (ENC-04) makes it harder to read, nothing more. WellShare's code is public on GitHub anyway. That's why its security comes from the server rules (section 4), which hold even when someone reads every line of the app.

---

## 4. Access control (database)

| ID | Test | How |
|---|---|---|
| DB-01 | Row Level Security is on for every table | Auto: rules |
| DB-02 | Phones (anon) have no direct table access; nothing granted to PUBLIC | Auto: rules + DB tests + live check |
| DB-03 | Every `security definer` function pins `search_path` (prevents function hijacking) | Auto: rules |
| DB-04 | Default execute rights revoked; phones can call only the 6 phone functions; `setup_group` only from the SQL Editor | Auto: rules + DB tests |
| DB-05 | One phone = one participant; one roll number = one phone; second user profile refused | Auto: DB tests |
| DB-06 | A mentor sees and changes only their own group (reports, timers, SOS, reset), even when asking for another group's ID | Auto: DB tests |
| DB-07 | Mentors can't edit reports or fake usage | Auto: DB tests |

---

## 5. Abuse and spoofing

| ID | Risk | Protection | Test |
|---|---|---|---|
| ABU-01 | Guessing group codes to join | 10 wrong codes per network address per 15 min, then paused; max 100 new phones per group per 10 min. **Use long, random join codes** (e.g. `BLK-A7Q9-2026`). | Auto: DB tests |
| ABU-02 | SOS spam | Max 5 SOS per phone per 10 minutes | Auto: DB tests |
| ABU-03 | Another app opens WellShare's SOS screen | It only shows the confirm dialog; the student must tap "Send SOS" | Manual |
| ABU-04 | A student with a rooted phone fakes lower numbers using their own token | Can't be fully prevented on a device the student controls. Mentors should watch for odd patterns (zero screen time with many unlocks, sudden drops). | Known limit |
| ABU-05 | Avoiding timers by switching off Accessibility, or uninstalling | Can't be prevented. The dashboard flags it at the next sync ("Timers off") or after 12 h ("No report"). | Auto: dashboard alerts |

---

## 6. Dashboard (web) security

| ID | Test | How |
|---|---|---|
| WEB-01 | All data (names, app names, websites) is inserted as text, never as HTML; no `eval` | Auto: rules. Manually tested with names like `<img onerror=…>`: shown as text, nothing ran. |
| WEB-02 | Links opening new tabs use `rel=noopener` | Auto: rules |
| WEB-03 | No third-party scripts; the sign-in library is copied from npm (integrity-checked) and served from the dashboard's own site, with a subresource-integrity hash | Auto: rules + deploy |
| WEB-04 | Strict Content-Security-Policy: only the page's own script (pinned by SHA-256 hash) and the library may run, and the page may talk only to your Supabase project | Auto: deploy adds it; live check confirms |
| WEB-05 | Refuses to run inside another site's frame (click-jacking) | Auto: rules. Manually tested. |

---

## 7. Supply chain and releases

| ID | Test | How |
|---|---|---|
| SUP-01 | Every GitHub Action is pinned to an exact commit, so a tag can't be swapped for malicious code | Auto: rules |
| SUP-02 | Every workflow declares minimal permissions; no `pull_request_target` | Auto: rules |
| SUP-03 | Dependabot suggests updates weekly; CodeQL scans JavaScript and Kotlin on every push | Auto |
| REL-01 | APK is signed with APK Signature Scheme v2 | Auto: APK check |
| REL-02 | APK is signed with the **permanent** release key (warns if a temporary debug key is used) | Auto: APK check (warning until the key is set up) |
| REL-03 | Each release has a `.sha256` file, so anyone can check the APK wasn't altered | Auto: release workflow |

---

## 8. Privacy

| ID | Test | How |
|---|---|---|
| PRI-01 | Only the data listed in `PRIVACY.md` is sent | Manual (section 1c) + code review |
| PRI-02 | Website tracking keeps site names only: never full addresses, searches or page text; nothing read while typing in the address bar | Auto: unit tests (`SiteMathTest`) + rules |
| PRI-03 | Location is read only when SOS is pressed | Code review (`Sos.kt` is the only user of location) |
| PRI-04 | "Reset phone link" deletes all of a student's reports, timers and SOS history | Auto: DB tests |

---

## Release checklist (before giving a new version to students)

- [ ] All GitHub checks are green: Tests, Security, CodeQL (no new high-severity alerts), Android app (APK checks passed)
- [ ] The APK check shows **"Signed with the permanent release key"** (REL-02)
- [ ] The latest `schema.sql` has been run in Supabase, and **Live security check** passes
- [ ] Manual sandbox checks (1a) on a test phone
- [ ] Join codes are long and random; mentors use strong passwords with two-factor sign-in on
- [ ] `PRIVACY.md` is up to date and shared with students

## If something goes wrong

| Situation | Do this |
|---|---|
| Secret key or database password exposed | Rotate it in Supabase now; review recent data changes |
| A mentor's password is leaked | Change it in Supabase → Authentication → Users; sign out all sessions |
| A student's phone is lost | Mentor → **Reset phone link** (the old token stops working) |
| A fake participant appears | Mentor → **Reset phone link** on it; change the join code with `setup_group` |
| Someone reports a vulnerability | Follow `SECURITY.md` |
