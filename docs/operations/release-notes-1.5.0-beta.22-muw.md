# LibreClinica MUW · 1.5.0-beta.22-muw release notes

_Successor to **1.5.0-beta.21-muw**. A release about **exporting multimodal data**, and about **accounts with several roles**. It came out of a HealthAEye test export that held a header and nothing else._

_**Export:**_
- _A dataset can be filtered by **participant ID**. "Does not equal" is applied, and numbers compare as numbers._
- _A reopened dataset keeps its visits, items and filters, and saving it no longer deletes them._
- _The wizard and the export dialog say what an export will carry, and warn before a text export that would hold headers only._
- _The multimodal bundle opens with four tables: participants, visits, images and a data dictionary._
- _**Images leave the platform scrubbed.** DICOM goes out under the Basic Confidentiality Profile, keeping dates, geometry and laterality. JPEG and PNG lose their metadata. Any other file is withheld and named._

_**Imaging:** HealthAEye's fundus images tick their "performed" box, and one button ticks the images filed before._

_**Roles:** an account may do what any of its roles on a study allows, so an Investigator who is also the Data Manager signs. Monitor is held alone. Any treating role refuses a change of a participant's AI arm._

For older releases see [release-notes-1.5.0-beta.21-muw.md](release-notes-1.5.0-beta.21-muw.md) and its predecessors.

**Deployment-breaking:** nothing has to be set.
- **Four migrations:** the changelog count goes from 1206 to 1210.
  - One adds a column to the dataset table.
  - Three fill gaps in HealthAEye's imaging catalogue. They touch only `S_HAE`, and only add what is missing.
- **Nothing to configure, no change to the host setup or nginx.** Retinal-inference and dicom-scp get the same tag as usual; neither changed.
- **The GPU cluster needs nothing.** Its retinal-inference checkout can stay on beta.21.

**Upgrading from beta.20?** Then beta.21's steps apply too: the closed-screens check, the cluster checkout for #441 and the role setup ([beta.21 notes](release-notes-1.5.0-beta.21-muw.md#upgrading-the-app-vm)).

Three things change for users the day it is installed:
1. **Signing with several roles.** An account that holds **Investigator beside another role** on a study, Data Manager for example, can sign from the upgrade on. Under beta.21 it could not. If one of them should not sign on a study, remove its Investigator role there (see [After the upgrade](#after-the-upgrade)).
2. **Exported images are scrubbed.** A recipient gets DICOM files whose patient name and ID are the study label, without birth date, age, descriptions or protocol name. E2E volumes, PDFs and scanned forms attached to CRFs are no longer in the bundle; the manifest names them.
3. **Monitor beside another role can no longer be granted.** User management refuses it (409), and the role dialog says why. Accounts that already combine them keep working.

Work through [Upgrading the app VM](#upgrading-the-app-vm), then [After the upgrade](#after-the-upgrade) and [What needs a human to check](#what-needs-a-human-to-check-after-the-upgrade).

---

## Highlights

### Exporting multimodal data (#453)

#### Why the HealthAEye export was empty

HealthAEye had 59 participants, 59 visits and 176 filed images, and no CRF value yet. The text formats (tab, CSV, SPSS, ODM) carry CRF values only, so they correctly found nothing. The images are carried by the multimodal bundle, which is switched on per study. Filtering for `HAE-050` found nothing either: the filter offered CRF items only, and the study's participant-ID item was never filled.

#### Filters

- **Filter by participant.** A filter row can name the **participant ID** (equals, does not equal, is one of, starts with) or the **enrolment date**, instead of a CRF item. Case and surrounding blanks in the ID do not matter.
- **"Does not equal" is applied.** The export ignored the row, and with no other filter exported every participant, although the preview had counted the restricted set.
- **Numeric items compare as numbers.** Before, "95" was greater than "100", as text. A value that is not a number does not match.
- **The preview and the export cannot disagree.** Both restrict through the same code.
- **A filter the export could not apply is refused on save**, instead of being stored and ignored.

#### Reopening a saved dataset

- **The selection comes back.** A reopened dataset showed nothing ticked, with no way past the first step.
- **The filters come back, and saving keeps them.** Saving a reopened dataset deleted its filters, which silently widened every later export.
- **"CRF completion date" is stored** (new column). Three options that changed nothing are no longer offered: event start time, event end time and discrepancy notes.

#### What an export will carry

The filter preview also counts the visits, the filed images and the CRF values of the selected items. The wizard's review step and the export dialog show these counts. They warn when a text format would hold headers only, and point to the bundle, or say who can switch it on.

#### The multimodal bundle

- **Four tables at the top of the zip:**
  - `participants.csv`;
  - `visits.csv`;
  - `images.csv`: visit, device, modality, eye, path in the zip, checksum, and how the file was treated;
  - `data_dictionary.csv`: the selected items with labels, types, units and answer options.

  They are written even when empty.
- **The manifest** gives each image its visit and lists the tables.
- **Checksums are those of the files in the zip.** The checksum stored at upload is taken before the header is pseudonymised. It identifies the original export and did not match the bundled file.

#### Images are scrubbed on export

Decided 2026-11-24 ([DR-028 amendment](../development/modernization/decision-record.md#dr-028--an-export-carries-the-evidence-and-says-what-a-person-did-not-write)). The stored files are never changed; only the copy in the export is scrubbed.

- **DICOM: the Basic Application Level Confidentiality Profile** (DICOM PS3.15 Annex E), with the option that retains full dates. The scrub uses dcm4che 5.35.2.
  - **Kept:** study, series, acquisition and content dates and times, and what analysis needs beyond the profile's list: pixel spacing, laterality, slice thickness, orientation, the ophthalmic acquisition attributes, and pixel measures and frame laterality inside functional groups.
  - **Replaced:** patient name and ID become the participant's study label. UIDs are remapped the same way every time, so a study's files still belong together.
  - **Removed:** birth date, age, size and weight; free-text descriptions and the protocol name; private tags, curves and overlays. This applies in every nested sequence.
  - **Withheld:** a file that declares burned-in annotation, since the profile does not clean pixels.
  - Each file records that the patient identity was removed, and by which method.
- **JPEG and PNG: metadata stripped.**
  - JPEG: EXIF, XMP, IPTC and comments go; colour profiles stay.
  - PNG: text, time and private chunks go.
  - The picture itself is unchanged.
- **Anything else is withheld and named** in the manifest (`omitted`): E2E volumes, PDFs and scanned forms attached to CRFs, and any file whose content does not match its recorded type. The casebook in the bundle does not point at a withheld file.
- **The pipeline's B-scan DICOM and fundus PNG are scrubbed too.** Computed outputs, such as geometry and masks in their own formats, are copied.

### Performed ticks for fundus images (#453)

On HealthAEye, none of the 176 filed images had ticked its "performed" box.

- **Missing bindings.** The 2026-11-02 seed binds a modality only to a CRF item that already exists, and it ran once, before the HealthAEye CRF had its Remidio, Optomed and Clarus items. A new changeset adds the missing "performed" and "reason" bindings wherever the item exists now.
- **The Optomed device name.** The Optomed entry named its device `OPTOMEDLUMO`, which is the camera's AE title. Bridge uploads say `optomed`, so they matched nothing: no tick, no modality on the file, no match with the visit's imaging plan. The changeset sets the device to `optomed` and keeps the AE title for C-STORE.
- **Modality on bind.** A filed image now gets its catalogue entry from its device or AE title, when exactly one entry of the study matches and accepts its file type.
- **Catch-up for images already filed.** A new button in the imaging catalogue, *Auf bereits zugeordnete Bilder anwenden*, ticks "performed" for the images filed before their binding existed.
  - It writes as the system account and is audited per tick.
  - It never overwrites a value someone entered, and leaves removed, locked or signed CRFs alone.
  - Running it twice changes nothing.

### An account may do what any of its roles allows (#452, DR-042)

Most checks read only the role the session is bound to, the account's highest-ranked one (#442). So an Investigator who was also the Data Manager was bound as Data Manager and could not sign.

- **Permissions are the union of the account's roles** on the active study, or on its parent when the active study is a site. This covers data entry, signing visits and casebooks, participants, notes, imaging, export and the study settings. The app offers what the server allows.
- **Monitor is held alone on a study.** A Monitor verifies source data and closes queries; every other role enters data. Adding Monitor beside another role, or the reverse, is refused, and the role dialog explains why. Accounts that already combine them are not changed. They work under the union until an administrator edits them, and the dialog shows the hint.
- **Any treating role refuses a change of a participant's AI arm**, whatever else the account holds. A Data Manager who was also a treating Investigator could move a patient out of the hidden arm and then see the AI output. Data Manager and Administrator still move participants; the rule on completed visits is unchanged.
- **Unchanged:** which sites' participants a list shows, and the role in the top bar, still follow the bound role. Blinding already counted every treating role.

---

## Smaller items

- **dcm4che is vendored** in `web/lib/maven`, with checksums enforced. It is not on Maven Central, and declaring its own repository made CI's dependency resolution hang for 2.5 hours and the security scan time out. Its licence (LGPL 2.1 or later) is compatible with the project's.
- **The manuals** describe the export (`role-data-manager.md` §10 and §11) and the role rules (`role-administrator.md`).

---

## Upgrading the app VM

Do this outside clinic hours. The restart takes the app down for a few minutes, and everyone signs in again afterwards.

1. **Tell the study team first.** Accounts with Investigator beside another role can sign from the upgrade on, and exported images are scrubbed (see the top of these notes).

2. **Back up, and dry-run against that backup.**
   ```sh
   sudo systemctl start libreclinica-backup-db.service
   IMAGE=ghcr.io/luviku/libreclinicamuw:1.5.0-beta.22-muw sudo -E /opt/libreclinica/deploy/dry-run-migration.sh
   ```
   **Expect 4 new changesets (1206 → 1210)** and a healthy start. The rollback is `1.5.0-beta.21-muw`.

3. **Set the tag and restart:**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.22-muw   # plus --dicom, as on the last run
   sudo systemctl restart libreclinica
   ```

4. **Smoke test** (runbook §4) and the checks below. **If anything is wrong, roll back** by running the same two commands with `1.5.0-beta.21-muw`. No data change needs undoing: beta.21 ignores the new dataset column, and the catalogue changes only fill gaps.
   - A rollback exports images unscrubbed again.
   - It also brings back the single-role checks.

---

## After the upgrade

1. **Check HealthAEye's imaging catalogue.** Remidio, Optomed and Clarus (and BCVA and OCT) should each have a `performed` and a `not_performed_reason` binding, and Optomed the device `optomed`:
   ```sql
   select im.code, im.device, im.auto_match_ae_title, b.role, b.item_oid
   from imaging_modality im
   join study s on s.study_id = im.study_id
   left join imaging_modality_item_binding b on b.imaging_modality_id = im.imaging_modality_id
   where s.oc_oid = 'S_HAE'
   order by im.code, b.role;
   ```
   The changeset binds only items that exist. Bind a missing one by hand in the catalogue (*Studienaufbau → Modalitäten → Bildgebung (Studie)*).

2. **Tick the images already filed.** As an administrator, with HealthAEye active: *Studienaufbau → Modalitäten → Bildgebung (Studie) → Auf bereits zugeordnete Bilder anwenden*. The result names how many images were checked and how many ticks were written.

3. **Switch on the multimodal bundle for HealthAEye**, if its images are to be exported: *Studienaufbau → Studienparameter → Plattform-Einstellungen → Multimodaler Export*.

4. **Review the accounts with several roles on a study.** From the upgrade on, an account with Investigator beside another role signs. This lists every account with more than one active role on a study:
   ```sql
   select r.user_name, s.unique_identifier as study,
          string_agg(r.role_name, ', ' order by r.role_name) as roles
   from study_user_role r join study s on s.study_id = r.study_id
   where r.status_id = 1
   group by r.user_name, s.unique_identifier
   having count(*) > 1
   order by 1, 2;
   ```
   In `role_name`, `Data Specialist` is Investigator, `director` is Data Manager and `coordinator` is CRC (Koordinator).
   - Remove Investigator where an account should not sign.
   - Resolve any combination with `Monitor`: the role dialog shows the hint on it.

---

## What needs a human to check after the upgrade

- **The participant filter.** A dataset on HealthAEye with the filter *Teilnehmer-ID = HAE-050* previews one participant.
- **Reopening.** Reopen that dataset: its visits, items and filter are there. Save it, reopen it again, and the filter is still there.
- **The warning.** On a study without CRF values, the review step warns that a text export would hold headers only.
- **The bundle**, exported for one participant:
  - the zip opens with `participants.csv`, `visits.csv`, `images.csv` and `data_dictionary.csv`;
  - a DICOM opened in a viewer shows the study label as patient name, keeps its acquisition date, pixel spacing and laterality, and has no birth date;
  - an E2E file, if the participant has one, is named in the manifest's `omitted` and is not in the zip.
- **Performed ticks.** After the catch-up, a HealthAEye visit with a filed fundus image has its "performed" box ticked. The next Optomed upload ticks its visit when it is filed.
- **Signing.** An account with Investigator and Data Manager on a study signs a test visit.
- **Monitor.** Adding Monitor beside another role in *Nutzer verwalten* is refused with the explanation.

---

## Still open at this release

- **CI results.** CI run [45628382322](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/45628382322) on `412f3961d` (#453's head: lc-develop after #452 with #453 merged in, the code of this release without the version bump):
  - Maven build and unit tests: core 607, web 1,450. Core gains 8 filter tests and web 7 scrubber tests and 3 role tests.
  - Integration tests on PostgreSQL 14 and 17 passed, with the same counts on both: core 687, web 4,545 (from 679 and 4,509).
  - The compose smoke test: `api.ts` matches the live spec; E2E smoke 28, accessibility 17, DICOM 4.
  - The SPA: 2,631 vitest tests, 4 skipped.
  - The three Python sidecar suites: muw-e2e-converter 83, dicom-scp 107, retinal-inference 344.

  Security scan [45628387949](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/45628387949) on the same commit passed (Trivy, Gitleaks, OWASP). #452 alone passed every job of build run 44104343361 and the security scan. The run on the release head is linked in the release PR.
- **What else DICOM export should keep.** The protocol name, study and series descriptions, and age are removed, as the profile says. A study that needs one back can have it kept explicitly.
- **CRF attachments that are not images** (PDFs, scanned forms) are never exported. Carrying them would need a scrub of their own.
- **A DICOM with burned-in annotation** is withheld, not cleaned. Cleaning pixels is a separate profile option.
- **The *Modalitäten* page is shown to administrators only**, although the catch-up and the catalogue also admit a Data Manager on the server.
- **Randomisation at enrolment** is reworked before the planned AI study starts randomising. No current study randomises.
- **Carried over from beta.21 and still open:** see the [beta.21 notes](release-notes-1.5.0-beta.21-muw.md#still-open-at-this-release).
