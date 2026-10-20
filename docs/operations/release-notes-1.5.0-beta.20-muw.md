# LibreClinica MUW · 1.5.0-beta.20-muw release notes

_Successor to **1.5.0-beta.19-muw**. A patch release for password handling, from setting up colleagues' accounts after beta.19. **Changing a password failed with an internal server error (500) for every user**, including the change forced at first sign-in. Also: you can now change your own password from the profile menu, and an administrator can reset the password of an account whose invitation is still open._

For older releases see [release-notes-1.5.0-beta.19-muw.md](release-notes-1.5.0-beta.19-muw.md) and its predecessors.

**Deployment-breaking:** nothing.
- **No migrations:** the changelog count stays at 1206.
- **Nothing to configure.**
- **No change to the host setup, nginx or the sidecars.** Retinal-inference and dicom-scp get the same tag as usual, but their code is unchanged.

---

## Highlights

### Changing a password no longer fails with a 500 (#435)

**The bug.**
- **The page.** *Passwort ändern* is the page the app forces at first sign-in, after an invitation or a reset.
- **What failed.** It reads the English error messages to word the password rules. The server loaded a language's messages only after some request had used that language.
- **Why only here.** On the VM everyone works in German, so the English messages were never loaded. Every attempt failed with a `NullPointerException`, logged as `API 500 unexpected` from `MeApiController.changePassword`.
- **Why tests missed it.** Our tests run in English, which hid it.

**The fix.**
- **A language loads on first use.** A language's messages now load the first time they are asked for.
- **A thread with no language falls back to English.** This covers a thread no request has set a language on, such as a scheduled job, and failed the same way before. English is the app's default.

**Until the upgrade**, a colleague can change their password on the old screen, `/LibreClinica/ResetPassword`. It needs a security question and answer in addition to the passwords.

### Change your own password from the profile menu (#435)

- **Before.** `/change-password` opened only when the change was forced, and sent everyone else home. No screen of the new interface let a user change their password by choice.
- **Now.** The profile menu (top right) has **Passwort ändern** for every local account.
  - Opened by choice, the page shows no first-login banner and has a cancel button.
  - SSO and LDAP accounts do not get the entry: their password belongs to the identity provider or the directory.

### Reset the password of an open invitation (#435)

- **The gap.** *Passwort zurücksetzen* in the users list appeared only for accounts that had signed in at least once.
- **Who it hit.** An invited user who never signed in (*Einladung offen*) got no button. Yet a lost first password is exactly when a reset is needed.
- **Now.** The button appears for these accounts too. The server already accepted the reset; only the button was missing.

---

## Upgrading the app VM

Do this outside clinic hours. The restart takes the app down for a few minutes, and everyone signs in again afterwards.

1. **Back up, and dry-run against that backup.**
   ```sh
   sudo systemctl start libreclinica-backup-db.service
   IMAGE=ghcr.io/luviku/libreclinicamuw:1.5.0-beta.20-muw sudo -E /opt/libreclinica/deploy/dry-run-migration.sh
   ```
   **Expect 0 new changesets (1206 → 1206)** and a healthy start. The rollback is `1.5.0-beta.19-muw`.

2. **Set the tag and restart:**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.20-muw   # plus --dicom, as on the last run
   sudo systemctl restart libreclinica
   ```

3. **Smoke test** (runbook §4) and the checks below. **If anything is wrong, roll back** by running the same two commands with `1.5.0-beta.19-muw`. No data changes need undoing.

---

## What needs a human to check after the upgrade

- **The 500 is gone.** A user with a forced password change (a new account, or one just reset) sets a new password on the page that opens after sign-in and reaches the home page. No `API 500 unexpected` from `MeApiController.changePassword` appears in `libreclinica.json`.
- **Change by choice.** For a local account, the profile menu shows *Passwort ändern*. The page has a cancel button, and changing the password works. Sign out and in with the new password.
- **Reset for an open invitation.** In the users list, an account shown as *Einladung offen* has *Passwort zurücksetzen*. The reset shows a one-time password once.

---

## Still open at this release

- **CI results.** CI run [39434976227](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/39434976227) on `e6b1a830d` (lc-develop after #436) passed all seven jobs. The first attempt of the Maven job failed on a connection reset from Maven Central and passed on rerun. Counts: Maven build and unit tests (core 593, web 1,541) and the SPA (2,607 vitest tests, 4 skipped). The integration tests on PostgreSQL 14 and 17, the compose smoke test and the three Python sidecar suites passed; their logs could not be fetched for counts. The security scan (Trivy, Gitleaks, OWASP dependency-check) passed on #438, which accepts CVE-2026-40984 in micrometer-core as a Trivy false positive: the build ships 1.17.1, Trivy misreads the root POM as 1.14.14.
- **The legacy *Reassign subject* screen** does not offer the parent study when a subject sits at a site, so moving a subject from a site to its study changes nothing. Left as it is for now.
- **Carried over from beta.19 and still open:** see the [beta.19 notes](release-notes-1.5.0-beta.19-muw.md#still-open-at-this-release).
