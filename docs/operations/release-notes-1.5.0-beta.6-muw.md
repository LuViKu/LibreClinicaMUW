# LibreClinica MUW · 1.5.0-beta.6-muw release notes

_Successor to **1.5.0-beta.5-muw** (tag `1.5.0-beta.5-muw`). This window: the **Java 21 → 25 LTS** platform bump, the **nAMD two-arm treat-and-extend** workflow (cohort gate + treatment-recommendation engine + clinical-flags + hemorrhage entry), **subject randomization at enrollment**, subject-matrix fixes, and the institutional **TLS reverse proxy with clean root URLs**._

For older releases see [release-notes-1.5.0-beta.5-muw.md](release-notes-1.5.0-beta.5-muw.md), [release-notes-1.5.0-beta.4-muw.md](release-notes-1.5.0-beta.4-muw.md), [release-notes-1.5.0-beta.3-muw.md](release-notes-1.5.0-beta.3-muw.md), [release-notes-1.5.0-beta.2-muw.md](release-notes-1.5.0-beta.2-muw.md), and [release-notes-1.5.0-beta.1-muw.md](release-notes-1.5.0-beta.1-muw.md).

## ⚠️ Breaking — deployment now requires the nginx reverse proxy

This release changes how the app is served. The SPA is now built at the **site root** (`vite base '/'`, `createWebHistory('/')`) so users get clean URLs (`https://ecrf.augen.meduniwien.ac.at/login` instead of `…/LibreClinica/app/login`). As a consequence, **the app is only reachable through the bundled nginx reverse proxy** — a direct `:8080/LibreClinica/app` load can no longer resolve the root-based asset URLs.

**When deploying beta.6 you MUST also bring up nginx** (it ships as a compose sidecar) with a valid TLS cert in place. Do not deploy the app image without the proxy. Full runbook + testing checklist: [deploy/nginx/README.md](../../deploy/nginx/README.md).

## Highlights

The dominant themes are the **Java 25 LTS** platform bump (build + runtime) and the **nAMD treat-and-extend** clinical workflow reaching end-to-end (cohort gate → clinical flags → treatment recommendation → decision capture), plus randomization at enrollment. Alongside, the institutional HTTPS front door lands: TLS termination + clean URLs behind an in-compose nginx proxy.

### Java 21 → 25 LTS

- **Build + runtime moved to Java 25** ([Dockerfile](../../Dockerfile)): builder `maven:3-eclipse-temurin-25`, runtime `tomcat:10.1-jdk25-temurin`. The legacy Spring/Hibernate/Castor/LDAP `--add-opens`/`--add-exports` stopgaps carry forward unchanged.
- **Build fixes for the new toolchain**: `liquibase-core` re-pinned to `3.6.3` (Liquibase 4 breaks the heritage changelog checksum validation), and the dependency-plugin ASM overridden to `9.10.1` for JDK 25 bytecode. Compile + full test suite green (692/692) on the upgrade branch.
- MIGRATION.md / modernization status refreshed for the Java 25 baseline.

### nAMD treat-and-extend workflow

- **Two-arm cohort gate + treatment-recommendation engine + decision capture** (#293-derived). The nAMD workspace gates subjects into the two study arms and drives a per-visit treatment recommendation with an explicit operator decision-capture step.
- **Clinical-flags entry card on the Overview tab** — captures the per-visit clinical flags; **auto-creates the `event_crf` row on save** so operators don't pre-provision the CRF, and treats `eventCrfId=0` as "no CRF row yet".
- **Prior-interval derivation from the visit week gap** — the treat-and-extend interval is derived from the actual spacing between visits.
- **Hemorrhage entry** (#295) and assorted backend + SPA follow-ups from live user testing.

### Subject randomization

- **Randomization at enrollment (v1 + v2a)** (#294) — subjects are randomized into arms at enrollment. Backed by the new `subject-randomization` migration.

### Subject matrix + subjects fixes

- **Always reload subjects on mount** (#296) — the matrix no longer shows stale rows from a previous study/session.
- **`Öffnen` action aligned** across rows with varying visit counts.
- **Case-insensitive study OID lookup** — resolving a study by OID no longer fails on case differences.

### TLS reverse proxy + clean root URLs

- **In-compose nginx sidecar** ([deploy/nginx/ecrf.conf](../../deploy/nginx/ecrf.conf), [deploy/compose.production.yaml](../../deploy/compose.production.yaml)) terminates TLS for `ecrf.augen.meduniwien.ac.at` (+ the host FQDN) and serves the SPA at the site root: `/assets/` and the SPA shell map to the WAR's `/app` base, `/LibreClinica/*` (REST API, legacy JSP, actuator) passes through, and old `/LibreClinica/app/*` bookmarks 301 to the clean path. Handles the 200 MB OCT upload limit, unbuffered SSE for the live job-status stream, forwarded headers, and the session-cookie path rewrite.
- **Tomcat `RemoteIpValve`** added to the image so `X-Forwarded-Proto` is honored → `https://` URLs + a Secure session cookie behind the terminator.
- **Host wiring**: `setup-ubuntu-host.sh` adds nginx to the systemd `ExecStart` and stamps `sysURL` to the public HTTPS URL; plain 8080 is narrowed to loopback on deploy. A [cert-expiry monitor](../../deploy/nginx/cert-expiry-check.sh) guards the manually-renewed cert.

## Migrations

Two new Liquibase changesets run on boot (back up the DB before deploy):

- `lc-muw-2026-06-30-namd-clinical-flags.xml`
- `lc-muw-2026-07-02-subject-randomization.xml`

## Beta-testing checklist

### Smoke

- [ ] `ghcr.io/luviku/libreclinicamuw:1.5.0-beta.6-muw` pulls cleanly + Tomcat reaches healthy (Java 25 runtime).
- [ ] `ghcr.io/luviku/libreclinicamuw/retinal-inference:1.5.0-beta.6-muw` ditto.
- [ ] nginx sidecar starts, `nginx -t` clean, cert served for `ecrf.augen.meduniwien.ac.at`.
- [ ] `LoginView` footer renders `v1.5.0-beta.6-muw · Build <yyyy-MM-dd> · <7-char-sha>`.

### TLS + clean URLs (see deploy/nginx/README.md for the full list)

- [ ] `https://ecrf.augen.meduniwien.ac.at/login` renders the SPA; the **address bar stays `/login`**; DevTools shows `/assets/*` = 200.
- [ ] Login with an internal account → clean URLs throughout; JSESSIONID cookie has the **Secure** flag.
- [ ] Old bookmark `…/LibreClinica/app/login` → 301 → `/login`; legacy `…/LibreClinica/MainMenu` still works.
- [ ] Large `.e2e` OCT upload succeeds (no 413); an in-flight OCT job live-updates SLO/OCT/segmentation without a manual refresh.
- [ ] Plain `:8080` is not reachable from another MUW host.

### nAMD + randomization

- [ ] Enroll a subject → randomization assigns an arm; the arm gate routes the nAMD workspace correctly.
- [ ] Overview tab → clinical-flags card saves; the `event_crf` row is auto-created (no pre-provisioning).
- [ ] Treatment recommendation reflects the derived prior interval; the decision-capture step records the operator's choice.
- [ ] Subject matrix shows current rows on mount (no stale study data); `Öffnen` aligns across rows.

## Known issues

Carried from beta.5 — see [release-notes-1.5.0-beta.5-muw.md § Known issues](release-notes-1.5.0-beta.5-muw.md#known-issues). New this release: the clean-URL cutover means the app is unreachable if nginx isn't running or the cert is missing/mismatched — verify the proxy is healthy before announcing the new URL to users.
