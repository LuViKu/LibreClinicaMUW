# Multicenter internet readiness

This page says what has to change before the platform runs the multicenter nAMD
treat-and-extend study. In that study, external centers reach the platform
across the internet, not only from inside the MUW network.

It is written for the people who must sign off on that:
- the department head;
- MUW IT, who own the DMZ and the reverse proxy;
- the data protection officer;
- the clinical lead of the nAMD module.

It is based on a code and configuration review of `lc-develop` on 2026-10-06,
not on a penetration test.

The short version: **the platform is not ready to be exposed as it is.** It was
built on the assumption that the MUW network is the only access gate. Several
endpoints need no login because of that assumption, and the reverse proxy
currently forwards all of them. The fixes are known and mostly small. Some
decisions belong to people outside the development team; they are listed at
the end.

---

## Decisions already taken (2026-10-06)

| Topic | Decision |
|---|---|
| How external centers connect | Through the MUW DMZ and its reverse proxy. The app VM itself is not in the DMZ. |
| Deployment | The multicenter study gets **its own VM, database and storage**, separate from the internal MUW deployment. MUW-internal patients are never on the internet-facing host. |
| Multi-factor authentication | Not reintroduced. Compensate with account lockout, rate limiting, a stronger password policy, and a shorter session. **The DPIA has to accept this explicitly.** |
| Self-service password reset | Removed. Study administrators reset passwords. |
| Regulatory status of the AI | Investigational, defined in the study protocol. The investigator decides; the recommendation is advisory. |

## Gates before go-live

Each item must be closed (or explicitly accepted by its owner) before the DMZ
rule is opened. "Branch" names the change that fixes it.

### Patient safety (nAMD module)

| # | Finding | Fix | Branch / owner |
|---|---|---|---|
| S1 | When data is missing, the recommendation engine treats it as "no disease". A missing fluid volume, a missing BCVA, a failed fetch or a clinical flag that was never recorded becomes 0 or `false`, so the engine can recommend **EXTEND** when it has no data. | The engine returns "insufficient data" with the list of missing inputs and never extends on unknown input. | `fix/muw-namd-fail-closed` |
| S2 | The inference sidecar defaults to the `placeholder` adapter, which produces deterministic fake volumes. Nothing in the SPA hides a recommendation built on them. | The SPA refuses placeholder results. The internet-facing setup refuses to start with the placeholder adapter. | `fix/muw-namd-fail-closed`, `feature/muw-multicenter-edge-hardening` |
| S3 | The engine differs from the rules document in three ways: <br>• the cumulative and nadir SRF rule is never given its inputs, so it never fires; <br>• `NEW_HEMORRHAGE` fires on any haemorrhage, new or not; <br>• the base interval comes from the gap between scans, not from the last decided interval. <br>Separately, the KEEP trigger names in the code and the document do not match. | These change recommendations, so they need the clinical lead's sign-off before anyone codes them. | Clinical lead |
| S4 | The thresholds are not signed off (the rules document says "pending"). | Sign-off, recorded with `NAMD_THRESHOLDS_VERSION`. | Clinical lead |
| S5 | Traceability: the decision snapshot does not record the model version, the job id or the input hash. `model_version` is a hard-coded string that stays the same when the weights change. | Record a model manifest (model id, weights SHA-256, container digest, preprocess version) per result, and copy it into the decision snapshot. | Follow-up batch |
| S6 | The rationale required on override is checked only in the browser, not on the server. | Validate it on the server. | Follow-up batch |
| S7 | Only Heidelberg `.e2e` OCT is supported. Laterality comes from the form and is not checked against the file. | The protocol limits sites to Spectralis with a fixed scan protocol. Cross-check laterality on upload. | Protocol + follow-up batch |

### Application security

| # | Finding | Fix | Branch |
|---|---|---|---|
| A1 | No-login upload portals (OCT, image, BCVA, generic upload) are reachable through nginx. Anyone can search subject labels by a three-character prefix, list a subject's visits, upload files against real subjects, and delete uploads. | Switched off by `libreclinica.deployment.internet-facing=true`, and blocked again at nginx. External centers use the logged-in upload. | `feature/muw-multicenter-app-hardening` |
| A2 | Endpoints that rely on a shared token only (internal DICOM ingest and worklist, the Optomed and uploader device endpoints), plus `/actuator/info`, `/actuator/prometheus` and Swagger, need no session. | Same switch, plus an nginx block that also catches `;param` path tricks. | both hardening branches |
| A3 | The upload rate limiter keys on the first `X-Forwarded-For` entry, which the client controls. nginx appends to that header instead of replacing it. | Use the client address that Tomcat's RemoteIpValve resolves, and have nginx set the header to `$remote_addr`. | both hardening branches |
| A4 | No brute-force protection: account lockout is seeded off, and nothing throttles login, account requests or the contact form. | A Liquibase changeset turns lockout on (5 attempts). nginx `limit_req` covers login, account requests and contact. | both hardening branches |
| A5 | The self-service password reset uses a security question, emails a new password in plain text, and tells callers whether an account exists. | Removed. | `feature/muw-multicenter-app-hardening` |
| A6 | Seeded `root` / `12345678` sysadmin. | The app refuses to start in internet-facing mode while any active account still has the seed hash. | `feature/muw-multicenter-app-hardening` |
| A7 | The login response tells a locked account apart from a wrong password, which confirms that a username exists. | Generic response in internet-facing mode. | `feature/muw-multicenter-app-hardening` |
| A8 | Missing security headers: no HSTS, no CSP, no Referrer-Policy. The TLS cipher list is dated. | Added in nginx. | `feature/muw-multicenter-edge-hardening` |
| A9 | Session idle timeout is 60 minutes. | 30 minutes on the multicenter host. | `feature/muw-multicenter-edge-hardening` |
| A10 | Isolation between centers is enforced in the app (role per site), and no test proves it. | Before go-live, run a test with two site accounts that tries every ID-taking `/pages/api/v1/**` endpoint across sites. Give every external user a **site-level** role, never a study-level one. | QA, follow-up |

### Host and network

| # | Finding | Fix | Branch / owner |
|---|---|---|---|
| H1 | Port 8080 is published on `0.0.0.0`, which bypasses nginx and TLS. The DICOM port falls back to `0.0.0.0` when its setting is missing. | Internet-facing mode binds to loopback and publishes no DICOM port. | `feature/muw-multicenter-edge-hardening` |
| H2 | No host firewall, by design ("the campus perimeter does it"). | `DOCKER-USER` rules allow inbound 80/443 only, and SSH only from `ADMIN_CIDRS`. | `feature/muw-multicenter-edge-hardening` + MUW IT |
| H3 | The push to the remote GPU host uses plain HTTP, and its token defaults to a published placeholder. | Internet-facing mode requires `https://` and a real token. MUW IT must provide TLS or a WireGuard link to the GPU nodes. | `feature/muw-multicenter-edge-hardening` + MUW IT |
| H4 | Backups: an unencrypted `pg_dump` on the same disk, without the image and file stores, and with no restore test. | Internet-facing mode backs up the file stores too and encrypts with `age`. Off-host copy optional. | `feature/muw-multicenter-edge-hardening` + MUW IT (target) |
| H5 | Containers run as root and are not read-only. Images use floating tags, `:latest` included. | Pin images by digest and run Tomcat as non-root. | Follow-up batch |
| H6 | PostgreSQL 14 reaches end of life in November 2026. | Run the [PostgreSQL 17 runbook](postgresql-17-upgrade.md) on the new VM from the start. | Ops |

### Data protection and integrity

| # | Finding | Fix | Owner |
|---|---|---|---|
| D1 | The app connects as the database superuser, and the fallback password is `clinica`. | Separate roles: an owner role for Liquibase and a runtime role for DML only. The `:-clinica` fallbacks are removed. | Follow-up batch (DBA) + edge branch for the fallbacks |
| D2 | Nothing in the database protects the audit trail: the app user can update or delete audit rows. | Use a trigger or `REVOKE` to make `audit_log_event` append-only. This needs a check first, because heritage code may update audit rows to add a reason for change. | Follow-up batch (DBA) |
| D3 | `subject` stores first name, last name and full date of birth next to the clinical data. Patient detail returns them to every user who can see the enrolment. | **Decision for the DPO:** does the central system need direct identifiers for external sites at all? If not, the multicenter instance does not collect them. | DPO |
| D4 | Disks are not encrypted at rest, according to the repo. | Encrypted VM storage from MUW IT, or LUKS. | MUW IT |
| D5 | `.e2e` files keep the patient name and date of birth in their header. The DICOM pseudonymiser does not cover `.e2e`. | Keep them in the access-restricted store, and never put them in an export. Decide whether `.e2e` headers are scrubbed on ingest. | DPO + follow-up |
| D6 | The DICOM pseudonymiser uses an allow-list of tags and keeps private tags by default. | Switch to the PS3.15 basic profile and drop private tags by default. | Follow-up |
| D7 | Backup RPO is up to 24 h, and there is no WAL archiving. | Define RPO and RTO for the study, then add pgBackRest or wal-g if needed. | Sponsor + ops |

## Questions for people outside the development team

**MUW IT**
1. Does the DMZ reverse proxy terminate TLS, provide a WAF and rate limiting, and pass the client IP? (This decides which nginx header settings are correct.)
2. Which public host name will the study use, and which CA issues its certificate? Can it renew automatically?
3. Can the GPU nodes accept TLS or WireGuard from the new VM, and are they reachable only from inside MUW?
4. Is VM storage encrypted at the hypervisor? Where does the off-host backup go?
5. Is a penetration test required before the DMZ rule is opened? (Recommended.) Who monitors the host and receives alerts out of hours?

**Data protection officer**
1. Has a DPIA (Art. 35) been carried out, and does it accept password-only authentication (no MFA)?
2. Are joint-controller or processor agreements (Art. 26 and 28) in place with every participating center?
3. Should names and dates of birth exist on the central system at all (D3)?
4. What retention applies to identity data, to study data (the EU CTR expects 25 years), and to access logs?

**Department head / sponsor**
1. Do the ethics approval and the protocol describe the AI recommendation, both study arms (`AI_SHOWN` and `AI_HIDDEN`), and the documentation of overrides?
2. Does any center outside MUW run the software as part of patient care rather than as an investigational tool? The Art. 5(5) MDR in-house exemption does not extend to other institutions, so this decides whether the study is a clinical investigation under MDR Art. 62.
3. Who approves a change of threshold, model or weights during the study (change control)?

**Clinical lead (nAMD)**
1. Sign off the thresholds (S4) and the three rule deviations (S3).

## Go-live checklist (on the multicenter VM)

- [ ] All gate rows above are closed or formally accepted.
- [ ] `setup-ubuntu-host.sh` has run with `INTERNET_FACING=true`.
- [ ] The app starts, which proves the default-password guard passes.
- [ ] An external `nmap` scan shows only 80 and 443.
- [ ] `curl` returns 404 for `/LibreClinica/actuator/info`, `/LibreClinica/pages/api/v1/public/…`, `/LibreClinica/pages/swagger-ui/` and `/LibreClinica/RequestPassword`.
- [ ] `Set-Cookie` carries `Secure; HttpOnly; SameSite=Lax`, and HSTS is present.
- [ ] Six wrong passwords lock a test account, and an admin can unlock it.
- [ ] The cross-site IDOR test passes (A10).
- [ ] An encrypted backup has been restored on a scratch VM.
- [ ] The inference adapter is not `placeholder`, and the model manifest is recorded.
