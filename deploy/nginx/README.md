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
sudo docker run --rm -v /opt/libreclinica/deploy/nginx/ecrf.conf:/etc/nginx/conf.d/default.conf:ro \
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
5 8 * * *  /opt/libreclinica/deploy/nginx/cert-expiry-check.sh /etc/libreclinica/tls/dutyplan-augen.crt \
             || echo "DutyPlan TLS cert needs renewal" | mail -s "DutyPlan cert" you@meduniwien.ac.at
```

After installing a renewed cert, reload nginx without downtime:
```sh
sudo docker exec libreclinica-muw-nginx-1 nginx -s reload
```

## Other apps behind this nginx (DutyPlan, DR-038)

The VM also hosts **DutyPlan**, a small separate web app (FastAPI, one container,
its own compose project and repository). This nginx serves it at
`https://dutyplan.augen.meduniwien.ac.at`, so it needs no port of its own.

- **Network:** nginx and DutyPlan meet on the external docker network `edge`.
  `setup-ubuntu-host.sh` creates it, and the systemd unit re-creates it before
  every start (`ExecStartPre`), because compose refuses to start nginx while an
  external network is missing. `compose down` never removes it.
- **Server block:** [dutyplan.conf](dutyplan.conf). The setup script copies it to
  `/etc/libreclinica/nginx-sites/` (mounted into the sidecar and included by
  `ecrf.conf`) **only when** `/etc/libreclinica/tls/dutyplan-augen.crt` and
  `.key` exist, and removes it otherwise. nginx exits on a missing certificate,
  so this is what keeps a DutyPlan cert problem from taking the eCRF down.
- **Upstream:** `dutyplan:8000`, resolved per request, so a stopped DutyPlan
  only returns 502 on its own hostname. Never proxy to `127.0.0.1:8000`: inside
  the container that is nginx itself.
- **Certificate:** a separate one, so the eCRF certificate is never reissued:

  ```sh
  sudo openssl req -new -newkey rsa:2048 -nodes \
    -keyout /etc/libreclinica/tls/dutyplan-augen.key \
    -out    /etc/libreclinica/tls/dutyplan-augen.csr \
    -subj   "/C=AT/O=Medizinische Universitaet Wien/CN=dutyplan.augen.meduniwien.ac.at" \
    -addext "subjectAltName=DNS:dutyplan.augen.meduniwien.ac.at"
  sudo chmod 600 /etc/libreclinica/tls/dutyplan-augen.key
  ```

  Install the signed full chain as `dutyplan-augen.crt` (chmod 600, server cert
  first), then **re-run the setup script** to activate the block, or copy it by
  hand and reload:

  ```sh
  sudo install -m 0644 /opt/libreclinica/deploy/nginx/dutyplan.conf /etc/libreclinica/nginx-sites/
  sudo docker exec libreclinica-muw-nginx-1 nginx -t && sudo docker exec libreclinica-muw-nginx-1 nginx -s reload
  ```

- **DNS:** IT adds a CNAME `dutyplan.augen.meduniwien.ac.at` →
  `vrc-lin-tasks.augen.meduniwien.ac.at`.
- **Rollback:** `sudo rm /etc/libreclinica/nginx-sites/dutyplan.conf` and
  `nginx -s reload` as above. The `edge` network can stay.

Validate with the sites directory mounted:

```sh
sudo docker run --rm --network edge \
     -v /opt/libreclinica/deploy/nginx/ecrf.conf:/etc/nginx/conf.d/default.conf:ro \
     -v /etc/libreclinica/nginx-sites:/etc/nginx/sites:ro \
     -v /etc/libreclinica/tls:/etc/libreclinica/tls:ro nginx:1.27-alpine nginx -t
```

## Notes

- **HSTS** is commented out in `ecrf.conf` — enable it only once HTTPS is
  proven stable on every path (it's hard to unpin in browsers).
- If clean-URL routing misbehaves, iterate on `ecrf.conf` + `nginx -s reload`
  (no image rebuild needed); only the SPA base / valve need a rebuild.
