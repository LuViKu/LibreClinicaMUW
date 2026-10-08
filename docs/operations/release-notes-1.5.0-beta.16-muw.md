# LibreClinica MUW · 1.5.0-beta.16-muw release notes

_DRAFT, 2026-10-08. Written from the `spike/muw-spring-boot-4` branch before stage 3 (live-stack verification) and before the release; check every statement against the released image before publishing._

_Successor to **1.5.0-beta.15-muw**. A platform release: the application moves from Spring Boot 3.5 to **Spring Boot 4.1** (Spring 7, Spring Security 7, Hibernate 7, Jackson 3) and the image from Tomcat 10.1 to **Tomcat 11**, both of which were out of open-source support or about to be (DR-037). It is meant to change nothing a user sees. A few behaviours do change, and three SPA actions that never worked behind the API dispatcher are repaired; they are listed below._

For older releases see [release-notes-1.5.0-beta.15-muw.md](release-notes-1.5.0-beta.15-muw.md) and its predecessors.

**Deployment-breaking:** no, for the platform move itself. There is no new key to set, and the move adds no database migration. The image changes (new Tomcat base, Java 25 as before), so the first start after the upgrade is slower than usual and everyone signs in again. **Rollback is the previous image tag** (see [Upgrading the app VM](#upgrading-the-app-vm)).

_This branch also carries the multicenter-gate work merged into it (de-identification, cross-site isolation, account lockout, the SLURM mode of the retinal sidecar and their three Liquibase changesets). That work has its own notes and its own upgrade steps; this page covers the platform move only. If both ship in one release, merge the two upgrade sections._

---

## Highlights

### The platform moves to Spring Boot 4.1 and Tomcat 11 (DR-037)

| Component | beta.15 | beta.16 |
|---|---|---|
| Spring Boot | 3.5.16 | 4.1.1 |
| Spring Framework | 6.2.19 | 7.0.x |
| Spring Security | 6.5.11 | 7.1.x |
| Hibernate ORM | 6.6 | 7.4.x |
| Jackson | 2.x | 3.1.x (`tools.jackson`) |
| Servlet container (the Docker base image) | `tomcat:10.1-jdk25-temurin` (jakarta servlet 6.0) | `tomcat:11.0-jdk25-temurin` (Jakarta EE 11, servlet 6.1) |
| Java | 25 | 25 (unchanged) |
| Liquibase | 4.31.1 | 4.31.1 (kept pinned: Boot 4.1 would otherwise pull 5.x, which is under the FSL licence) |
| logback | 1.5.34 | 1.5.34 (kept pinned) |

What this means for operators:

- **The base image changes.** Anything that assumed Tomcat 10 paths or settings in the image should be checked: the WAR, the `/usr/local/tomcat/...` paths, the session-cookie context file and the health check are the same as in the Dockerfile of the previous release. A custom `server.xml`, valve or connector tuning mounted into the container is the operator's to re-check.
- **Java stays at 25.** The image still runs the JDK 25 Temurin build.
- **No configuration key changes.** `datainfo.properties`, `/etc/libreclinica/env` and the compose files are used as they were.
- **No database migration comes with the platform move.** Liquibase runs as before; Hibernate 7 reads the same schema.
- **The two XML-defined security beans** that Spring Security 7 changed (the authentication provider) were adjusted in the application; nothing to configure.

### Behaviour changes to know about

- **An audit-row bug that Hibernate 7 would have exposed is fixed.** The audit rows of rule sets and rule set rules (`RuleSetAuditBean`, `RuleSetRuleAuditBean`) carried a cascade that, with Hibernate 7's stricter handling of detached objects, would have made saving an audit row for an existing rule set fail (and deleting an audit row would have cascaded into the rule set). The cascade is gone. Rule-set edits, removals and updates audit as before; a test stores both audit rows against a detached rule set.
- **`save` and `saveOrUpdate` were reviewed at all 97 call sites.** Hibernate 7 removed both; the replacement persists a new object, merges a detached one and leaves a managed one alone. The review found no other caller that needs a change.
- **Scheduled-job times in the administrator's job list are ISO-8601 text.** `previousFireTime`, `nextFireTime` and `finalFireTime` in `GET /pages/api/v1/admin/jobs` were epoch milliseconds on the wire; they are now UTC strings such as `2026-10-07T10:15:30Z`, which is what the SPA's jobs screen has always expected. A script that reads these three fields from the API must be changed to parse text. Nothing else on the SPA's JSON wire changes; the Jackson 3 mapper is configured to read and write what Jackson 2 did, and tests recorded on Jackson 2 pin it, as well as every JSON document the application stores.
- **An unreadable request body gets one stable error message.** A request whose JSON cannot be parsed, or that has no body where one is required, used to return the parser's own wording ("JSON parse error: Unexpected end-of-input ...", which changes with the library). It now returns the same 400 with a fixed message in the usual `message` / `errors` shape. The SPA shows only `message`. Any monitoring that matches the old wording must be updated.
- **A request path the framework cannot parse answers 400, not 500.** `/LibreClinica/%70ages/...` (a percent-encoded letter in the servlet-path prefix) used to produce a 500 from Spring 7's path parsing. A filter ahead of the security chain now answers a bare 400. No allow or deny decision changes: such a request was never served. Expect a few 400s, not 500s, from scanners in the access log.

### Three SPA actions that answered an error through the API dispatcher are repaired

The `pages` dispatcher has no `String` or `Resource` message converter (it never had). Three SPA endpoints were written as if it did, so they answered an error instead of their result. The same converter list is on the previous release (beta.15), so the same three failed there; this was found while the tests were moved onto the production converters. They are fixed in a commit that does not depend on the platform move.

- **CRF version upload (`POST /pages/api/v1/crfs/{oid}/versions`):** the version name, description and revision notes were read as `String` request parts and refused with 415. They are read as form fields now.
- **Study metadata download (`GET /pages/api/v1/studies/{oid}/metadata`):** the ODM document (an `application/xml` string) could not be written and answered 500. It is written as UTF-8 bytes now; the document is unchanged.
- **Download of a file attached to a CRF item (`GET /pages/api/v1/eventCrfs/{id}/items/{itemOid}/file`):** a file resource could not be written and answered 500. The file is sent as bytes now.

**What needs a human to check:** see below. These three were not observed failing on a running instance by whoever wrote this draft: that they failed is derived from the converter list and a test on it, so confirm each works after the upgrade (and, if you still have a beta.15 instance, whether it failed there).

---

## Migrations

None from the platform move.

---

## Configuration

Nothing has to be set. The logging configuration is unchanged (the logback pin stays at 1.5.34).

---

## Upgrading the app VM

Do this outside clinic hours: the restart takes the app down for a few minutes, and everyone signs in again afterwards.

1. **Back up** the database and the file stores, as for any upgrade. Note the **current image tag**: it is the rollback.

2. **Run the host setup script, then pull and restart**, as in the previous release:
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.16-muw   # --dicom too, if a camera speaks DICOM here
   sudo systemctl restart libreclinica
   ```
   The first start pulls the Tomcat 11 based image, so it takes longer than usual.

3. **Expect everyone to sign in again.** Sessions do not survive the restart.

4. **Check the access log for the first day.** The Jackson 3 and Spring 7 changes touch every API call. A rise in 500s is the signal to roll back.

5. **If you read the administrator jobs API from a script**, change it to parse the three time fields as text (see above).

### Rolling back

Stop the service and start the previous image tag (set `--image-tag` back to the beta.15 value and re-run the setup script, then restart). **The platform move rewrites no stored data and adds no migration**, so the database and file stores need no restore. The one exception to check before relying on this: if the same release also carries the multicenter-gate changesets, they will already have run and are not undone by an older image; the older image ignores them. Liquibase's checksums are not touched by this release (the pin stays at 4.31.1), so a rollback across it is the same as between any two beta releases.

---

## What needs a human to check after the upgrade

No automated test drives a browser against the new stack. Each of these is a page or a flow a person has to open.

- **Sign in and open each workspace** (investigator, monitor, data manager, administrator, imaging): the SPA, the legacy JSP pages that remain, and a PDF/Excel export. These sit on Spring 7, Security 7 and the new Tomcat.
- **The CRF version upload form**, with a version name, a description and revision notes, and a spreadsheet. It must create the version.
- **Download a study's metadata** from the study screen: an XML file with the study's ODM document.
- **Download a file attached to a CRF item**, one that was uploaded before the upgrade.
- **The administrator's job list** shows next and previous fire times (as readable dates, not numbers).
- **Edit a rule set, then remove a rule set rule**, and look at the rule audit: the audit rows are written.
- **A session lasting longer than the idle time-out**, and the first request after it: the usual redirect to sign-in, no error page.

---

## Still open at this release

- **Stage 3 of the migration is the live-stack verification** this draft is written before: the authenticated smoke (SPA sign-in, `/pages/api/v1/me`, the administrator jobs list) had not been run when this was drafted.
- **The OpenAPI document is unchanged byte for byte** (261 paths, 226 schemas), so the generated TypeScript client needs no regeneration.
- **Jackson 2 stays on the classpath** through `logstash-logback-encoder` 7.4 and springdoc/swagger-core, until those release Jackson 3 versions.
- **Logback is pinned below 1.5.37** and **Liquibase at 4.31.1**, as before.
