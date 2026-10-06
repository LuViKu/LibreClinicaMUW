# eCRF TLS reverse proxy + clean URLs

nginx sidecar that terminates TLS for `ecrf.augen.meduniwien.ac.at` and serves
the SPA at clean root URLs (`/login`, `/subjects/…`) instead of
`/LibreClinica/app/login`.

## How it works

- **SPA** is built with Vite `base: '/'` + `createWebHistory('/')`, so the
  router matches clean root paths and keeps them in the address bar.
- **nginx** ([ecrf.conf](ecrf.conf)) maps:
  - `/assets/…` → `…/LibreClinica/app/assets/…` (the WAR still serves the
    hashed bundles under `/app`),
  - `/LibreClinica/…` → passthrough (REST API, legacy JSP, actuator, MainMenu),
  - everything else → `…/LibreClinica/app/…` (SpaForwardingConfig returns
    `index.html`, so deep links like `/subjects/EIAMD150` boot the SPA),
  - old `/LibreClinica/app/…` bookmarks → `301` to the clean path.
- **Tomcat** honors `X-Forwarded-Proto` via a `RemoteIpValve` (added in the
  [Dockerfile](../../Dockerfile)) → `https://` URLs + a Secure session cookie.

> **The app is now reachable only through nginx.** A direct
> `:8080/LibreClinica/app` load can't resolve the root-based `/assets/` URLs.
> That's intended — plain 8080 is narrowed to loopback (below).

## Prerequisites (from IT)

1. Internal DNS: `ecrf.augen.meduniwien.ac.at` → the VM (CNAME to
   `vrc-lin-tasks.augen.meduniwien.ac.at`), MUW-network-only.
2. A SAN cert covering **both** names — see the CSR you generated at
   `/etc/libreclinica/tls/ecrf-augen.csr`.
3. Port **443** open to the VM from the MUW network.

## Cert placement

Drop the signed cert + key in the root-only TLS dir (mounted read-only into the
sidecar):

```sh
sudo install -m 700 -d /etc/libreclinica/tls
# key was generated on the VM and stays here; add the signed cert (full chain:
# server cert + intermediates, in that order):
sudo cp <signed-fullchain>.pem /etc/libreclinica/tls/ecrf-augen.crt
sudo chmod 600 /etc/libreclinica/tls/ecrf-augen.key /etc/libreclinica/tls/ecrf-augen.crt
```

If IT returns the chain as a separate intermediate file, concatenate
`server.crt` + `intermediate.crt` into `ecrf-augen.crt` (server first).

## Deploy

The clean-URL change is in the **image** (SPA base + valve) *and* the host
(nginx). So you need the new image tag deployed AND nginx started.

```sh
# 1. Narrow plain 8080 to loopback so users must use HTTPS (nginx reaches the
#    app over the compose network regardless).
sudo sed -i 's|^LIBRECLINICA_BIND_ADDR=.*|LIBRECLINICA_BIND_ADDR=127.0.0.1|' /etc/libreclinica/env

# 2. Point at the image tag that contains this branch's SPA-base + valve change.
sudo sed -i 's|^LIBRECLINICA_IMAGE_TAG=.*|LIBRECLINICA_IMAGE_TAG=<tag>|' /etc/libreclinica/env

# 3. Set the public URL (or let a setup re-run stamp it).
sudo sed -i 's|^sysURL=.*|sysURL=https://ecrf.augen.meduniwien.ac.at/LibreClinica/MainMenu|' /opt/libreclinica/config/datainfo.properties

# 4. Make systemd start nginx too. Either re-run setup-ubuntu-host.sh (its
#    ExecStart now lists nginx) OR patch the live unit:
sudo sed -i 's| retinal-inference$| retinal-inference nginx|' /etc/systemd/system/libreclinica.service
sudo systemctl daemon-reload

# 5. Validate the nginx config, then restart the stack.
sudo docker run --rm --add-host libreclinica:127.0.0.1 \
     -v /opt/libreclinica/deploy/nginx/ecrf.conf:/etc/nginx/conf.d/default.conf:ro \
     -v /opt/libreclinica/deploy/nginx/edge-none.conf:/etc/nginx/ecrf-edge.conf:ro \
     -v /opt/libreclinica/deploy/nginx/empty.conf:/etc/nginx/ecrf-realip.conf:ro \
     -v /opt/libreclinica/deploy/nginx/empty.conf:/etc/nginx/ecrf-edge-http.conf:ro \
     -v /etc/libreclinica/tls:/etc/libreclinica/tls:ro nginx:1.27-alpine nginx -t
sudo systemctl restart libreclinica
```

## Testing checklist (verify each before calling it done)

- [ ] `sudo docker exec libreclinica-muw-nginx-1 nginx -t` → syntax OK.
- [ ] `curl -I http://ecrf.augen.meduniwien.ac.at/` → `301` to `https://`.
- [ ] `curl -Ik https://ecrf.augen.meduniwien.ac.at/login` → `200`, valid cert
      (drop `-k` once the chain is trusted).
- [ ] Browser → `https://ecrf.augen.meduniwien.ac.at/login`: the SPA login
      renders, **address bar stays `/login`**, DevTools Network shows
      `/assets/*.js` = `200` (no 404s).
- [ ] Log in with an internal account → lands on home, `/subjects` etc. stay
      clean in the address bar.
- [ ] `GET /LibreClinica/pages/api/v1/me` returns `200` when logged in (auth
      cookie rides the clean URLs).
- [ ] JSESSIONID cookie has the **Secure** flag (DevTools → Application →
      Cookies) — confirms RemoteIpValve sees https.
- [ ] Old bookmark: `https://ecrf.augen.meduniwien.ac.at/LibreClinica/app/login`
      → `301` → `/login`.
- [ ] Legacy JSP still works: `https://ecrf.augen.meduniwien.ac.at/LibreClinica/MainMenu`.
- [ ] **OCT upload** of a large `.e2e` succeeds (no `413` — `client_max_body_size`).
- [ ] **Live updates**: open an in-flight OCT job → status/SLO/segmentation
      update without a manual refresh (SSE passes through unbuffered).
- [ ] Public portals: `https://ecrf.augen.meduniwien.ac.at/bcva-entry/<studyOid>`
      and `/oct-upload` load.
- [ ] Plain 8080 is not reachable from another MUW host:
      `curl -m5 http://vrc-lin-tasks.augen.meduniwien.ac.at:8080/` fails.

## Rollback

The change spans the image + nginx, so rollback = previous image + no proxy:

```sh
sudo systemctl stop libreclinica
sudo docker stop libreclinica-muw-nginx-1 2>/dev/null || true
sudo sed -i 's| retinal-inference nginx$| retinal-inference|' /etc/systemd/system/libreclinica.service
sudo sed -i 's|^LIBRECLINICA_BIND_ADDR=.*|LIBRECLINICA_BIND_ADDR=0.0.0.0|' /etc/libreclinica/env
sudo sed -i 's|^LIBRECLINICA_IMAGE_TAG=.*|LIBRECLINICA_IMAGE_TAG=<previous-tag>|' /etc/libreclinica/env
sudo systemctl daemon-reload && sudo systemctl start libreclinica
```
That restores the previous `…/LibreClinica/app/…` URLs on plain 8080.

## Cert renewal

The MUW-CA cert is renewed manually. Wire [cert-expiry-check.sh](cert-expiry-check.sh)
to a daily cron so it can't lapse silently:

```sh
0 8 * * *  /opt/libreclinica/deploy/nginx/cert-expiry-check.sh \
             || echo "eCRF TLS cert needs renewal" | mail -s "eCRF cert" you@meduniwien.ac.at
```

After installing a renewed cert, reload nginx without downtime:
```sh
sudo docker exec libreclinica-muw-nginx-1 nginx -s reload
```

## Edge hardening (2026-10)

Applies to both deployments (it is in the shared `ecrf.conf`):

- `server_tokens off`; Mozilla "intermediate" TLS (TLS 1.2 + 1.3, AEAD
  ciphers, `ssl_prefer_server_ciphers off`, `ssl_session_tickets off`). A
  client that only speaks CBC suites or TLS 1.0/1.1 will no longer connect.
- **HSTS** `max-age=31536000` is **on**: port 80 only redirects, so both
  deployments are already HTTPS-only. Browsers remember it for a year; if
  HTTPS ever has to be taken away from the internal name, that is a long wait.
  `includeSubDomains` is added only on the internet-facing host.
- `Referrer-Policy: same-origin`, `X-Content-Type-Options: nosniff`,
  `Content-Security-Policy: frame-ancestors 'none'; base-uri 'self'; object-src 'none'`.
  There is deliberately no `script-src`: the heritage JSPs use inline scripts.
- `X-Forwarded-For` is **set** to `$remote_addr` (nginx is the first hop), and
  `REMOTE_USER`, `mail` and `displayName` (the SSO identity headers) are blanked
  on every proxied request. `Connection ""` is set at server level so the SSE
  location inherits all of it (a location with its own `proxy_set_header`
  drops the server-level ones).
- **Per-mode limits** (only ONE file sets them, the server-level include
  `ecrf-edge.conf`): the internal deployment (`edge-none.conf`) keeps the old
  server-level `client_max_body_size 1024m`, nginx's default timeouts and no
  rate limiting; the internet-facing one (`internet-facing.conf`) sets 2 MB, the
  timeouts and the rate limits below. The per-location limits in this table are
  in `ecrf.conf` and apply in both modes (200m on the app-capped routes is the
  app's own `/pages/*` multipart cap, so nothing larger worked before either).
- Request bodies, internet-facing: **2 MB** by default. The large limit is granted only here:

  | Location | Limit |
  |---|---|
  | `/LibreClinica/pages/api/v1/public/` (portals; 404 on internet-facing) | 1024m |
  | `/LibreClinica/pages/api/v1/ingest/upload/` (SPA `/ingest-inbox/upload`) | 1024m |
  | `/LibreClinica/pages/api/v1/event-crfs/{id}/oct-upload` | 1024m |
  | `/LibreClinica/pages/api/v1/eventCrfs/{id}/items/{oid}/file` (CRF file item) | 200m |
  | `/LibreClinica/pages/api/v1/crfs/{oid}/versions`, `…/import`, `…/rules/import` | 200m |
  | `/LibreClinica/{CreateCRFVersion,CreateXformCRFVersion,ImportCRFData,ImportRule,UploadFile}` (legacy multipart JSPs) | 200m |

  The app itself caps the `/pages/*` multipart parser at 200 MiB (`web.xml`).
  The Optomed device endpoints and uploader heartbeats are tiny and keep the
  2 MB default. **A new upload route needs a line in this table and in
  `ecrf.conf`, or it answers 413.** The `:8088` retinal failover listener keeps
  its own 1024m.
- Rate limits, **internet-facing only** (per client address; `429` when exceeded): login
  (`/LibreClinica/j_spring_security_check`) 20/min burst 10; `RequestAccount`,
  `Contact`, `/pages/api/v1/contact` 6/min burst 5; everything else 50 r/s burst
  200; 100 concurrent requests per address. `client_header_timeout 15s`,
  `client_body_timeout 60s`, `send_timeout 60s`.
- Per-deployment includes (compose variables; defaults are the internal
  deployment's, the setup script's internet-facing mode sets the others):
  `LIBRECLINICA_NGINX_EDGE_CONF` (server level; default `edge-none.conf`, internet:
  `internet-facing.conf` = limits above plus 404 for actuator, Swagger, public,
  internal and device APIs, `pages/auth`, the clean twins, the public portal
  pages and `;param` path tricks), `LIBRECLINICA_NGINX_EDGE_HTTP_CONF` (http level;
  default `empty.conf`, internet: `internet-facing-http.conf` = `default_server`
  catch-alls that close unknown `Host` on :80 with 444 and refuse unknown SNI on
  :443 via `ssl_reject_handshake`) and `LIBRECLINICA_NGINX_REALIP_CONF` (default
  `empty.conf`; generated `set_real_ip_from` lines for the DMZ proxy). See
  [../README.md](../README.md#internet-facing-multicenter-deployment).
- **Who breaks with AEAD-only TLS (both deployments).** Only
  ECDHE/DHE + AES-GCM/ChaCha20 over TLS 1.2/1.3 is offered (the old list was
  `HIGH:!aNULL:!MD5`, which also allowed CBC suites and static RSA key exchange).
  Checked in this repo: `deploy/optomed/OptomedBridge.ps1` and
  `deploy/export-watcher/ExportWatcher.ps1` are Windows PowerShell + .NET
  `HttpClient` and pin `SecurityProtocol = Tls12`, so they use the OS's Schannel:
  fine on Windows 10/11 and Server 2016+ (and 8.1/2012 R2), which offer
  ECDHE-RSA-AES-GCM; **not fine on Windows 7 / Server 2008 R2 / 2012 (non-R2)**,
  which have no AES-GCM suites. Verify on each acquisition PC with
  `Invoke-WebRequest https://<host>/login -UseBasicParsing`
  (or open the site in its browser) after the change. The Remidio probe
  (`deploy/remidio/remidio-probe.sh`) and the app's Remidio client only make
  outbound calls to Remidio's cloud and are not affected. Other possible
  victims: Java 7 or older, Python 2 / OpenSSL < 1.0.1, embedded devices and
  old Android (< 5) browsers. The server certificate must be RSA or ECDSA
  (both suites are listed).

Validate a change without touching the running stack (a dummy cert is enough;
the app hostname must resolve, hence `--add-host`):

```sh
docker run --rm --add-host libreclinica:127.0.0.1 \
  -v "$PWD/deploy/nginx/ecrf.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "$PWD/deploy/nginx/internet-facing.conf:/etc/nginx/ecrf-edge.conf:ro" \
  -v "$PWD/deploy/nginx/empty.conf:/etc/nginx/ecrf-realip.conf:ro" \
  -v "$PWD/deploy/nginx/internet-facing-http.conf:/etc/nginx/ecrf-edge-http.conf:ro" \
  -v /etc/libreclinica/tls:/etc/libreclinica/tls:ro nginx:1.27-alpine nginx -t
```

## Notes

- HSTS is enabled (see above). If clean-URL routing misbehaves, iterate on `ecrf.conf` + `nginx -s reload`
  (no image rebuild needed); only the SPA base / valve need a rebuild.
