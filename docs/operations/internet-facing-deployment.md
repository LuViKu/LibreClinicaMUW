# Internet-facing deployment

A second deployment of this application may sit behind the MUW DMZ reverse proxy and serve a multicenter study to the internet. It runs the same image as the internal one. One switch changes its posture:

```
LIBRECLINICA_DEPLOYMENT_INTERNET_FACING=true      # property: libreclinica.deployment.internet-facing
```

Default `false`; the internal deployment is unchanged. Set it in `/etc/libreclinica/env` (compose passes it through).

## What the switch does

| Area | Effect when `true` | Where |
|------|--------------------|-------|
| Account-less portals | `/pages/api/v1/public/**` (OCT upload, BCVA entry, image upload, combined upload) answer a bare 404 | `SecurityConfig.INTERNET_FACING_DENIED_PATHS`, `InternetFacingPathBlockFilter` |
| Sidecar / device APIs | `/pages/api/v1/internal/**` (DICOM ingest, worklist) and `/pages/api/v1/device/**` (Optomed worklist, uploader heartbeat) answer 404 | same |
| Operational endpoints | `/actuator/info`, `/actuator/prometheus`, springdoc (`/pages/v3/api-docs*`, `/pages/swagger-ui*` and the bare `/v3/api-docs*`, `/swagger-ui*`) answer 404 | same |
| Healthcheck | `/actuator/health` stays open | unchanged |
| Login failures | locked, 2FA-outdated, unknown and wrong-password all redirect to the same `errorLogin`; the audit row and the denied-login mail still carry the real reason | `SpaLoginFailureHandler` |
| Startup | refuses to start if any active account still has the default password (`12345678`, as seeded for `root`), in either the seeded MD5 form or a bcrypt rehash of it | `InternetFacingStartupGuard` |

The deny list is applied twice: the 404 filter runs first, and the authorization rules deny the same patterns if the filter were ever absent.

## Before the first start

1. Start once with the switch **off**, sign in as `root`, change its password (and any other seeded account's), then set the switch and restart. With the switch on, the start fails with a message naming each account that still has the default password.
2. Scrape metrics from inside the network only. `/actuator/prometheus` is not reachable on this deployment; use the internal one or an exporter on the host.

## Account lockout

Migration `lc-muw-2026-10-06-account-lockout.xml` turns lockout on (`user.lock.switch = TRUE`) with five allowed failed logins, but only where the switch still has its seeded value `FALSE`. An administrator unlocks an account in the SPA (Users, Unlock) or in the legacy UI; the SPA unlock also restores the account status, which a failed-login lock changes to `locked`.

On this deployment the locked state is not shown to the person typing, so a locked user learns of it from the administrator.

## What the switch does not do

- `/app/**` (the SPA shell) stays public; the upload routes in it render but every API behind them is 404.
- The remaining anonymous paths are the login page, `/RequestAccount`, `/Contact`, `/pages/api/v1/contact`, the static assets, `/error` and `/actuator/health`. `/pages/auth/**` (heritage API-key REST API), `/SystemStatus` and, with SSO off, `/pages/sso/reauth` also answer 404 here. The OpenRosa, ODM and anonymous-form paths were removed from the public list for both deployments with the participant chain (#373).
