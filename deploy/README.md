# LibreClinicaMUW — production deployment

Runbook for deploying LibreClinicaMUW onto a single Ubuntu 24.04 LTS VM at MUW
Ophthalmology. Everything in this directory is meant to be **re-runnable**; treat
the host like a managed config target, not a hand-crafted server.

## Prerequisites

- Ubuntu 24.04 LTS (Noble Numbat), fully patched, dedicated VM.
- Root or `sudo` access for the operator running this script.
- **Host-level access hardening and network ACLs are out of scope** for this
  script. The production VM is expected to live on the internal MUW network
  behind the institutional reverse proxy / campus firewall; SSH hardening,
  UFW, fail2ban, and similar host-hardening are the campus admin team's
  responsibility and should be applied before you run this script. The
  script focuses only on the LibreClinicaMUW stack — Docker, the deploy
  user, the systemd unit, and the backup timer.
- One or more CIDRs the institutional reverse proxy / campus subnets occupy.
  These feed `LIBRECLINICA_SSO_TRUSTED_CIDRS` (the SSO header-trust filter
  the application uses to decide which incoming request headers it'll trust
  for upstream-provided identity), NOT any host firewall — there isn't one.
  Today's MUW campus CIDRs: ask netops/infrastructure.
- Published release images at both
  `ghcr.io/luviku/libreclinicamuw:<tag>` and
  `ghcr.io/luviku/libreclinicamuw/retinal-inference:<tag>`. One workflow
  builds both in parallel — cut a release via Actions → **Release
  image** → Run workflow → pick `main` or a release tag → Run.
  (Both images share the tag matrix so a single release tag pins
  the whole stack.)
- Outbound HTTPS to `ghcr.io`, `download.docker.com`, `archive.ubuntu.com`,
  `security.ubuntu.com`, `github.com`.
- A **classic GitHub PAT** with the `repo` + `read:packages` scopes. It must be
  a *classic* token — GHCR (ghcr.io) does not accept fine-grained PATs. See
  § "Provisioning the GHCR token" below.

## Provisioning the GHCR token

Both the repo (private) and the GHCR packages (private) require auth, and the
setup script uses **one token for both** — the same token authenticates the
sparse-clone and the `docker login ghcr.io` step the systemd unit relies on for
image pulls.

It must be a **classic** PAT, not a fine-grained one. GitHub's container
registry (ghcr.io) only authenticates classic tokens — a fine-grained PAT has
no permission that works against the container registry, so `docker login
ghcr.io` / image pulls will fail with one. (Ref:
[GitHub Docs — Working with the container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).)
A classic token's `repo` scope also covers the private-repo clone, so a single
classic PAT does both jobs.

**Mint the token** at <https://github.com/settings/tokens/new> (the *classic*
token page — not `/personal-access-tokens/new`):

1. **Note** — `libreclinica-muw-deploy-<host>` (so the rotation log is
   self-documenting).
2. **Expiration** — pick 90 or 365 days; set a calendar reminder for rotation.
3. **Scopes** — tick exactly two:
   - **`repo`** (full control of private repositories) *(authorises the
     sparse-clone; classic tokens have no read-only or per-repo variant)*
   - **`read:packages`** *(authorises `docker pull` from
     `ghcr.io/luviku/libreclinicamuw` + `…/retinal-inference`)*
4. Click *Generate token* and copy the `ghp_…` value once — GitHub won't show
   it again.

> **Least-privilege note:** classic `repo` grants read/write to *all* repos the
> token owner can access — there is no narrower scope that still authorises a
> private-repo clone on a classic token. On a dedicated deploy host this is the
> accepted trade-off; the token lives only in `/etc/libreclinica/env` (mode
> 0640) and `/root/.docker/config.json`. Rotate it on the schedule below.

Pass it to the setup script via `--ghcr-token` *(see § One-shot setup)*. The
script persists it to `/etc/libreclinica/env` (mode 0640) so re-runs and
post-restart pulls don't need it re-passed.

**Rotation** — when the token nears expiry:

```sh
sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh \
  --ghcr-token <new-token>
# Re-run is idempotent; every other knob keeps its current value. The
# script overwrites the LIBRECLINICA_GHCR_TOKEN line in /etc/libreclinica/env
# and re-runs `docker login ghcr.io` so the cached creds at
# /root/.docker/config.json are refreshed.
sudo systemctl restart libreclinica
```

## One-shot setup

On a fresh VM, as root:

```sh
# Pull the deploy tree once via curl. raw.githubusercontent.com requires
# the PAT for a private repo — same token as the one the setup script
# itself will use for the sparse-clone. Mint per § "Provisioning the
# GHCR token" above and stash it in ~/libreclinica-deploy.pat before
# this step.
mkdir -p /root/libreclinica-setup
cd /root/libreclinica-setup
PAT=$(cat ~/libreclinica-deploy.pat)
curl -fsSL \
  -H "Authorization: Bearer ${PAT}" \
  -H "Accept: application/vnd.github.raw" \
  -o setup-ubuntu-host.sh \
  "https://api.github.com/repos/LuViKu/LibreClinicaMUW/contents/deploy/setup-ubuntu-host.sh"

# Inspect the script before running it. This installs Docker, creates a
# system user, drops files under /opt/libreclinica + /etc/libreclinica +
# /var/lib/libreclinica, installs a systemd unit, and wires a nightly
# backup timer. Read the comments at the top before pasting anything
# below.
less setup-ubuntu-host.sh

# Run it.
bash setup-ubuntu-host.sh \
  --image-tag v1.4.0-muw \
  --trusted-cidrs '128.131.0.0/16,10.0.0.0/8' \
  --ghcr-user 'LuViKu' \
  --ghcr-token "$(cat ~/libreclinica-deploy.pat)"
```

The script's summary section at the end lists the manual steps left — read
that, don't skip it.

## What the script does

The script focuses on the application stack only — see the **Prerequisites**
above for the host-hardening scope split.

1. **Preflight** — root + OS check.
2. **System packages + unattended-upgrades** — security updates apply nightly,
   no auto-reboot (reboot windows are operator-scheduled).
3. **Timezone** — `Europe/Vienna` (override with `--timezone`).
4. **Docker Engine + Compose v2** — installs from the official Docker apt
   repo, configures `daemon.json` (log rotation 50 MB × 5, live-restore,
   default address pool moved off 172.17/16 to dodge MUW lab subnet clashes).
5. **Deploy user** — `libreclinica`, system account, no shell, member of
   `docker`. Stack owned by this user.
6. **Stack directory** — **sparse + shallow** clone of the repo to
   `/opt/libreclinica` (only `compose.yaml`, top-level files,
   `deploy/`, and `docker/config/`; the Java/SPA/Python source dirs
   stay on the build host where they belong — a few MB instead of
   ~150 MB on disk). Seeds `/opt/libreclinica/config/` from
   `docker/config/`. Creates
   `/var/lib/libreclinica/{postgres,e2e-uploads,retinal-inference}` and
   `/var/backups/libreclinica/`. The sparse-checkout pattern is
   re-asserted on every re-run, so an older full clone gets trimmed
   on the next setup pass.
7. **Env file** — `/etc/libreclinica/env`. On first run it generates a 32-char
   Postgres password; on re-run it preserves the existing secret and only
   updates the image-tag pin.
8. **systemd unit** — `libreclinica.service`. Uses
   `compose.yaml` + `deploy/compose.production.yaml`. Both `libreclinica`
   and `retinal-inference` images are pulled from ghcr.io on every start
   (`pull_policy: always` on both services), so the VM never builds —
   it just pulls and runs. The sidecar tag defaults to the app tag so
   one `LIBRECLINICA_IMAGE_TAG` roll moves both; pin the sidecar
   independently by setting `LIBRECLINICA_RETINAL_IMAGE_TAG` in
   `/etc/libreclinica/env`. The `smtp` mailcrab dev service is
   intentionally not started.
9. **Backups** — `libreclinica-backup-db.timer` fires nightly at 03:00 local
   (with 30 min jitter). Output goes to `/var/backups/libreclinica/`,
   gzipped, retention 30 days (`--backup-days`).
10. **logrotate** — safety net for the backup directory.

## After the script

### 1. Mail is pre-configured for the MUW internal relay

The setup script **sets the SMTP config in `datainfo.properties` if it hasn't
been configured yet** — i.e. while `mailHost` is still the repo's `smtp` dev
placeholder (which points at the absent mailcrab container and otherwise 500s
the first-login root password change). Once `mailHost` is set, re-runs leave
the mail config alone, so hand edits survive. The first-run defaults target the
MUW **internal** outgoing relay, which needs no auth:

| Knob | Default | datainfo key |
|------|---------|--------------|
| `LIBRECLINICA_MAIL_HOST` | `smtpi.meduniwien.ac.at` | `mailHost` |
| `LIBRECLINICA_MAIL_PORT` | `25` | `mailPort` |
| `LIBRECLINICA_MAIL_SMTP_AUTH` | `false` | `mailSmtpAuth` |
| `LIBRECLINICA_MAIL_STARTTLS` | `false` | `mailSmtpStarttls.enable` |
| `LIBRECLINICA_MAIL_CONNECTION_TIMEOUT` | `10000` (ms) | `mailSmtpConnectionTimeout` |
| `LIBRECLINICA_ADMIN_EMAIL` | *(unset → keeps seeded value)* | `adminEmail` |

For an **authenticated/external** relay (e.g. `smtpa.meduniwien.ac.at:587` from
outside the MUW net), set the knobs via env before running setup:

```sh
LIBRECLINICA_MAIL_HOST=smtpa.meduniwien.ac.at \
LIBRECLINICA_MAIL_PORT=587 \
LIBRECLINICA_MAIL_SMTP_AUTH=true \
LIBRECLINICA_MAIL_STARTTLS=true \
LIBRECLINICA_MAIL_USERNAME=<MUWUserID> \
LIBRECLINICA_MAIL_PASSWORD=<password> \
LIBRECLINICA_ADMIN_EMAIL=<real MUW sender> \
  sudo -E bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --ghcr-token <token> …
```

> These are **first-run defaults**, not re-stamped values: the script only
> writes them while `mailHost` is still the `smtp` placeholder. After that,
> edit the mail keys directly in `datainfo.properties` (hand edits are
> preserved across re-runs) — or, to re-apply the `LIBRECLINICA_MAIL_*` env
> defaults, reset `mailHost=smtp` and re-run setup. Set `LIBRECLINICA_ADMIN_EMAIL`
> (or edit `adminEmail`) to a real MUW address; the seeded `admin@example.com`
> may be dropped as a bogus sender.

Restart the stack after any config change:

```sh
sudo systemctl restart libreclinica
```

> **Re-running setup:** always use **`/opt/libreclinica/deploy/setup-ubuntu-host.sh`**
> (kept current by `git reset --hard` on each run), *not* the
> `/root/libreclinica-setup/` bootstrap copy you `curl`'d for the first run.
> The bootstrap copy is frozen at first-run and will silently skip later fixes
> (dbPass sync, bind address, mail config) even though it still pulls fresh
> compose files into `/opt/libreclinica`.

### 2. Wire the institutional reverse proxy

The VM exposes Tomcat on `127.0.0.1:8080` only. The MUW reverse proxy is
expected to terminate TLS, do Shibboleth SP auth, and forward to
`http://<vm-internal-ip>:8080/LibreClinica/`. Network-level access control
(which sources can reach port 8080) is handled at the institutional
perimeter, not on the VM itself — see the **Prerequisites** scope split.

When the reverse proxy is in place and forwarding the SSO headers, flip
`LIBRECLINICA_SSO_ENABLED=true` in `/etc/libreclinica/env` and restart.

### 3. Start the stack

```sh
sudo systemctl start libreclinica
sudo journalctl -u libreclinica -f                          # boot orchestration
sudo docker logs -f libreclinica-muw-libreclinica-1         # Tomcat / app log
```

The first start pulls the image and runs Liquibase to bootstrap the schema —
budget ~3–5 minutes before the app responds.

### 4. Smoke

From a host on a trusted CIDR:

```sh
curl -I http://<vm-ip>:8080/LibreClinica/
# expect: 302 → /pages/login/login
curl -I http://<vm-ip>:8080/LibreClinica/pages/login/login
# expect: 200
```

> The published bind comes from `LIBRECLINICA_BIND_ADDR` (set to `0.0.0.0` by
> setup; narrow to the VM internal IP to restrict it). `docker port
> libreclinica-muw-libreclinica-1` should show exactly **one** mapping — if it
> shows two, or none, the bind got double-defined (do not add a `ports:` entry
> in the overlay; Compose concatenates them).

> **Known issue — container shows `(unhealthy)`:** the image's healthcheck
> probes `/LibreClinica/actuator/health`, which currently returns 404 (Spring
> Boot Actuator's web endpoints aren't exposed in the WAR deployment). This is
> **cosmetic** — the app serves normally (302 on `/LibreClinica/`). Fixing it
> properly means exposing the actuator health endpoint (app config) or
> overriding the healthcheck; tracked separately, not a blocker for go-live.

## Day-2 operations

### Upgrade to a new image

1. Cut a new release on GitHub (Releases → Draft a new release → publish).
   The `Release image` workflow fires and pushes BOTH:
   - `ghcr.io/luviku/libreclinicamuw:<release-tag>` (+ `latest`)
   - `ghcr.io/luviku/libreclinicamuw/retinal-inference:<release-tag>` (+ `latest`)
2. On the VM:
   ```sh
   sudo sed -i 's|^LIBRECLINICA_IMAGE_TAG=.*|LIBRECLINICA_IMAGE_TAG=<new-tag>|' /etc/libreclinica/env
   sudo systemctl restart libreclinica
   ```
   `pull_policy: always` on both services in the production overlay handles
   the actual pulls. Both images roll together unless
   `LIBRECLINICA_RETINAL_IMAGE_TAG` is also set in the env file (it pins
   the sidecar independently of the app).

### Rebuild a single image without cutting a release

For ad-hoc rebuilds (e.g. dep CVE refresh on the sidecar, no app change):

1. Actions → **Release image** → Run workflow
2. Pick `Use workflow from: main` (or a release tag)
3. Set `images: app` or `images: retinal-inference` (default `both`)
4. Optionally fill `version: <raw-tag>` to add a custom tag alongside `latest` + `sha-<short>`

### Restore from backup

```sh
# 1. Pick a dump.
ls -lh /var/backups/libreclinica/

# 2. Stop the app (Postgres stays up so we can restore into it).
sudo docker compose -f /opt/libreclinica/compose.yaml \
    -f /opt/libreclinica/deploy/compose.production.yaml \
    stop libreclinica retinal-inference

# 3. Wipe + restore the db.
sudo docker exec -i libreclinica-muw-db-1 \
    psql -U clinica -d postgres -c 'DROP DATABASE IF EXISTS libreclinica;'
sudo docker exec -i libreclinica-muw-db-1 \
    psql -U clinica -d postgres -c 'CREATE DATABASE libreclinica;'
gunzip -c /var/backups/libreclinica/libreclinica-<stamp>.sql.gz \
    | sudo docker exec -i libreclinica-muw-db-1 psql -U clinica -d libreclinica

# 4. Back up.
sudo systemctl start libreclinica
```

### Read-only DB shell

```sh
sudo docker exec -it libreclinica-muw-db-1 psql -U clinica libreclinica
```

### Tail the retinal-inference sidecar

```sh
sudo docker logs -f libreclinica-muw-retinal-inference-1
```

## FAQ

**Why containerised Postgres instead of host Postgres?**
Operational simplicity. The compose stack is the unit of deploy — one
`systemctl start` brings up app + DB + sidecar atomically, and the data
volume lives at `/var/lib/libreclinica/postgres` on the host disk so the
backup script can `docker exec pg_dump` without any host-Postgres setup.
If MUW DBAs want host Postgres later, swap the `db` service's
`volumes:` entry for an `external: true` network and point the
application at `host.docker.internal`.

**Why no nginx/TLS on this host?**
The MUW institutional reverse proxy is expected to terminate TLS, do
Shibboleth SP auth, and forward to this VM. Adding a local TLS stack
would just add a second cert to rotate. For staging/dev VMs not behind
the proxy, run a separate nginx on port 443 → 127.0.0.1:8080 (out of
scope for this script).

**Why is mailcrab gone?**
It's a dev SMTP catcher. Production sends through the institutional MUW
relay, which the setup script bakes into `datainfo.properties` by default
(internal relay `smtpi.meduniwien.ac.at:25`, no auth) — see § "After the
script" → "Mail is pre-configured" to override for an authenticated/external
relay.

**What happens if I re-run setup-ubuntu-host.sh?**
Every block is idempotent. The env file's Postgres secret is preserved
across re-runs — only the image tag pin gets refreshed. Re-running is the
canonical path for bumping `--image-tag` or refreshing `--trusted-cidrs`
(which feeds `LIBRECLINICA_SSO_TRUSTED_CIDRS`).
