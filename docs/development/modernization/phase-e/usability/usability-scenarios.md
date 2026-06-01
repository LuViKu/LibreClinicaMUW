# Phase E.10 — Usability-test scenario cards

Nine scenarios — three per role — sized to ≈ 10–12 minutes each. Hand the participant a one-page printed card per scenario; the moderator's notes (italics, blockquoted, in the *Moderator-only* sections) stay with the test team.

For the scoring rubric + critical-error definitions, see [usability-test-protocol.md](usability-test-protocol.md).

---

## Investigator scenarios

### I-1 · Enrol a new subject and schedule the first visit

**Route:** `/app/subjects/new` → `/app/subjects`
**Participant card:**

> You are Dr. user_demo, an Investigator at site München. A new patient has just consented to enrol in the LCDemo study; the source document says her ID is **M-101**, year of birth 1962, gender female. Please add her to the study and then return to the subject list to confirm.

**Success criteria:**
- The subject row appears in the Subject Matrix with id `M-101`, gender F, year of birth 1962, all three event statuses = Not scheduled, status pill = unsigned.
- The participant chose **Save & finish** (or, if Save & schedule was chosen, returned to the matrix via the back link).

**Critical errors (any = fail):**
- The participant entered the patient's **real name or hospital ID** into Secondary ID. (The SPA warns; the participant saved through the warning.)
- The participant enrolled the subject with a **future enrolment date** without correcting it.
- The participant created a **duplicate subject** (M-001 / M-002 / etc.) without noticing the validation error.

> *Moderator-only: If the participant freezes for >20 s on the gender radio chips, prompt: "What would you do next?" Don't volunteer the chip's keyboard shortcut.*

---

### I-2 · Enter and complete a CRF, including a value that fails validation

**Route:** `/app/event-crfs/EC_M001_V1_DEMO`
**Participant card:**

> You are continuing your morning's data entry on subject **M-001**, V1 Inclusion, the **Demographics** CRF. The source document shows:
> - Consent date: 2026-05-01, signed (Yes)
> - Height: 172 cm
> - Weight: 67.5 kg
> - Systolic BP — leave blank for now
>
> Then **mark the CRF complete** and return to the subject list.

**Success criteria:**
- All four required items populated correctly.
- The systolic-BP optional field is empty without blocking completion.
- The participant clicked **Mark CRF complete** (not Save draft); status pill flips to Complete; navigation returns to subjects.

**Critical errors:**
- The participant **lost typed data** by navigating away (browser-back / a stray link) without saving and didn't notice.
- The participant entered the **weight in pounds** (e.g. 150) without catching the range warning.
- The participant marked the CRF complete with a **required field empty** (the SPA refuses + surfaces an error; the participant didn't read the error).

> *Moderator-only: If the participant types 67.5 into the weight field and the SPA flags it (it shouldn't — range is 1–300), that's a finding. Don't acknowledge the warning yourself.*

---

### I-3 · Sign a subject after reviewing the pre-flight checklist

**Route:** `/app/subjects/M-001/sign`
**Participant card:**

> You are wrapping up subject **M-001**. The data has all been entered; visit V3 is in progress with one open query. Please review the readiness of the subject for sign-off, then **sign** if you are satisfied.

**Success criteria:**
- The participant read the pre-flight checklist and noticed at least the **open-query warning** + the V3-in-progress event.
- The participant chose to either (a) **sign anyway** after acknowledging the warnings or (b) **cancel and reconcile first** — both are valid outcomes; the observer notes which.
- If sign chosen: the e-signature block password challenge was completed with the correct mock password (any non-empty string in dev mode).

**Critical errors:**
- The participant signed the subject **without reading the pre-flight checklist at all** (no eye movement on the four checklist rows — observer judgment call).
- The participant **could not find the sign button** after the e-signature attestation acknowledgement (the button stays disabled until both fields filled — surfaces as the participant getting stuck).
- The participant attempted to sign and the SPA silently accepted the empty password (it shouldn't — should be `disabled` until password ≥ 1 char).

> *Moderator-only: This scenario explicitly tests whether the warning patterns are read or skipped. Both outcomes are fine for the score — the finding is "did they read it" not "did they sign".*

---

## Monitor scenarios

### M-1 · Bulk-verify a session's worth of completed CRFs

**Route:** `/app/sdv`
**Participant card:**

> You are Mona Demo, an external Monitor visiting site München this morning. The site team has completed Demographics + Vitals + Adverse Events on **M-001 + M-004 + M-005** since your last visit. You have just reviewed the source documents for those CRFs and want to mark them as SDV-verified in one go.

**Success criteria:**
- The participant filtered to the subjects + CRFs in scope (search + status + requirement filters, in any order).
- The participant **bulk-selected** at least 3 of the rows.
- The participant clicked the **Mark N as verified** action.
- The participant read the confirmation modal + confirmed.
- The post-confirm toast appeared + the rows flipped to status `verified`.

**Critical errors:**
- The participant **verified a CRF that has an open query** without noticing (the row's `query` status is rose; a participant who bulk-verifies-and-confirms anyway misses it).
- The participant misread the bulk-action's **selection count** (e.g. "8 selected" appeared but they thought they were verifying 3).

> *Moderator-only: The SDV view has a row-level "Add query" link in the action column. If the participant goes to the per-row query flow instead of the bulk verify, that's a different + valid path — note it but continue toward the bulk-verify success criteria.*

---

### M-2 · Open a query on a specific item value mid-review

**Route:** `/app/sdv` → row's Add query modal → `/app/notes`
**Participant card:**

> While reviewing **M-002**'s V1 Inclusion Demographics CRF, you notice that `weight_kg = 155` looks like a transposition. Please open a query on that item asking the site to verify, then confirm the query appears on the Notes & Discrepancies list.

**Success criteria:**
- The Add Query modal was opened from the row.
- Note type = `Query`.
- Description references the source-document discrepancy.
- After Submit, the participant navigated to `/app/notes` and located the new entry (filter by subject = M-002 or scroll).

**Critical errors:**
- The participant chose **Annotation** instead of Query (the legacy distinction matters: only Query triggers an investigator response cycle).
- The participant attached the query to the **wrong item** (the modal's breadcrumb shows the target — observer watches whether the participant verified before submit).

> *Moderator-only: The SDV row's "Add query" link opens the modal pre-filled with the row's context. The participant should verify the modal's title shows M-002 + Demographics + weight_kg before submitting.*

---

### M-3 · Find every Reason-for-Change edit on a specific subject in the audit log

**Route:** `/app/audit-log`
**Participant card:**

> A sponsor reviewer asked for a quick summary of every **Reason-for-Change** edit the site has made on subject **M-001** since 1 May. Open the audit log and tell us the count + the original-vs-new value of each edit.

**Success criteria:**
- The participant filtered by both Subject = M-001 + Event type = Reason for change.
- The participant correctly counted the resulting events.
- The participant read at least one before/after diff card aloud + correctly named the original + new values.

**Critical errors:**
- The participant counted **edits from other subjects** because the subject filter wasn't applied.
- The participant read the **wrong value** off the diff card (the diff is rose before / teal after — the colour mapping matters).

> *Moderator-only: The audit log groups events by date. The participant should iterate via the date markers, not get lost in a flat list.*

---

## Data Manager scenarios

### DM-1 · Onboard a new Investigator with a pending invite

**Route:** `/app/manage-users`
**Participant card:**

> Dr. Fritz Berger is starting at site München next month. He's already in the user list as a pending invite. Please confirm he's correctly configured (Role = Investigator, Site = München, Auth = pending-invite), then locate the "Invite user" action and walk us through what it would do — you do **not** need to actually invite anyone.

**Success criteria:**
- The participant located Dr. Berger's row via the filter or search (his row has the pending-invite badge in the side rail).
- The participant verified the role + site + auth columns.
- The participant clicked **Invite user** and described what the form should do (the SPA renders the button; the modal is out of scope for this v0 walkthrough — the participant should explain what they'd expect).

**Critical errors:**
- The participant assumed an active user is the new Investigator and tried to **modify a real, active row** by mistake.
- The participant filtered to the wrong role (e.g. Monitor) + couldn't find Dr. Berger + gave up before adjusting the filter.

> *Moderator-only: This scenario is intentionally low-stakes — it primarily tests whether the filters + pending-invite badge are discoverable. The "what would Invite User do" part captures their mental model.*

---

### DM-2 · Check the Build-Study progress and identify the next task

**Route:** `/app/build-study`
**Participant card:**

> The Data Manager hands LCDemo over to you for the final 30 % of setup. Please open the Build-Study tracker, tell us the current progress percentage, and identify the **next task you would tackle** + which task is currently blocking the highest-priority screen for clinicians.

**Success criteria:**
- The participant named the progress percentage (71 % in the mock state).
- The participant identified the in-progress tasks (Rules + Users) + chose one with reasoning.
- Bonus: the participant noticed the deep-link arrow on the Users task + clicked through to /manage-users.

**Critical errors:**
- The participant misread the percentage (rounding / parsing error).
- The participant claimed a complete task is in-progress or vice-versa (the status pill colour is the primary tell).

> *Moderator-only: The deep-link arrow only renders on tasks where the supporting SPA view ships. Others render `View pending`. The participant noticing the distinction is a good sign; ignoring it isn't a failure.*

---

### DM-3 · Stage-and-resolve an Import-CRF-Data file

**Route:** `/app/import-crf-data`
**Participant card:**

> The site Müchen sent a corrected ODM XML to reconcile some prior data-entry errors. Please run the 4-step Import wizard end-to-end. You can upload any `.xml` file (it doesn't matter what — the mock layer doesn't read its contents). Pay attention to the **Preview & resolve** step.

**Success criteria:**
- All four wizard steps reached.
- The participant read the per-row before/after diff cells on the Preview step.
- The participant noticed at least one of the 4 categories on the summary cards (Ready / Overwrite / Warning / Error).
- The participant **wrote a Reason-for-Change note** before continuing to Commit (the SPA disables the Continue button until the reason is non-empty when overwrites > 0).
- The participant reached the Commit success card.

**Critical errors:**
- The participant tried to **commit without a Reason-for-Change** + didn't notice the disabled state.
- The participant did **not read** any of the error-row details + missed that 2 of the rows will be skipped.
- The participant skipped over the bulk-stat summary cards entirely.

> *Moderator-only: The Preview step is the heart of this scenario. If the participant flips past it in < 30 seconds they probably didn't process the diff rows — observer's call on whether that counts as a severe finding.*

---

## Calibration notes for the observer

- **Be silent during attempts.** The think-aloud is the participant's. Don't acknowledge correctness or fill silences.
- **Stopwatch starts at "you can begin"** + stops when the participant **says** they're done (not when the screen state suggests done).
- **The critical-error lists are not exhaustive.** Anything that could cause silent wrong-data writes is a critical error even if not listed.
- **Severe vs. minor finding.** Severe = a pattern that prevents a non-trivial subset of clinicians from completing the workflow, even when the participant in front of you happened to push through. Minor = "I'd polish this" but not gate-blocking.
- **Time-on-task is descriptive, not gating.** DR-019 doesn't set a time budget per scenario — but the observer should note when a participant takes > 2× the median for that scenario.
