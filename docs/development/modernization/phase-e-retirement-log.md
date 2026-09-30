# Phase E — legacy UI retirement log

The record [DR-018](decision-record.md) point 6 requires. Every retirement of a legacy screen or file is logged here: what went, what replaces it, when it was closed and deleted, and who signed it off. The sequence is in the [JSP retirement plan](jsp-retirement-plan-2026-09-30.md).

**How a screen retires:**

1. **Dead code** (nothing reaches it) is deleted directly; there is no bake-in.
2. **A covered or not-needed screen** is first **closed**. Its URL goes on the closed-paths list (`libreclinica.legacy.closedPaths`). After that, anyone who is not an administrator gets 410 with a pointer to the SPA route, and an administrator can still reach the screen through `/legacy/<path>`. Every hit is logged under the `legacy-access` logger.
3. **Six months after closing**, and only if the log shows no legitimate use, it is **deleted**.

The six-month clock starts when the closure reaches **production**, not when it merges.

---

## Deleted directly (dead code)

| Date | What | Evidence it was dead | PR | Signed off |
|---|---|---|---|---|
| 2026-09-30 | **36 JSPs** unreachable from any servlet, controller or page: `403.jsp`, `404.jsp`, `access_confirmation.jsp`, `admin/auditLogStudyOld.jsp`, `admin/createCRFConfirm.jsp`, `admin/createCRFVersionConfirmSQL.jsp`, `admin/showAuditEventStudyRow.jsp`, `admin/showSubjectRow.jsp`, `admin/systemStatus.jsp`, `admin/uploadCRFVersionFile.jsp`, `admin/viewScheduler.jsp`, `allSdvResult.jsp`, `extract/createDatasetApplyFilter.jsp`, `extract/createFilterConfirm.jsp`, `extract/selectDiscrepancyAttributes.jsp`, `extract/viewEmptyDatasets.jsp`, `include/breadcrumb.jsp`, `include/showEntities.jsp`, `include/showMessages.jsp`, `include/showPanel.jsp`, `include/showPresetValueText.jsp`, `include/showTableWithTabForDNotes.jsp`, `include/showTerms.jsp`, `include/showTextInput.jsp`, `include/userbox.jsp`, `login-include/footer.jsp`, `login-include/login-sidebar.jsp`, `login/contactPop.jsp`, `managestudy/listSubject.jsp`, `managestudy/showSectionWithoutComments.jsp`, `managestudy/showSubjectDiscNote.jsp`, `submit/initialDataEntry.jsp`, `submit/listEventsForSubjects.jsp`, `submit/showSection.jsp`, `viewSubjectAggregateSDV.jsp`, `viewSubjectSDV.jsp`; plus six `Page` constants that pointed only at them | No `Page` forward, no controller view name, and no include or link by path or by bare filename. A before/after crawl of 176 legacy URLs as two roles gave identical status codes, and the server log showed no missing JSP | #363 | pending (Lukas) |
| 2026-09-30 | `/pages/user` (`UserController`, `user.jsp`), the upstream Spring MVC demo page with four hard-coded names | nothing links to it; no feature | #369 | pending (Lukas) |

## Closed (in bake-in)

| Wave | Closed in code | Reached production | Paths | SPA replacement | Delete not before | Signed off |
|---|---|---|---|---|---|---|
| W0 — not needed | 2026-09-30 (#371) | pending | `/Enterprise`, `/TechAdmin`, `/AdminSystem`, `/AuditDatabase`, `/ListSubject`, `/ListSubjectData`, `/ViewSubject`, `/UpdateSubject`, `/RemoveSubject`, `/RestoreSubject`, `/CreateJobImport`, `/UpdateJobImport`, `/ViewImportJob`, `/ViewLogMessage`, `/PrintoutCertificate`, `/DeleteEventCRF`, `/ConfigurePasswordRequirements` | none needed: SPA home; patient model (`/patients`) and the SPA subject page; imports and two-factor dropped (2026-09-30); `/admin/password-policy` | six months after production | pending (Lukas) |

## Deleted after bake-in

| Wave | Deleted | Paths | Log review (hits by non-admins during bake-in) | PR | Signed off |
|---|---|---|---|---|---|
| — | | | | | |
