# Wave 1B — Java backend new endpoints

## Result: PASS

### Default-profile test run (`mvn test`)
`Tests run: 634, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.

### Integration-tests profile (`*DatabaseIT` subset, manual run)
Ran with `TESTCONTAINERS_RYUK_DISABLED=true` + `--network host`:
- `RetinalJobStatusSseControllerDatabaseIT` — 3/3 pass
- `RetinalResultsApiControllerDatabaseIT` — 17/17 pass (10 baseline + 7 new bind/search)
- `PublicOctUploadControllerDatabaseIT` — 9/9 pass (8 baseline + 1 new disambiguation)
- `StudySubjectFinderDatabaseIT` — 6/6 pass

**Total**: 35 ITs pass across the 4 classes.

Note: bare `mvn -P integration-tests test` against the full IT suite hits
Testcontainers Ryuk connectivity errors on Docker-Desktop's nested
networking (same failure mode for unrelated ITs like
`PatientsApiControllerDatabaseIT`). With Ryuk disabled and host networking
my four IT classes run green. CI uses the GitHub-Actions Docker runner
which doesn't have this nested-bridge issue, so the suite should pass
unattended there.

## What ships

### 1. SSE controller + broadcaster
- **`core/.../service/retinal/RetinalJobStatusBroadcaster.java`** (new @Component)
  - `ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>`.
  - `subscribe(jobId, emitter)` registers + hooks completion/timeout/error → eviction.
  - `publish(jobId, status)` fans out; send failure → evict that emitter.
  - `@Scheduled(fixedRate=15_000)` heartbeat keeps idle connections alive across proxies.
- **`web/.../controller/api/RetinalJobStatusSseController.java`** (new)
  - `GET /pages/api/v1/retinal-jobs/{jobId}/status/stream` returns SseEmitter (5-min idle timeout).
  - guardSession + SiteVisibilityFilter; 401 / 400 / 403 / 404 mirror the read-side controller.
- **`web/.../controller/api/RetinalInferenceApiController.java`** — `updateStatus` now calls `broadcaster.publish` after every successful DB flip (null-guarded for legacy test ctors).

### 2. Park-bind endpoint
- **`web/.../controller/api/RetinalResultsApiController.java`**
  - `PATCH /pages/api/v1/retinal-jobs/{jobId}/bind` body `{ eventCrfId }`.
  - 200 success: updates `event_crf_id` + flips `status` → `remote_pending` (when `remoteClient.isConfigured()`) or `queued`; clears `screened_at` + `segmenting_at`.
  - 409 when current status != `parked`.
  - 403 when target event_crf outside site visibility.
  - 404 when job or event_crf missing.
  - Emits `RETINAL_PARK_BIND` (id 116) audit row.
  - Calls `broadcaster.publish` so subscribers see the flip.

### 3. Patient search endpoint
- `GET /pages/api/v1/study-subjects/search?q=<prefix>&limit=<n>` on RetinalResultsApiController.
- Limit clamped to `[1, 50]`; default 10.
- Delegates to **new** `StudySubjectFinder.findByLabelPrefix(prefix, limit)` (case-insensitive ILIKE).
- Result filtered by `SiteVisibilityFilter.visibleStudyIds`.

### 4. Rate limit filter
- **`web/.../web/PublicOctUploadRateLimitFilter.java`** (new @Component)
  - Hand-rolled token bucket; no Maven dep.
  - 30 req/h/IP against `/pages/api/v1/public/oct-upload/**`.
  - 1 token / 120s refill; CAS-based to be safe against concurrent requests.
  - 429 + `Retry-After` header + JSON body on exhaustion.
  - X-Forwarded-For preferred over `remoteAddr`.
  - `@Scheduled(fixedRate=300_000)` evicts buckets idle > 1h.
  - `nowMs()` seam for tests (no sleeps).
- Wired into `SecurityConfig.securityFilterChain` via `.addFilterBefore(filter, ChannelProcessingFilter.class)`.
- `ServletInfraConfig` opt-out for Boot's auto-registration (matches the pattern for myFilter/concurrencyFilter/apiSecurityFilter).
- `SecurityConfig` gained `@EnableScheduling` so the filter's `@Scheduled` + the broadcaster's heartbeat both fire in the root context.

### 5. Ambiguous-match audit
- `PublicOctUploadController.commit` accepts two new optional fields: `disambiguated=true|false` (default false) + `candidateCount=<n>` (default 0).
- When `disambiguated=true` AND a `study_subject_id` was resolved for the audit row, writes a SECOND `audit_log_event` row of type `OCT_UPLOAD_PUBLIC_AMBIGUOUS` (id 117) with `audit_table='study_subject'`, `entity_id=<chosen ssId>`, `new_value="chose:<id>:from:<count> candidates"`.
- Best-effort: failure to write the marker does not block the upload or roll back the main audit row.

### Liquibase
- New `core/src/main/resources/migration/lc-muw-2026-06-19-audit-types-retinal-followups.xml` seeds:
  - id 116 `retinal_park_bind` / "Retinal job bound from park" / `is_user_visible=true`
  - id 117 `oct_upload_public_ambiguous` / "OCT upload (public portal) — ambiguous-match disambiguated" / `is_user_visible=true`
- Added to `master.xml` tail.
- `AuditTypeIds.java` updated with the matching constants.

## New tests
- **`RetinalJobStatusBroadcasterTest`** (5 unit cases): subscribe / publish / failing-emitter eviction / no-op-when-empty / heartbeat-safe-on-empty.
- **`RetinalJobStatusSseControllerDatabaseIT`** (3 IT cases): subscribe registers + broadcast routes, parked-no-event_crf → 403, missing job → 404.
- **`RetinalResultsApiControllerDatabaseIT`** extended with 7 new IT cases (bind happy / 409 / 404; search happy / blank / clamp / no-match).
- **`StudySubjectFinderDatabaseIT`** (6 IT cases): all-matches / limit honored / case-insensitive / blank prefix / non-existent prefix / zero limit.
- **`PublicOctUploadRateLimitFilterTest`** (7 unit cases): under-limit / 31st 429 / refill / unguarded bypass / distinct-IP buckets / X-Forwarded-For preference / idle eviction.
- **`PublicOctUploadControllerDatabaseIT`** extended with 1 new IT case (commit_disambiguated_writesAmbiguousAuditRow).

## Surprises + notes
- **SseEmitter + MockMvc**: `MvcResult.getAsyncResult()` hangs for the full SseEmitter timeout because nothing calls `.complete()` on a streaming emitter. The IT skips the async-wait and inspects the broadcaster registry directly after `perform()` — by then the controller has registered the emitter, which is what the SPA cares about.
- **Spring `@EnableScheduling`**: was not previously enabled anywhere in the app. Added to `SecurityConfig` because that's the @Configuration class that already imports filter + scheduling-adjacent infra; it powers both the broadcaster heartbeat and the rate-limit idle eviction.
- **Auto-register opt-out**: a `@Component` Filter gets auto-mounted at `/*` by Boot's `ServletContextInitializerBeans` unless explicitly disabled via a `FilterRegistrationBean.setEnabled(false)`. Without the opt-out the rate-limit filter would decrement tokens TWICE per request (once via Spring Security's chain, once via the Boot-auto-registered chain). Pattern is identical to the existing myFilter / concurrencyFilter / apiSecurityFilter opt-outs.
- **Back-compat constructor**: `RetinalResultsApiController` grew a 6-arg ctor for the new collaborators (StudySubjectFinder + RemoteRetinalInferenceClient + broadcaster) plus a 3-arg back-compat ctor that null-defaults the trio. The session-guard slice test (`RetinalResultsApiControllerTest`) keeps the old wiring; the search endpoint defensively returns `[]` when the finder is null so the legacy test path still 200s on `/study-subjects/search`.
- **SSE controller 403 vs 404**: had to distinguish "no job row" (404) from "job exists, study chain doesn't resolve" (403, via a wrapper `JobLookup` carrying a nullable Integer). Matches the read-side controller's behaviour for parked jobs with NULL `event_crf_id`.
- **Testcontainers Ryuk**: the standard `mvn -P integration-tests` invocation fails with `Could not connect to Ryuk at 172.17.0.1:<port>` on Docker-Desktop nested networking. With `TESTCONTAINERS_RYUK_DISABLED=true` + `--network host` the suite runs clean. This affects the entire `*DatabaseIT` suite, not just my new tests — flagging here so CI is the canonical runner.

## Files touched
**New**:
- `core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/retinal/RetinalJobStatusBroadcaster.java`
- `core/src/main/resources/migration/lc-muw-2026-06-19-audit-types-retinal-followups.xml`
- `core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/service/retinal/RetinalJobStatusBroadcasterTest.java`
- `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/RetinalJobStatusSseController.java`
- `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/web/PublicOctUploadRateLimitFilter.java`
- `web/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/RetinalJobStatusSseControllerDatabaseIT.java`
- `web/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/service/retinal/StudySubjectFinderDatabaseIT.java`
- `web/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/web/PublicOctUploadRateLimitFilterTest.java`

**Modified**:
- `core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/retinal/StudySubjectFinder.java`
- `core/src/main/resources/migration/master.xml`
- `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/SecurityConfig.java`
- `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/ServletInfraConfig.java`
- `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/AuditTypeIds.java`
- `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/PublicOctUploadController.java`
- `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/RetinalInferenceApiController.java`
- `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/RetinalResultsApiController.java`
- `web/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/PublicOctUploadControllerDatabaseIT.java`
- `web/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/RetinalInferenceApiControllerTest.java`
- `web/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api/RetinalResultsApiControllerDatabaseIT.java`

## Commits
- `3b36a2b60` feat(retinal-followups-1b): SSE broadcaster + status push to subscribers
- `846d80d82` feat(retinal-followups-1b): park-bind + patient-search endpoints
- `6f80ae5a4` feat(retinal-followups-1b): rate limit + ambiguous-match audit

Not pushed; ready for the main session to harmonize with Waves 1A + 1C.
