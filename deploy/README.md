# LibreClinicaMUW — production deployment

Runbook for deploying LibreClinicaMUW onto a single Ubuntu 24.04 LTS VM at MUW
Ophthalmology. Everything in this directory is meant to be **re-runnable**; treat
the host like a managed config target, not a hand-crafted server.

## Prerequisites

- Ubuntu 24.04 LTS (Noble Numbat), fully patched, dedicated VM.
- Root SSH access (initially via password or an existing key — the setup script
  will harden SSH to key-only).
- An SSH public key for the operator/admin account that will keep working after
  hardening.
- One or more CIDRs the institutional reverse proxy lives on (so UFW can scope
  port 8080 to just those sources). Today's MUW campus CIDRs: ask
  netops/infrastructure.
- A published release image at `ghcr.io/luviku/libreclinicamuw:<tag>`. Cut one
  via the **Release image** workflow on GitHub:
  Actions → Release image → Run workflow → pick `main` or a release tag → Run.
- Outbound HTTPS to `ghcr.io`, `download.docker.com`, `archive.ubuntu.com`,
  `security.ubuntu.com`, `github.com`.

## One-shot setup

On a fresh VM, as root:

```sh
# Pull the deploy tree once via curl (the setup script itself will then clone
# /opt/libreclinica from the same repo so updates are git-managed).
mkdir -p /root/libreclinica-setup
cd /root/libreclinica-setup
curl -fsSLO https://raw.githubusercontent.com/LuViKu/LibreClinicaMUW/main/deploy/setup-ubuntu-host.sh

# Inspect the script before running it. This installs Docker, hardens SSH,
# opens a firewall port, and creates a system user. Read the comments at the
# top before pasting anything below.
less setup-ubuntu-host.sh

# Run it.
bash setup-ubuntu-host.sh \
  --image-tag v1.4.0-muw \
  --trusted-cidrs '128.131.0.0/16,10.0.0.0/8' \
  --admin-ssh-key "$(cat ~/admin-key.pub)"
```

The script's summary section at the end lists the manual steps left — read
that, don't skip it.

## What the script does

1. **Preflight** — root, OS version, network sanity.
2. **System packages + unattended-upgrades** — security updates apply nightly,
   no auto-reboot (reboot windows are operator-scheduled).
3. **Timezone** — `Europe/Vienna` (override with `--timezone`).
4. **SSH hardening** — drop-in at `/etc/ssh/sshd_config.d/10-libreclinica.conf`.
   Key-only, no root password, no agent/X11/TCP forwarding, 3 auth tries.
5. **UFW firewall** — deny incoming by default. Open `22/tcp` (rate-limited)
   and `8080/tcp` only to `--trusted-cidrs`. Refuses to leave a wide-open
   `8080/0.0.0.0` unless you didn't pass any CIDRs at all.
6. **fail2ban** — sshd jail, 5 retries in 10 min → 1 h ban.
7. **Docker Engine + Compose v2** — installs from the official Docker apt
   repo, configures `daemon.json` (log rotation 50 MB × 5, live-restore,
   default address pool moved off 172.17/16 to dodge MUW lab subnet clashes).
8. **Deploy user** — `libreclinica`, system account, no shell, member of
   `docker`. Stack owned by this user.
9. **Stack directory** — clones the repo to `/opt/libreclinica`, seeds
   `/opt/libreclinica/config/` from `docker/config/`, creates
   `/var/lib/libreclinica/{postgres,e2e-uploads,retinal-inference}` and
   `/var/backups/libreclinica/`.
10. **Env file** — `/etc/libreclinica/env`. On first run it generates a 32-char
    Postgres password; on re-run it preserves the existing secret and only
    updates the image-tag pin.
11. **systemd unit** — `libreclinica.service`. Uses
    `compose.yaml` + `deploy/compose.production.yaml`. Pulls the pinned
    `libreclinica` image on every start (via `pull_policy: always`), brings
    up `libreclinica`, `db`, and `retinal-inference`. The `retinal-inference`
    sidecar builds locally from `retinal-inference/Dockerfile` on first
    start (placeholder adapter; ~2 min build, ~0 sec after that). The
    `smtp` mailcrab dev service is intentionally not started.
12. **Backups** — `libreclinica-backup-db.timer` fires nightly at 03:00 local
    (with 30 min jitter). Output goes to `/var/backups/libreclinica/`,
    gzipped, retention 30 days (`--backup-days`).
13. **logrotate** — safety net for the backup directory.

## After the script

### 1. Fill in `datainfo.properties`

The dev defaults point `mailHost=smtp` at the (now-absent) mailcrab service.
Production must point at the institutional MUW SMTP relay:

```sh
sudo -u libreclinica vim /opt/libreclinica/config/datainfo.properties
# mailHost=relay.meduniwien.ac.at        # confirm with MUW netops
# mailPort=587
# mailUsername=<institutional service account>
# mailPassword=<institutional service account password>
# mailSmtpAuth=true
# mailSmtpStarttls.enable=true
```

Any other production-only overrides (SSO entry URL, study-specific
parameters, debug log levels) belong in this file too. Restart the stack
after edits:

```sh
sudo systemctl restart libreclinica
```

### 2. Wire the institutional reverse proxy

The VM exposes Tomcat on `127.0.0.1:8080` only. The MUW reverse proxy is
expected to terminate TLS, do Shibboleth SP auth, and forward to
`http://<vm-internal-ip>:8080/LibreClinica/`. UFW already allows the
trusted CIDRs you set during setup.

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

## Day-2 operations

### Upgrade to a new image

1. Cut a new release on GitHub (Releases → Draft a new release → publish).
   The `Release image` workflow fires and pushes
   `ghcr.io/luviku/libreclinicamuw:<release-tag>` + `latest`.
2. On the VM:
   ```sh
   sudo sed -i 's|^LIBRECLINICA_IMAGE_TAG=.*|LIBRECLINICA_IMAGE_TAG=<new-tag>|' /etc/libreclinica/env
   sudo systemctl restart libreclinica
   ```
   `pull_policy: always` in the production overlay handles the actual pull.

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
relay; configure it in `/opt/libreclinica/config/datainfo.properties`.

**What happens if I re-run setup-ubuntu-host.sh?**
Every block is idempotent. UFW gets fully reset and re-applied (so adding
new trusted CIDRs is a re-run). The env file's Postgres secret is
preserved across re-runs — only the image tag pin gets refreshed.
