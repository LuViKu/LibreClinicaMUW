/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Phase E.8 legacy-retirement (2026-06-20) — central registry mapping
 * each legacy servlet path to its successor SPA route + retirement
 * bucket. Drives the deprecation banner ({@link DeprecationBannerHandler})
 * and the access-telemetry filter ({@link LegacyServletTelemetryFilter}).
 *
 * <p>Adding a new entry is one line; the filter + banner pick it up at
 * the next request without code changes elsewhere. Buckets are stable
 * strings so log-aggregation queries ("show me everyone who hit a
 * subject-management page last week") keep working as the catalog
 * grows.
 *
 * <p>This catalog deliberately covers only the "safe to delete" surfaces
 * identified in the 2026-06-20 retirement audit ({@code
 * docs/development/legacy-retirement-2026-06-20.md}). The shim + keep
 * buckets (login flow, admin tooling, print PDF, job admin) are NOT
 * listed here — those still serve real workflows + are tracked
 * separately for the Phase C SPA build-out slices.
 */
@Component
public class LegacyServletDeprecationCatalog {

    /**
     * Bucket labels mirror the audit report. Used as a structured
     * field on every telemetry log line and in the banner copy so
     * operators can act ("Subjects bucket has live traffic — investigate
     * which workflow still uses it").
     */
    public enum Bucket {
        SUBJECTS_AND_EVENTS,
        STUDY_ADMIN_AND_BUILD,
        DATA_EXPORT,
        USER_ACCOUNTS,
        AUDIT_TRAIL,
        DISCREPANCY_NOTES,
        SITES_GROUPS_RULES,
        /** Phase E.8 Slice L2 (2026-06-20) — public unauthenticated forms. */
        SUPPORT_FORMS,
        /** Phase E.8 Slice L3 (2026-06-20) — sysadmin tooling. */
        ADMIN_TOOLING
    }

    /**
     * One catalog row — the legacy servlet path (the value used by the
     * legacy menu / form actions), the SPA route operators should use
     * instead, and the bucket for telemetry.
     */
    public record Entry(String legacyPath, String spaRoute, Bucket bucket) {}

    private final Map<String, Entry> byPath;

    public LegacyServletDeprecationCatalog() {
        Map<String, Entry> m = new LinkedHashMap<>();

        // --- SUBJECTS_AND_EVENTS — SPA `/subjects`, `/event-crfs/:oid`,
        // `/events/:eventId` fully cover these ---
        put(m, "/pages/ListStudySubjects", "/app/subjects", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/ListStudySubjectsManage", "/app/subjects", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/ListStudySubjectsSubmit", "/app/subjects", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/AddNewSubject", "/app/subjects/new", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/ViewStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/UpdateStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/RemoveStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/RestoreStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/ReassignStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/SignStudySubject", "/app/subjects/:subjectId/sign", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/EnterDataForStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/ViewStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/ViewStudyEvents", "/app/events", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/ListEventsForSubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/ListEventsForSubjects", "/app/subjects", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/CreateNewStudyEvent", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/DeleteStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/UpdateStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/RemoveStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/RestoreStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/MarkEventCRFComplete", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/DeleteEventCRF", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/RestoreEventCRF", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/DoubleDataEntry", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/AdministrativeEditing", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);

        // --- STUDY_ADMIN_AND_BUILD — SPA `/studies/:oid/edit`,
        // `/build-study`, `/event-definitions`, `/crf-library` ---
        put(m, "/pages/CreateStudy", "/app/studies/new", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/CreateSubStudy", "/app/studies/new", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/UpdateStudy", "/app/studies/:oid/edit", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/UpdateStudyServlet", "/app/studies/:oid/edit", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/InitUpdateStudy", "/app/studies/:oid/edit", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/RemoveStudy", "/app/studies/:oid/edit", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/RestoreStudy", "/app/studies/:oid/edit", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/ListEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/DefineStudyEvent", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/ViewEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/InitUpdateEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/LockEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/UnlockEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/CreateCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/ListCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/ViewCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/RemoveCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/RestoreCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/InitUpdateCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/ViewCRFVersion", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/CreateCRFVersion", "/app/crf-authoring-canvas/:crfOid", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/DeleteCRFVersion", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/RemoveCRFVersion", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/RestoreCRFVersion", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/InitCreateCRFVersion", "/app/crf-authoring-canvas/:crfOid", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/AddCRFToDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/RemoveCRFFromDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);

        // --- DATA_EXPORT — SPA `/export`, `/datasets/*` ---
        put(m, "/pages/ExtractDatasetsMain", "/app/export", Bucket.DATA_EXPORT);
        put(m, "/pages/ViewDatasets", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/CreateDataset", "/app/datasets/new", Bucket.DATA_EXPORT);
        put(m, "/pages/EditDataset", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);
        put(m, "/pages/RemoveDataset", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/RestoreDataset", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/ApplyFilter", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/CreateFiltersOne", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/CreateFiltersTwo", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/CreateFiltersThree", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/EditFilter", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/RemoveFilter", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/pages/SelectItems", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);
        put(m, "/pages/ViewSelected", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);
        put(m, "/pages/ExportDataset", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);
        put(m, "/pages/ChooseDownloadFormat", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);

        // --- USER_ACCOUNTS — SPA `/manage-users` ---
        put(m, "/pages/ListUserAccounts", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/CreateUserAccount", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/EditUserAccount", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/ViewUserAccount", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/DeleteUser", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/UnLockUser", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/SetUserRole", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/DeleteStudyUserRole", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/EditStudyUserRole", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/ListStudyUser", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/AssignUserToStudy", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/pages/SetStudyUserRole", "/app/manage-users", Bucket.USER_ACCOUNTS);

        // --- AUDIT_TRAIL — SPA `/audit-log`, `/system/audit-log` ---
        put(m, "/pages/AuditLogStudy", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/AuditLogUser", "/app/system/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/AuditUserActivity", "/app/system/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/AuditUserActivityData", "/app/system/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/AuditDatabase", "/app/system/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/ViewItemAuditLog", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/StudyAuditLog", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/StudyAuditLogData", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/ViewStudySubjectAuditLog", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/ViewLogMessage", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/pages/ExportExcelStudySubjectAuditLog", "/app/audit-log", Bucket.AUDIT_TRAIL);

        // --- DISCREPANCY_NOTES — SPA `/notes` ---
        put(m, "/pages/CreateDiscrepancyNote", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/pages/CreateOneDiscrepancyNote", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/pages/ViewDiscrepancyNote", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/pages/ListDiscNotesForCRF", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/pages/ListDiscNotesForCRFData", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/pages/ResolveDiscrepancy", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/pages/ViewNotes", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/pages/ViewNotesData", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/pages/ViewNote", "/app/notes", Bucket.DISCREPANCY_NOTES);

        // --- SITES_GROUPS_RULES — SPA `/sites`, `/group-classes`, `/rules` ---
        put(m, "/pages/CreateSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/ListSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/UpdateSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/RemoveSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/RestoreSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/ListSite", "/app/sites", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/RemoveSite", "/app/sites", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/RestoreSite", "/app/sites", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/ViewSite", "/app/sites", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/RemoveRuleSet", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/RestoreRuleSet", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/RunRule", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/RunRuleSet", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/TestRule", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/ViewRuleSet", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/pages/ViewRuleAssignment", "/app/rules", Bucket.SITES_GROUPS_RULES);

        // --- SUPPORT_FORMS — SPA `/contact` (Phase E.8 Slice L2) ---
        // The legacy Contact form is wired under both /pages/Contact (the
        // standard prefix) and bare /Contact (the form action used by the
        // login-screen "Contact" link). Catalog both so the telemetry +
        // banner cover the historical URL.
        put(m, "/pages/Contact", "/app/contact", Bucket.SUPPORT_FORMS);
        put(m, "/Contact", "/app/contact", Bucket.SUPPORT_FORMS);

        // --- ADMIN_TOOLING — SPA `/admin/*` (Phase E.8 Slice L3) ---
        // SystemStatus has historically been wired at bare /SystemStatus
        // (the menu link) in addition to /pages/SystemStatus.
        put(m, "/pages/SystemStatus", "/app/admin/system-status", Bucket.ADMIN_TOOLING);
        put(m, "/SystemStatus", "/app/admin/system-status", Bucket.ADMIN_TOOLING);
        put(m, "/pages/ConfigurePasswordRequirements", "/app/admin/password-policy", Bucket.ADMIN_TOOLING);
        put(m, "/pages/Configure", "/app/admin/config", Bucket.ADMIN_TOOLING);

        this.byPath = Map.copyOf(m);
    }

    private static void put(Map<String, Entry> m, String legacyPath, String spaRoute, Bucket bucket) {
        m.put(legacyPath, new Entry(legacyPath, spaRoute, bucket));
    }

    /**
     * Look up the catalog entry for an inbound request URI. Matches on
     * exact path or any path prefix (legacy servlets accept query
     * strings + extra segments).
     */
    public Optional<Entry> lookup(String requestUri) {
        if (requestUri == null) return Optional.empty();
        // Strip trailing query / extra segments; legacy servlets accept
        // both /pages/ListStudySubjects and /pages/ListStudySubjects/123
        // and we want both to register as a hit.
        Entry direct = byPath.get(requestUri);
        if (direct != null) return Optional.of(direct);
        for (Map.Entry<String, Entry> e : byPath.entrySet()) {
            if (requestUri.startsWith(e.getKey() + "/") || requestUri.startsWith(e.getKey() + "?")) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /** All registered entries — for tests + the audit dashboard. */
    public Map<String, Entry> all() {
        return byPath;
    }
}
