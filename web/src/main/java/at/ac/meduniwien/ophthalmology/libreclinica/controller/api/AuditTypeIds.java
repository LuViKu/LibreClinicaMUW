/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

/**
 * Canonical {@code audit_log_event_type_id} constants for the
 * audit-table unification sweep (2026-06-12).
 *
 * <p>Every direct-SQL writer to {@code audit_log_event} should pull its
 * type id from here so the type vocabulary stays in one place. Ids in
 * this file are seeded by
 * {@code lc-muw-2026-06-12-audit-event-types-unification.xml} and
 * extend the existing range allocated by:
 * <ul>
 *   <li>Ids 1-35, 40-41 — legacy OpenClinica vocabulary
 *       (item data, study-event, study-subject lifecycle).</li>
 *   <li>Ids 50-56 — Phase E.6 admin actions (user profile, study
 *       identity, study parameters, dataset/subject/audit-log/
 *       discrepancy exports).</li>
 *   <li>Id 57 — eye cohort transition.</li>
 *   <li>Ids 58-60 — modality CRUD.</li>
 *   <li>Ids 61-62 — {@code OPERATION_FAILED} + {@code JOB_FAILED}
 *       (Phase A1, {@code is_user_visible=false}).</li>
 *   <li>Ids 63-74 — Phase audit-coverage gap closure (PR #186).</li>
 *   <li>Ids 75-108 — this sweep (Phase audit-unification).</li>
 * </ul>
 *
 * <p>See {@code docs/development/audit-coverage-2026-06-11.md} and the
 * approved plan at {@code .claude/plans/polished-jumping-swan.md} for
 * the full architecture rationale.
 */
public final class AuditTypeIds {

    private AuditTypeIds() {
        // No instances.
    }

    // ------------------------------------------------------------------
    // CRF library (CrfsApiController.writeLifecycleAudit)
    // ------------------------------------------------------------------

    public static final int CRF_LIFECYCLE_CHANGED            = 75;
    public static final int CRF_VERSION_LIFECYCLE_CHANGED    = 76;

    // ------------------------------------------------------------------
    // Event-CRF (EventCrfsApiController.writeAuditEvent + adjacent)
    // ------------------------------------------------------------------

    public static final int EVENT_CRF_STARTED                = 77;
    public static final int EVENT_CRF_SDV_UNVERIFIED         = 78;

    // ------------------------------------------------------------------
    // CRF version migration (CrfVersionMigrationService)
    // ------------------------------------------------------------------

    public static final int CRF_VERSION_MIGRATED             = 79;

    // ------------------------------------------------------------------
    // Bulk import (ImportApiController, carries reason_for_change)
    // ------------------------------------------------------------------

    public static final int BULK_IMPORT_ATTEMPTED            = 80;

    // ------------------------------------------------------------------
    // Rules + rule sets + rule actions
    // (RulesApiController, RuleExpressionApiController,
    //  RulesImportApiController)
    // ------------------------------------------------------------------

    public static final int RULE_CREATED                     = 81;
    public static final int RULE_UPDATED                     = 82;
    public static final int RULE_ACTION_CREATED              = 83;
    public static final int RULE_ACTION_UPDATED              = 84;
    public static final int RULE_SET_CREATED                 = 85;
    public static final int RULE_SET_LIFECYCLE_CHANGED       = 86;
    public static final int RULE_SET_SCHEDULE_UPDATED        = 87;
    public static final int RULES_IMPORT_COMMITTED           = 88;
    public static final int RULE_SET_RULE_LIFECYCLE_CHANGED  = 89;

    // ------------------------------------------------------------------
    // Sites + studies (SitesApiController, StudiesApiController)
    // ------------------------------------------------------------------

    public static final int SITE_LIFECYCLE_CHANGED           = 90;
    public static final int SITE_FIELD_UPDATED               = 91;
    public static final int STUDY_LIFECYCLE_CHANGED          = 92;

    /**
     * Study status change carries a free-text {@code reason_for_change}
     * supplied by the caller (StudiesApiController:627). Writers MUST
     * populate {@code reason_for_change} on the audit_log_event row.
     */
    public static final int STUDY_STATUS_CHANGED             = 93;

    // ------------------------------------------------------------------
    // Study event (EventsApiController)
    // ------------------------------------------------------------------

    public static final int STUDY_EVENT_UPDATED              = 94;

    // ------------------------------------------------------------------
    // Event definitions (EventDefinitionsApiController)
    // ------------------------------------------------------------------

    public static final int EVENT_DEFINITION_LIFECYCLE_CHANGED = 95;
    public static final int EVENT_DEFINITION_DISABLED          = 96;
    public static final int EVENT_DEFINITION_FIELD_UPDATED     = 97;

    // ------------------------------------------------------------------
    // Group classes (GroupClassesApiController)
    // ------------------------------------------------------------------

    public static final int GROUP_CLASS_LIFECYCLE_CHANGED    = 98;
    public static final int GROUP_CLASS_FIELD_UPDATED        = 99;

    // ------------------------------------------------------------------
    // Subject (SubjectsApiController)
    // ------------------------------------------------------------------

    public static final int SUBJECT_DEMOGRAPHICS_UPDATED     = 100;

    // ------------------------------------------------------------------
    // User account + login (UsersApiController, login servlets, legacy
    // AuditEventDAO.createRow* helpers)
    // ------------------------------------------------------------------

    /**
     * Failed login attempts. Hidden ({@code is_user_visible=false}) —
     * surfaces in the sysadmin /api/v1/audit/system view only.
     */
    public static final int USER_LOGIN_FAILED                = 101;

    public static final int USER_LOGGED_IN                   = 102;
    public static final int USER_PASSWORD_REQUEST_LEGACY     = 103;
    public static final int USER_ACCOUNT_ADMIN_ACTION        = 104;
    public static final int USER_ACCOUNT_GENERIC             = 105;

    // ------------------------------------------------------------------
    // Background-job execution (XsltTransformJob + siblings via
    // AuditEventDAO.createRowForExtractDataJob*)
    // ------------------------------------------------------------------

    public static final int EXTRACT_JOB_SUCCEEDED            = 106;
    public static final int EXTRACT_JOB_FAILED               = 107;

    // ------------------------------------------------------------------
    // Backfill catch-all (Phase 3 only — runtime writers never emit
    // this id; only the lc-muw-2026-06-12-audit-event-backfill.xml
    // changeset emits rows of this type for historical audit_event
    // entries whose (audit_table, action_message) doesn't map cleanly).
    // Hidden ({@code is_user_visible=false}).
    // ------------------------------------------------------------------

    public static final int LEGACY_UNMAPPED                  = 108;

    // ------------------------------------------------------------------
    // DDE pass-2 commit + DM conflict resolution (DdeService)
    // ------------------------------------------------------------------

    /** DDE pass-2 value differs from pass-1 — FAILEDVAL note spawned. */
    public static final int DDE_PASS2_MISMATCH               = 109;

    /**
     * DDE pass-2 finalised. {@code entity_name} carries
     * {@code date_validate_completed} when the CRF flipped to complete,
     * or {@code mismatch_count} when conflicts remain open.
     */
    public static final int DDE_PASS2_COMMITTED              = 110;

    /**
     * Data manager picked a winner for a FAILEDVAL conflict. The
     * follow-up reason-for-change row reuses this same id with
     * {@code entity_name=reason_for_change}.
     */
    public static final int DDE_CONFLICT_RESOLVED            = 111;

    /** Last FAILEDVAL closed — CRF flipped to dde-complete. */
    public static final int DDE_RECONCILIATION_COMPLETE      = 112;

    // ------------------------------------------------------------------
    // Retinal inference (RetinalInferenceApiController)
    // ------------------------------------------------------------------

    public static final int RETINAL_INFERENCE_ENQUEUED       = 113;

    // ------------------------------------------------------------------
    // Study creation (StudiesApiController). Auto-bound COORDINATOR
    // role-grant audit reuses USER_ACCOUNT_ADMIN_ACTION (104) — matches
    // the UsersApi role grant/change/revoke convention.
    // ------------------------------------------------------------------

    public static final int STUDY_CREATED                    = 114;
}
