# Browser smoke testing — Selenium harness

> Phase B.4 jmesa-replacement support infrastructure.
> First introduced 2026-05-29 alongside the cohort-2 migrations to
> restore the smoke-gate discipline that the earlier cohorts skipped.

## Why this exists

The jmesa-replacement PRs swap server-rendered HTML tables for
client-side DataTables.net 2.x widgets. Integration tests cover the
Java + DAO paths but cannot verify:

- The DataTables JS actually initialises in a real browser.
- AJAX calls to the JSON endpoint succeed end-to-end through Spring
  Security + the servlet container.
- Column renderers (custom `data` keys, action links) produce the
  expected DOM.
- The DataTables JS+CSS bundle is actually present at
  `/includes/js/datatables/`.

A regression in any of those manifests as a *visibly broken admin
page* with no signal in the integration-tests suite. This harness
catches that.

## What's in scope

One `*SmokeIT.java` test class per migrated table. The first concrete
test, [`AuditUserActivitySmokeIT`](../../web/src/test/java/org/akaza/openclinica/smoke/AuditUserActivitySmokeIT.java),
retroactively verifies [PR #36](https://github.com/LuViKu/LibreClinicaMUW/pull/36)
(jmesa cohort 2a).

The base class [`SmokeIT`](../../web/src/test/java/org/akaza/openclinica/smoke/SmokeIT.java)
handles WebDriver lifecycle + login. Subclasses navigate + assert.

## Operator workflow

```sh
# 1. Bring up the LibreClinica stack
docker compose up -d

# 2. Wait for the app to be reachable
until curl -s --head http://localhost:8080/LibreClinica/ | head -1 | grep -q 302; do
  sleep 2
done

# 3. Drop the DataTables.net 2.x JS+CSS bundle into the running web/ source tree
#    (one-time per fresh clone — see includes/js/datatables/README.md).

# 4. Run the smoke profile
mvn -pl web -P smoke-tests test
```

Defaults:

| Property | Default | Effect |
|---|---|---|
| `smoke.base.url` | `http://localhost:8080/LibreClinica/` | URL of the running app |
| `smoke.headless` | `true` | `false` to see the browser window |
| `smoke.username` | `root` | Admin/sysadmin login for the smoke tests |
| `smoke.password` | `password` | Smoke account password |

To run a single test with a visible browser:

```sh
mvn -pl web -P smoke-tests test \
    -Dsmoke.headless=false \
    -Dtest=AuditUserActivitySmokeIT
```

## Why this is not in CI yet

CI would need either:

1. **Docker-in-Docker on the GitHub Actions runner** — possible but
   adds setup complexity and is brittle.
2. **A dedicated self-hosted runner** with Chrome pre-installed — has
   ops overhead the institutional team isn't paying for yet.

Until one of those exists, smoke tests are an **operator-gated merge
check** for jmesa cohort PRs. The expectation:

- The agent writes the smoke test alongside the cohort PR.
- The reviewer runs the smoke locally (`docker compose up && mvn -pl
  web -P smoke-tests test -Dtest=<NewSmokeIT>`) before approving.
- The PR description's "Test plan" section lists which smoke must
  pass — see [PR #36](https://github.com/LuViKu/LibreClinicaMUW/pull/36)
  for the template.

If the smoke is red, the cohort PR is not approved + the agent fixes
the JSP / endpoint until it passes.

## Adding a new smoke test (cohort 2b onwards)

1. Create `web/src/test/java/.../smoke/<TableName>SmokeIT.java`.
2. Extend `SmokeIT`. Use `loginAs(...)` then `goTo(...)`.
3. Wait for the DataTables init: `wait.until(ExpectedConditions
   .visibilityOfElementLocated(By.cssSelector("#<tableId> thead tr")))`.
4. Assert at least one body row (DataTables renders an empty-state
   row even on zero data, so `>= 1` is the floor — adjust if the
   target table has known fixtures).
5. Assert any cohort-specific column / link behaviour.

The locators that survive DataTables-version changes:

- `#<tableId>` — the `<table>` element with the id from the JSP
- `#<tableId> thead tr th` — column headers
- `#<tableId> tbody tr` — body rows
- `div.dt-search input[type='search']` (DataTables 2.x) or
  `div.dataTables_filter input[type='search']` (1.x compat) — global
  search box. Prefer the 2.x selector; the harness uses a comma-OR
  for safety.

## Known limitations

- **No browser binary in the Maven docker image.** The agent that
  authored this harness cannot run the smoke tests itself; it ships
  the code unverified. First-run debugging is on the operator.
- **Selenium-managed driver download** requires network access from
  the test JVM. Behind a proxy, set `SELENIUM_MANAGER_PROXY` or
  pre-place chromedriver on PATH.
- **Login form selectors** (`j_username` / `j_password`) assume
  Spring Security 5 form-login conventions. They survive the Spring
  6 cliff because Spring Security 6 keeps the same field names by
  default.
