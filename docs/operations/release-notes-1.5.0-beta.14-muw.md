# LibreClinica MUW · 1.5.0-beta.14-muw release notes

_Successor to **1.5.0-beta.13-muw**. A security release: the heritage API admits only valid API keys and no longer lets any logged-in user create an administrator, and four log statements stop writing passwords, security answers, SQL values and the configuration's secrets._

For older releases see [release-notes-1.5.0-beta.13-muw.md](release-notes-1.5.0-beta.13-muw.md) and its predecessors.

**Deployment-breaking:** none for the app's own users. External clients of the heritage `/pages/auth/api/*` endpoints must send HTTP Basic credentials with a valid API key, which they already had to. No migration, no new configuration key. **After the upgrade, work through [steps 3 to 6](#upgrading-the-app-vm):** they check for accounts created through the flaw and rotate secrets the logs may hold.

---

## Highlights

### The heritage API admits only valid API keys (#351)

The heritage endpoints under `/pages/auth/api/*` are open in the security configuration and rely on one filter for authentication. That filter answered a request without an `Authorization` header with 401 and then ran the endpoint anyway, and it skipped the check entirely for any scheme other than Basic.

- The filter now stops every request that does not carry Basic credentials naming the API key of an existing, not removed and not locked account.
- Nothing inside the app calls these endpoints. The web app and the SPA are unaffected.

### Only an administrator may create accounts through the API (#351)

The account-creation endpoint of the heritage API checked the caller in a way every active account passed. Any logged-in user could create an account of any type, a system administrator included, and received its password and API key.

- It now requires an active system or technical administrator.
- Accounts created through the flaw before this release are not removed automatically. [Step 3](#upgrading-the-app-vm) lists how to find them.

### Secrets leave the logs (#351)

- **The e-signature password check** wrote the typed password in plain text at INFO.
- **The password reset page**, reachable without login, wrote the stored and the typed security answer at INFO.
- **Every legacy SQL statement** wrote its bound values at DEBUG: password hashes, API keys, security answers and clinical data. It now writes only each value's type and position.
- **The application start** wrote the whole `datainfo.properties` at DEBUG, including the database, mail and Remidio passwords and every device and sidecar token. It now writes only the number of keys.

The shipped `docker/config/datainfo.properties` sets `logLevel=debug`, and the setup script copies it to the VM on first setup. Unless it was changed there, the two DEBUG leaks were active in production. [Steps 4 and 5](#upgrading-the-app-vm) check that and rotate what may have been written.

---

## Migrations

None.

---

## Configuration

No new keys. Set `logLevel=info` on the VM, see [step 4](#upgrading-the-app-vm).

---

## Upgrading the app VM

Do this outside clinic hours: the restart takes the app down for a few minutes.

1. **Back up** the database and the file stores.
2. **Run the setup script, then restart**, as for beta.13:
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.14-muw   # --dicom too, if a camera speaks DICOM here
   sudo systemctl restart libreclinica
   ```
3. **Look for administrator accounts nobody remembers creating.** An account created through the API names its creator in `owner_id`.
   ```sh
   cd /opt/libreclinica && sudo docker compose -f compose.yaml -f deploy/compose.production.yaml exec -T db \
     psql -U clinica -d libreclinica -c "SELECT user_id, user_name, user_type_id, status_id, owner_id, date_created, api_key IS NOT NULL AS has_api_key FROM user_account WHERE user_type_id IN (1, 3) OR api_key IS NOT NULL ORDER BY date_created DESC;"
   ```
   `user_type_id` 1 is a system administrator, 3 a technical administrator. Remove any account that should not exist, and check the audit log for what it did.
4. **Check the log level and set it to info.**
   ```sh
   grep '^logLevel' /opt/libreclinica/config/datainfo.properties
   sudo sed -i 's/^logLevel=.*/logLevel=info/' /opt/libreclinica/config/datainfo.properties && sudo systemctl restart libreclinica
   ```
5. **If it was `debug`, assume the secrets are in the logs.** Check, then rotate:
   ```sh
   sudo grep -rl 'DataInfo\.\.\.' /var/lib/libreclinica/tomcat-logs 2>/dev/null | head
   ```
   Rotate the database password (`dbPass` and the Postgres role), `mailPassword`, `core.remidio.password`, the retinal-inference tokens, the DICOM ingest token and the Optomed token, in `datainfo.properties` and `/etc/libreclinica/env` as each requires. Then delete or restrict the old log files, including any copy in the SIEM, as the data-protection rules for logs require.
6. **Replace the retinal-inference tokens if they are still the defaults.** Both defaults are committed in the public repository: the preprocess token (`compose.yaml`, `deploy/compose.production.yaml`, `deploy/setup-ubuntu-host.sh`, `docker/config/datainfo.properties`) and the remote push token, whose default is the placeholder `choose-a-long-shared-secret`.
   ```sh
   grep -E '^core\.retinalInference\.(preprocessToken|remotePushToken)=' /opt/libreclinica/config/datainfo.properties
   sudo grep -E '^RETINAL_INFERENCE_PREPROCESS_TOKEN=' /etc/libreclinica/env
   ```
   Generate a new value with `openssl rand -hex 32`. The preprocess token must be the same in `core.retinalInference.preprocessToken` and in `RETINAL_INFERENCE_PREPROCESS_TOKEN` in `/etc/libreclinica/env`. The push token must match the GPU host's configuration. Restart afterwards.
7. Nothing changes on the acquisition PCs in this release.

---

## Still open at this release

From the code-scanning triage of 2026-09-29, in order of priority:

- **The email check of the login-free pages** (Contact, RequestPassword, RequestAccount) uses a regular expression whose run time grows with the square of the input, so a large form value can occupy a CPU for minutes. Capping the field length fixes it.
- **Request forgery and legacy SDV.** CSRF protection is off, several legacy endpoints change data on GET, and five of the six legacy SDV handlers do not check the caller's role or study.
- **Rules-XML upload** accepts external XML entities.
- **The retinal-inference sidecar's `/preprocess`** builds a path from an unchecked upload id; it is reachable only from inside the compose network or with the sidecar token.
- **Log forging.** Log patterns print messages verbatim, so a line break in a logged value can forge a log line; one pattern change covers nearly all cases.
- **Dependencies.** A Spring Boot 3.5.x patch update closes most of the open dependency advisories; Pillow, python-multipart and requests in the sidecars need updates too.
- The earlier open items from the beta.13 notes stand.
