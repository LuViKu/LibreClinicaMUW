# LibreClinica MUW · 1.5.0-beta.15-muw release notes

_Successor to **1.5.0-beta.14-muw**. A security and correctness release across the whole platform: the legacy pages that change data check who is asking and accept POST only, cross-site requests are refused, uploaded and imported files stay inside their directories, the retinal-inference sidecars require their tokens, the Java, Python and SPA dependencies are patched, and several long-standing defects in conditional items, the rule dry run and the export's audit trail are repaired. It also closes every item the beta.14 notes listed as still open._

For older releases see [release-notes-1.5.0-beta.14-muw.md](release-notes-1.5.0-beta.14-muw.md) and its predecessors.

**Deployment-breaking:** in one respect. The production compose file no longer falls back to a retinal preprocess token that is published in this repository, so **the setup script must run before the restart** or the stack will not start. There is no migration and no key to type by hand. Beyond that, several legacy actions become POST-only, the session cookie gains `SameSite=Lax`, and the Apache FOP PDF post-processor is removed. Work through [Upgrading the app VM](#upgrading-the-app-vm) in order, and then through [What needs a human to check](#what-needs-a-human-to-check-after-the-upgrade), which lists the behaviour no automated test covers.

---

## Highlights

### Legacy SDV, export jobs and study modules check the caller (#357)

In the legacy source data verification pages only the listing itself and its table data checked the caller's role. Any logged-in user could set or unset SDV state by id, on any study, and could open the scheduled-export job list and cancel a job.

- Every SDV handler now requires an SDV role — study director, study coordinator or monitor. The six handlers that change SDV state accept POST only and refuse any event CRF or study subject outside the study the session is working in (the study itself or, for a parent study, its sites). In a bulk request, one id from outside refuses the whole request.
- The SDV table pages and their data endpoints refuse a study other than the session's current study.
- The page a handler returns to is now picked from a fixed list of the table views instead of being taken from the request, so the path it forwards to, and the line it logs, are constants.
- The scheduled-export job list, its data endpoint and the cancel action require a system or technical administrator, the same rule as the menu entry that links there. Cancelling a job is POST only.
- The study module switches on `studymodule.jsp` — participant portal, randomisation, and the module and study status form — need a build-study role (administrator, study director, study coordinator) and the study the session is in, and they are submitted through POST. The status form now binds only its own fields, not the row and study ids that were also in the request.
- Changing a CRF version accepts POST only, refuses an event CRF outside the session's study, and refuses a target version that belongs to a different CRF. Two error paths that used to end in a blank page or a server error now show their message: a session without a role, and a failed update.
- Selecting nothing in a bulk SDV action no longer forwards twice.

### Cross-site requests are refused, and the session cookie is SameSite=Lax (#357)

Token-based CSRF protection stays off — hundreds of heritage JSP forms post without a token — so the application gets a token-free defence instead.

- A new filter ahead of the security chain refuses POST, PUT, PATCH and DELETE requests that the browser marks as coming from another site. Where the browser sends no such marker, an `Origin` header must match the application's own scheme, host and port. Requests that carry neither header — the acquisition-PC uploaders, the DICOM receiver, API-key clients — pass as before. The filter reads headers only.
- The session cookie now comes back `Secure; HttpOnly; SameSite=Lax`, set through the WAR's own Tomcat context file. Checked against the Tomcat image this release runs on.
- Nothing legitimate reaches the application cross-site: single sign-on is terminated by the reverse proxy, which redirects into the application with a GET, and the public upload pages post to their own origin.

### LDAP authentication is inert unless it is switched on (#357)

The LDAP provider sat in the authentication manager and in the password-check list whatever `ldap.enabled` said. With LDAP off — the default and the setting here — a failed local login still went on to attempt a bind against whatever host the properties named.

The provider is now wrapped in a switch driven by `ldap.enabled`. Switched off it declines outright; switched on, every call reaches the unchanged LDAP provider.

### Uploaded and imported files stay inside their directories (#356)

Three upload paths built the target file name from the name the browser sent. Each cut that name at the last backslash and joined the rest onto a directory, so a name written with forward slashes still decided where the file landed. Only one of the three checked the result afterwards, and it compared canonical paths as text, which let a neighbouring directory with a matching name prefix through.

- A new helper in `core` reduces an uploaded name to its last segment on every platform and rejects the names that are not file names at all, and compares directories element by element instead of as text.
- Each of the three reacts the way its caller can absorb: the general upload helper aborts the upload, the XForm media upload skips the part with a warning and keeps writing the rest, and the OpenRosa submission settles its directory first and then checks the file against that same directory. Its old temporary-directory fallback, which wrote into a directory nothing ever created and therefore stored nothing, is gone; a directory that cannot be used now fails the submission and says which condition failed.
- The legacy Export Dataset page no longer unzips every ODM export into the server's working directory as a side effect, and dataset names may no longer contain `/` or `\` — the SPA wizard refuses them, as the legacy form already did, and an export run replaces them in names saved earlier.
- The import-log viewer and the data-directory lookup confine the file they open to the directory it must lie in.

### Uploaded XML, form values and filter criteria are checked before they are used (#356, #353)

- **Uploaded and imported XML no longer resolves entity declarations.** Rules XML, ODM clinical-data import (the legacy import page, the SPA import API and the scheduled import job), rules import binding, XForm uploads and OpenRosa submissions were all parsed with a default parser. They now go through parsers that refuse a DOCTYPE outright and disable external entities and external DTD loading. None of these formats uses a DOCTYPE, so no valid file is turned away. The trusted schemas are compiled as before.
- **Long values are refused before the pattern runs.** The e-mail rule is the first check applied to an address on the Contact, Request Password and Request Account forms, which are reachable without logging in, and its run time grows steeply with the length of the input. E-mail values over 254 characters (the limit the mail standard sets) are now refused before the pattern runs, and any pattern-based validation — e-mail, user name, telephone, date, CRF item regular expression — is refused above 4000 characters, the width of the item value column. Data entry already capped text at 3999. The same cap is applied on the login-free contact endpoint and in the rules action editor.
- **The legacy Create Filter wizard** built a fragment of SQL by pasting its connector and each criterion's value together. The connector is now restricted to *and* or *or*, operators to the fixed set the page offers, and values are quoted. The SQL produced for legitimate input is unchanged.
- **Two name lookups in the XForm CRF-version upload** and the item-data listing query now bind their values instead of pasting them in.

### Clinical values, passwords and forged log lines leave the logs (#356, #353, #358)

Following the four log leaks fixed in beta.14, several more paths wrote data into the log, and a value containing a line break could put a line of its own into a log file.

- The OpenRosa validator logged every rejected value in full; it now logs the reason, the data type and the length. The legacy data-entry render dumped the subject label and every item value of the event CRF on each render; it now logs the token names only. The rule expression parser logged its date operands; those lines are gone. The form validator's trace logged each validated field's value — passwords and CRF items among them — and the double-data-entry comparison logged both values; both now log lengths only.
- The system-status API printed every environment variable to the log on each call, credentials included. Removed. The same endpoint group no longer opens a connection to a reporting database whose results were never returned.
- Every console and file log pattern now prints the message with carriage returns and line feeds turned into spaces, so a line break in a value cannot start a line that looks like a log entry. Stack traces keep their line breaks, and the JSON log file already escaped them.
- A calculated item whose formula cannot be evaluated used to leave no trace at all. The messages are now logged at WARN with the item and the event CRF, and they name items and the formula, never item values.

### The retinal-inference sidecars require their tokens and stay in their stores (#355)

- **`/derive` fails closed.** It used to serve every request when no token was configured, and it rendered its images into any directory it was pointed at. It now refuses when no token is configured and when the token does not match, and the directory it works in must lie inside the artifact store — where compose already mounts the application's own artifact store, so no deployment change is needed. Its error replies no longer repeat the path.
- **`/preprocess`** accepts an upload identifier only in the exact form the application sends, and a laterality of OD or OS. Anything else is refused before the upload is read.
- **`/screen`** reads only files inside the upload store, answers the same way whether a file outside it exists or not, and requires the token when one is configured. The application's synchronous screen call now sends that token — without it, in production every upload would have quietly fallen back to the slower queued worker.
- **The four model runners** (fluid, GA, ONL, PR) now accept only absolute paths that resolve inside the shared directories compose mounts into them, and they can be made to require a token of their own. They run without published ports, on the GPU host and in the development profiles only.
- **Shared tokens are compared in constant time** in the retinal-inference routes and in the DICOM receiver's describe endpoint.
- Python dependencies: Pillow 11.0.0 → 12.3.0 in the DICOM receiver and the E2E converter, `requests` 2.32.3 → 2.34.2, and `python-multipart` 0.0.20 → 0.0.32 in the retinal-inference sidecar.

### Conditional (SCD) items hide again when their controlling item changes (#358)

**This is a deliberate behaviour change; please see [What needs a human to check](#what-needs-a-human-to-check-after-the-upgrade).**

The data-entry servlet has returned its page as a plain string since August 2012, but nine checks kept comparing that string with a page constant, which is never equal. They have had no effect for fourteen years. They now compare the page path properly.

Four of those checks govern conditional (SCD) items. Their flag — the one that lets an item be hidden again when the item controlling it changes — is true once more in administrative editing without a forced reason for change, as it was from January 2011 until the 2012 change, and it is now also true in initial data entry, where it was always meant to be: that half of the check named the JSP's page constant and never matched, not even before 2012. With the flag, a conditional item that has been saved can disappear again when its controlling item changes; without it the row stayed pinned visible once a value had been saved. The flag affects rendering only, and the form script still refuses to hide an item whose input holds a value, so only a value the user has cleared can disappear — exactly as it can before a value is first saved.

**Double data entry is deliberately unchanged.** The other five checks governed legacy double data entry. Up to 2012 it showed rule-shown items only after a rule run inside double data entry had marked them, used that mark to skip writing hidden empty items, and left child items without their stored row. For fourteen years it has instead used initial entry's show state and attached the row like every other item. The 2012 logic was **not** revived: doing so would write a second value row for a child item and check it against an empty initial value, and would hide rule-shown items from the second operator until a rule ran again in double data entry, so they would not be keyed twice and the first value would stay unverified. The dead checks are removed and the behaviour of the last fourteen years is kept and documented. Initial values stay hidden from the second operator, as before. Double data entry here is keyed in the SPA; this is the legacy JSP path.

### The RunRule dry run lists what its Submit would execute (#358)

The legacy RunRule dry-run page was always empty — the preview it read was declared and read back but never filled, heritage from the 2010 rewrite — while its Submit button then executed every action. The page now lists the executed actions grouped by rule set target, rule and result, the way the code it replaced grouped them.

What gets executed does not change: the execution loop, its order and its arguments are as before, and the preview is built after the last action has run. It is built for the dry run only, so saving takes no extra work.

### The subject audit trail in the clinical-data export is in time order (#358)

In the clinical-data export, the merged subject, group-assignment and subject audit entries were sorted by the text of their identifier rather than by when they happened, so `AL_1000` came before `AL_200`. They are now sorted by timestamp, entries written in the same transaction by their audit id, undated entries last.

### The item-data SDV listing sees a parent study's sites again (#358)

The paginated item-data SDV listing held its study and parent-study ids in a way that compared them by identity rather than by value. Above id 127 the comparison failed, so a parent study was queried as if it were a site and its sites' subjects dropped out of the list. The ids are compared by value now.

The same endpoint returned any study's item data to any valid API key. It now requires a session user, answers *not found* for an unknown study identifier, and requires a system administrator or an active role on the study — for a site, on the site's parent study; a role on one site alone does not open the whole study. Nothing inside the application calls this endpoint.

### Smaller legacy repairs (#358)

- `Term`, `UserAccountBean` and `InterventionBean` each declared an equality method taking their own type, which overloads rather than overrides the real one: comparisons made from collections, from `Objects.equals` and from JSP expressions silently used the inherited identity comparison instead. All three now override it with the comparison that was intended, and every typed call keeps its result.
- The conditional-item metadata rows compare their ids by value, and no longer fail on an unset id.
- An event CRF's status is compared by value, which was correct only by accident before.
- One SQL query per ODM form-metadata request is gone, along with several containers that were filled and never read.

### Library updates (#359, #354, #355)

Java, patched together and verified as one batch:

- **Spring Framework 6.2.6 → 6.2.19**, **Spring Security 6.4.4 → 6.5.11**, **Spring Boot 3.5.0 → 3.5.16**, **spring-ldap 2.3.2 → 3.3.8**. Twelve Spring advisories, three Spring Security advisories — one of them critical — and two spring-ldap advisories, one of them an authentication bypass on an empty password. The versions now equal what Boot 3.5.16 manages; Jackson, JAXB and the mail implementation are pinned so the newer Boot bill of materials does not split them.
- **Logback 1.5.13 → 1.5.34** (four advisories). Deliberately not 1.5.37 or later: that line removed the conditional configuration syntax our logging configuration uses for its log-location branches.
- **commons-fileupload2 2.0.0-M2 → 2.0.0-M4** with **commons-io 2.16.1 → 2.19.0**, which must move together. The new release caps a part's headers at 512 bytes, which turns away an upload whose file name runs past roughly 350 bytes; Tomcat's own default cap is the same, so no upload that reached the application before is turned away now.
- **Apache POI 5.3.0 → 5.4.1** (a crafted spreadsheet could expand far beyond its stored size when parsed), **Guava 30.0 → 33.4.8**, **PostgreSQL JDBC 42.7.11 → 42.7.12** (42.7.13 was skipped on purpose: it changes driver behaviour).
- **Apache HttpClient 4.5.7 is dropped.** Nothing compiled against it. The participant portal registration code needs an HTTP client that this build has not carried since the Spring 6 upgrade; the participant portal is not used at this site, and restoring it is a separate decision.
- Stale publishing settings that named plain-HTTP URLs are removed from the build.

SPA: **seventeen npm advisories closed** across nine packages, all of them build tooling that arrives through the OCT viewer's imaging library, ESLint and the documentation tooling, and none of which ships in the browser bundle. The built output is byte-identical before and after.

Python: see the sidecar section above.

### The Apache FOP PDF post-processor is removed (#359)

The PDF post-processor for extracts was built on Apache FOP 1.0, released in 2010, which carried two advisories. It was reachable only if an operator had added an `extract.N.post` line naming it: no extract shipped with the application has one, and the only reference in the shipped `extract.properties` was a commented example. Upgrading FOP would have meant a fourteen-year jump and a large change in its supporting libraries on a path nothing exercises, so the dependency and the code that used it were removed instead.

A host may still carry its own `extract.properties` with such a line. That case is handled rather than left to fail quietly: the extract keeps running and simply produces no PDF, and the application logs a warning naming the extract. See [step 2](#upgrading-the-app-vm).

---

## Migrations

None.

---

## Configuration

**No new key has to be set by hand.** What changes:

- `core.retinalInference.preprocessToken` (in `datainfo.properties`) and `RETINAL_INFERENCE_PREPROCESS_TOKEN` (in `/etc/libreclinica/env`) must hold the same per-host value. The setup script writes both on every run. The production compose file no longer has a fallback for the second one.
- The shipped `docker/config/datainfo.properties` now sets `logLevel=info`. On an existing host the file is the operator's, so [step 4](#upgrading-the-app-vm) checks it.
- The sidecar gained a setting for its artifact store, whose default is the directory compose already mounts. Nothing to set.
- The model runners accept an optional `RUNNER_AUTH_TOKEN`. Unset, they behave as before; set to the sidecar's token, they accept the sidecar and nobody else. The development compose file passes it through already.
- The `pdf1.*` block is gone from the shipped `extract.properties`, replaced by a comment explaining why.

---

## Upgrading the app VM

Do this outside clinic hours: the restart takes the app down for a few minutes, and everyone signs in again afterwards.

1. **Back up** the database and the file stores. There is no migration in this release, so nothing is rewritten, but back up as usual before changing the image.

2. **Check this host's `extract.properties` for a PDF post-processor.** The shipped seed at `docker/config/extract.properties` never referenced one, but the file on the VM is the operator's copy and may.
   ```sh
   grep -nE '^extract\.[0-9]+\.post=' /opt/libreclinica/config/extract.properties
   ```
   For each name that comes back, check what kind of post-processor it is:
   ```sh
   grep -nE '^<name>\.postProcessor=' /opt/libreclinica/config/extract.properties
   ```
   If the answer is `pdf`, that extract will keep running after the upgrade and simply produce no PDF, and the application will log a warning naming the extract on each run. Remove the `extract.N.post` line to silence it, and tell whoever expected that PDF. If nothing comes back, there is nothing to do.

3. **Run the setup script — before the restart, not after.**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.15-muw   # --dicom too, if a camera speaks DICOM here
   ```
   It keeps a preprocess token the operator set and otherwise mints one for this host, and writes it into both `/etc/libreclinica/env` and `datainfo.properties` so the two sides match. This matters now: `deploy/compose.production.yaml` no longer falls back to the value published in this repository, so without the token the stack refuses to start, and the sidecar's `/derive` refuses every request that does not carry the matching token. The script also warns if the push token is still the placeholder and if the log level is `debug` or `trace`.

4. **Replace the GPU push token if it is still the placeholder.** Its default, `choose-a-long-shared-secret`, is committed in this public repository.
   ```sh
   grep -E '^core\.retinalInference\.remotePushToken=' /opt/libreclinica/config/datainfo.properties
   ```
   Generate a value with `openssl rand -hex 32` and set the same value here and on the GPU host. Both sides must be changed together, so plan it with whoever runs the GPU host.

5. **Confirm the log level is `info`.** beta.14 already asked for this; the shipped seed now carries it too.
   ```sh
   grep '^logLevel' /opt/libreclinica/config/datainfo.properties
   sudo sed -i 's/^logLevel=.*/logLevel=info/' /opt/libreclinica/config/datainfo.properties
   ```

6. **Restart.**
   ```sh
   sudo systemctl restart libreclinica
   ```
   The DICOM receiver follows the same tag unless `/etc/libreclinica/env` pins `LIBRECLINICA_DICOM_IMAGE_TAG`; if it does, move that pin to this release too.

7. **Expect the session cookie to change.** It now comes back with `SameSite=Lax`, which means the browser sends it on ordinary navigation into the application but not with a form another site submits. Everyone is signed out by the restart in any case. If single sign-on is in use, have the first person to arrive confirm that signing in through it still works.

8. **Expect the legacy SDV, export-job and study-module actions to be POST-only and role-checked.** A bookmarked link or a script that used to change SDV state, cancel an export job or flip a study module by opening a URL no longer does anything, and users without the role for that action are refused. The buttons on the pages themselves were changed to match and work as before.

9. **LDAP stays inert** unless `ldap.enabled=true` is set. If this host has LDAP settings left over from an earlier configuration, they are now ignored rather than attempted after every failed local login.

10. **On the GPU host, optionally close the model runners.** Setting `RUNNER_AUTH_TOKEN` on the four runners to the sidecar's token makes them accept the sidecar only. Leaving it unset keeps today's behaviour. The sidecar's `/derive` now refuses to serve anything if it has no token at all, which the setup script's pairing in step 3 provides.

11. **This release moves Spring 6.2.19, Spring Security 6.5.11 and Spring Boot 3.5.16**, along with Logback, the file-upload libraries, Apache POI, Guava and the PostgreSQL driver. Nothing has to be configured for them, but they touch sign-in, uploads, spreadsheet export and every database call, so watch the first working day after the upgrade more closely than usual and keep the previous image tag available for a rollback.

12. **Nothing changes on the acquisition PCs** in this release. The Export Watcher and the Optomed Bridge are untouched, and requests that carry no browser headers are unaffected by the cross-site filter.

---

## What needs a human to check after the upgrade

No automated test covers the following. Each is a page or a flow a person has to open.

- **Conditional (SCD) items, in initial data entry and in administrative editing.** Behaviour deliberately changed here (see the highlight above). Open a CRF that has a conditional item, give the controlling item a value that shows the conditional item, save, then change the controlling item so the conditional item should no longer apply. Do this in initial data entry and in administrative editing without a forced reason for change. An item whose value the user has **cleared** may now disappear again, where before it stayed visible once it had been saved; an item that still holds a value is not hidden. Check that this reads correctly to the people who key these forms, and that no stored value goes missing. **Legacy double data entry is deliberately unchanged** — the 2012 behaviour was not revived — so a double-entry pass should look exactly as it did before this release.
- **The RunRule dry-run page.** It was empty for fourteen years and now lists the actions its Submit would execute. Run a dry run on a rule set with more than one target and confirm that the list matches what you expect Submit to do, before anyone presses Submit on a real rule set.
- **The legacy actions that became POST.** Cancel a scheduled export job; use the SDV table's row actions and its bulk actions; flip a switch on `studymodule.jsp`. All three now submit through POST and check the caller's role and study, and their buttons were changed for it. Confirm each still works for a user who has the role, and that a user who does not is refused rather than shown an error page.
- **An OCT upload with retinal inference, end to end.** The token pairing in step 3, the sidecar's `/derive` failing closed, the `/preprocess` and `/screen` checks and the synchronous screen call now sending its token all sit on this one path. Upload an OCT volume the way a clinic PC does and confirm it is converted, analysed and that its results and images appear, rather than silently falling back to the queued worker or failing to derive images.

---

## Still open at this release

- **The item-data write endpoint** (`POST /pages/auth/api/itemdata/`) still writes SDV flags for any item-data path it is given, without the study check its listing counterpart gained in this release.
- **A rule set with several targets** hands each action the rule set as it stood at the last target, so e-mail bodies and group ordinals can refer to the last target's subject and event rather than the action's own. Unchanged by this release, noted for follow-up.
- **Two log lines in the calculated-item code** still write a calculated value when a number was expected and something else was found.
- **Logback is pinned below 1.5.37** because that release removed the conditional syntax the logging configuration uses. Moving past it needs the configuration migrated first.
- **Thirteen SPA advisories remain**, all in development tooling that does not ship in the browser bundle (the build and test tools and the documentation tooling). They are out of scope for this round.
- **Token-based CSRF protection stays off.** The cross-site filter added here is the defence; turning tokens on means touching hundreds of heritage forms.
- **The participant portal registration code has no HTTP client.** It has not had a working one since the Spring 6 upgrade; the participant portal is not used at this site.
- The earlier open items from the beta.13 notes stand: the public rate limit never applies in the deployed app, the today's-visits list on the unauthenticated upload page and the Optomed worklist endpoint ship off pending data-protection sign-off, the HealthAEye CRF identifiers are still seeded with a best guess, the SAS output awaits the study statistician, the device scripts are unsigned PowerShell, and `check-i18n` still reports identical de/en strings.
