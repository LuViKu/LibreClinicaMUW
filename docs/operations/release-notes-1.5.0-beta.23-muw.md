# LibreClinica MUW · 1.5.0-beta.23-muw release notes

_Successor to **1.5.0-beta.22-muw**. A small release with two subjects. **A "performed" binding takes only one of its item's answers**, and **CI no longer fails on Maven Central's rate limit**._

_**Imaging:** a binding's yes value is chosen from the CRF item's own answers. A binding saved earlier with a value the item does not have is flagged, and ticks nothing until it is corrected. This came out of the beta.22 upgrade: HealthAEye's BCVA and OCT bindings held `1`, but the items answer `Y` or `N`._

_**Build and CI:**_
- _The smoke image build takes its Maven files from the runner's cache._
- _log4jdbc comes from Maven Central under its own name._
- _CodeQL and the security scan keep their own Maven caches._

For older releases see [release-notes-1.5.0-beta.22-muw.md](release-notes-1.5.0-beta.22-muw.md) and its predecessors.

**Deployment-breaking:** nothing.
- **No migration:** the changelog count stays at 1210.
- **Nothing to configure.** The host setup and nginx are unchanged. Retinal-inference and dicom-scp get the same tag as usual; neither changed.
- **The GPU cluster needs nothing.** Its retinal-inference checkout can stay on beta.21 or beta.22.

**Upgrading from beta.21?** Then beta.22's steps apply too: its four migrations (the dry-run expects 1206 → 1210), the catalogue check, the catch-up and the review of accounts with several roles ([beta.22 notes](release-notes-1.5.0-beta.22-muw.md#upgrading-the-app-vm)).

One thing changes for users, and only for whoever edits the imaging catalogue: **saving a "performed" binding asks for one of the item's answers** (see below). Data entry, export and signing are unchanged.

---

## Highlights

### A "performed" binding takes only one of its item's answers (#456)

#### Why

A binding tells the platform which CRF box to tick when an image is filed, and with which value. The catalogue form defaulted that value to `1` and accepted any text.

On HealthAEye the "performed" items are single-selects answering `Y` (Yes) or `N` (No). Its BCVA and OCT bindings held `1`, so a tick would have written a code the item does not have. The form would have shown no selection, and the export a code that no codelist knows.

None was written. After the beta.22 upgrade the bindings were set to `Y` by hand, and the catch-up then wrote only `Y`.

#### What changes

- **Saving a "performed" binding on a coded item** needs a value that is one of the item's answers, in every CRF version that carries it. A coded item is a single-select, radio, checkbox or multi-select. Otherwise the save is refused (400) and the item's answers are named, for example `Y (Yes), N (No)`.
  - A coded item no longer gets a default value.
  - A free-text item keeps `1` as its default.
  - "Reason" bindings carry no value and are not checked.
- **The catalogue offers the answers.** The form is under *Studienaufbau → Modalitäten → Bildgebung (Studie) → CRF-Feld …*.
  - The *Ja-Wert* field appears only for the role *durchgeführt*.
  - For a coded item the field is a list of the item's answers, shown as `Y – Yes`. When exactly one answer is labelled Ja, Yes, durchgeführt or performed, it is preselected. *Speichern* waits until an answer is chosen.
  - For an OID that no CRF carries, the form says *Kein CRF enthält ein Feld mit dieser OID*.
- **A binding saved earlier with a value the item does not have is flagged** in the list: *Ja-Wert ist keine Antwort dieses Felds – neu speichern*.
- **Such a binding ticks nothing.** A filed image whose binding's value is no answer of the item writes no value and starts no form.
  - The app log warns `performed-tick skipped`, naming the modality, the item and its answers.
  - The catch-up button counts these images: *… nicht gesetzt: Der Ja-Wert der Verknüpfung ist keine Antwort des Felds.*
- **API:**
  - `GET /pages/api/v1/studies/{studyOid}/imaging-modalities/item-answers?itemOid=…` says whether an item is coded and lists its answers.
  - Each "performed" binding in the catalogue's list carries `valueValid`.

---

## Build and CI

On 2026-11-25 and 26 the compose smoke test failed three times, and the PostgreSQL 14 integration tests once, on HTTP 429 (too many requests) from Maven Central. None of the three changes below alters the application.

- **The smoke image build is seeded from the runner's Maven cache** (#457).
  - The Dockerfile gained an empty `m2seed` stage. Both Maven steps read it as a read-only extension of the local repository.
  - CI's smoke job passes the cache restored by `setup-java` into that stage. The image build went from about 910 downloads to none.
  - Local `docker compose` builds and the release image pass nothing, so they build exactly as before.
- **log4jdbc comes from Maven Central as `com.googlecode.log4jdbc:log4jdbc:1.2`** (#458).
  - It used to be `log4jdbc:log4jdbc4:1.2` from the OpenClinica repository. That is the same jar (SHA-1 `ba3acb0b…`), but it had no POM anywhere, so every fresh resolution asked every repository for one, and a 429 there failed the build.
  - The WAR now ships it as `WEB-INF/lib/log4jdbc-1.2.jar`.
  - The SQL transcript at `logLevel` DEBUG or TRACE works as before.
- **The OpenClinica repository is no longer declared** (#458). It answered unknown artifacts with 401. Of what it served, jmesa and mockrunner are retired and log4jdbc now comes from Central.
- **CodeQL and the security scan have their own Maven cache keys** (#459). All three workflows keyed their cache on the POMs alone, and whichever job finished first saved it.
  - On the beta.22 version bump, CodeQL's compile-only job won. lc-develop's builds then restored its cache: 175 MB packed, against the build job's 249 MB.
  - Now only the build job writes the build's cache. The push of #458 confirmed it: the build job saved the cache, and the smoke image build downloaded nothing.

---

## Upgrading the app VM

Do this outside clinic hours. The restart takes the app down for a few minutes, and everyone signs in again afterwards.

1. **Back up, and dry-run against that backup.**
   ```sh
   sudo systemctl start libreclinica-backup-db.service
   IMAGE=ghcr.io/luviku/libreclinicamuw:1.5.0-beta.23-muw sudo -E /opt/libreclinica/deploy/dry-run-migration.sh
   ```
   **Expect no new changeset (1210)** and a healthy start. The rollback is `1.5.0-beta.22-muw`.

2. **Set the tag and restart:**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.23-muw   # plus --dicom, as on the last run
   sudo systemctl restart libreclinica
   ```

3. **Smoke test** (runbook §4) and the checks below. **If anything is wrong, roll back** by running the same two commands with `1.5.0-beta.22-muw`. There is no data change to undo. A rollback only drops the check on binding values.

---

## After the upgrade

1. **Check every "performed" binding against its item's answers.** In *Studienaufbau → Modalitäten → Bildgebung (Studie)* of each study with imaging, no binding should show *Ja-Wert ist keine Antwort dieses Felds*. A flagged binding is corrected by saving it again with one of the offered answers.

   This lists them all at once, each beside the answers its item has:
   ```sql
   select s.oc_oid as study, im.code, b.laterality, b.item_oid, b.performed_value,
          string_agg(distinct rt.name || ': ' || rs.options_values, ' | ') as answers
   from imaging_modality_item_binding b
   join imaging_modality im on im.imaging_modality_id = b.imaging_modality_id
   join study s on s.study_id = im.study_id
   left join item i on i.oc_oid = b.item_oid
   left join item_form_metadata ifm on ifm.item_id = i.item_id
   left join response_set rs on rs.response_set_id = ifm.response_set_id
   left join response_type rt on rt.response_type_id = rs.response_type_id
   where b.role = 'performed' and im.status_id = 1
   group by 1, 2, 3, 4, 5
   order by 1, 2, 3;
   ```
   - For a checkbox, radio, single-select or multi-select, `performed_value` must be one of the comma-separated `options_values`. Any other type accepts any value.
   - Empty `answers` means that no CRF carries the item.

---

## What needs a human to check after the upgrade

- **The answer list.** On HealthAEye, open *CRF-Feld …* with the role *durchgeführt* and `I_HEALT_OCT_PERFORMED`. *Ja-Wert* offers `Y – Yes` and `N – No`, with `Y – Yes` chosen. Close it without saving.
- **The existing bindings.** HealthAEye's list shows its "performed" bindings with `= Y` and without a warning.
- **The catch-up.** Run *Auf bereits zugeordnete Bilder anwenden* again on HealthAEye. It writes nothing new, and its result has no *nicht gesetzt* part.
- **The next upload.** The next Clarus, Remidio or Optomed image filed on HealthAEye ticks its visit's box with `Y`.

---

## Still open at this release

- **CI results.** CI run [45821874754](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/45821874754) on `917ce84ed` (lc-develop with #456–#459, the code of this release without the version bump) passed every job:
  - Maven build and unit tests: core 612 (from 607: 5 tests of the answer parser), web 1,450.
  - Integration tests on PostgreSQL 14 and 17 passed, with the same counts on both: core 692, web 4,551 (from 687 and 4,545).
  - The compose smoke test:
    - `api.ts` matches the live spec;
    - E2E smoke 28 (1 skipped), accessibility 17, DICOM 4;
    - the image build downloaded nothing from Maven repositories.
  - The SPA: 2,637 vitest tests (from 2,631), 4 skipped.
  - The three Python sidecar suites: muw-e2e-converter 83, dicom-scp 107, retinal-inference 344.

  The security scan [45821874312](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/45821874312) and CodeQL [45821874387](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/45821874387) on the same commit passed. The OWASP dependency check runs only on schedule or by hand. It passed on #458's branch, which carries this release's dependencies (run [45792672346](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/45792672346)). The run on the release head is linked in the release PR.
- **The release image build still starts from an empty Maven repository**, as before #457, and downloads about 910 files from Central. It could be seeded the same way as the smoke build.
- **Carried over from beta.22 and still open:** see the [beta.22 notes](release-notes-1.5.0-beta.22-muw.md#still-open-at-this-release). They cover what DICOM export keeps, CRF attachments that are not images, burned-in annotation, the *Modalitäten* page shown to administrators only, and randomisation at enrolment.
