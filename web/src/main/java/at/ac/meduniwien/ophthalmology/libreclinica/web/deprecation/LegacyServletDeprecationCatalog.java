/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Every legacy screen the retirement tracking knows about: each servlet path
 * registered by {@code config.LegacyServletRegistry}, and each Spring MVC
 * route under {@code /pages} that renders a JSP. For each one it records the
 * SPA route that replaces it, if there is one, and a bucket for grouping the
 * access log. Used by {@link LegacyServletTelemetryFilter} (logging and
 * closure) and {@link LegacyAliasServlet} (the administrators' {@code /legacy/}
 * alias).
 *
 * <h2>Keys</h2>
 *
 * <p>A key is the path inside the web application, without the context path,
 * exactly as the container maps it:
 *
 * <ul>
 *   <li>a legacy servlet is keyed by its servlet path, e.g.
 *       {@code /ListUserAccounts}. Servlets are mapped at exact paths, so the
 *       key matches only that path;</li>
 *   <li>a Spring MVC route is keyed {@code /pages} plus the route, e.g.
 *       {@code /pages/studymodule}. It also matches every path below it
 *       ({@code /pages/studymodule/S_1/deactivate}), because those are the
 *       same screen's actions.</li>
 * </ul>
 *
 * <p>Until 2026-09-30 most servlet keys carried a {@code /pages} prefix the
 * servlets were never mapped under, and four named no registered servlet, so
 * no request matched them. {@code LegacyServletDeprecationCatalogTest} now
 * checks the servlet keys against the registry in both directions: a servlet
 * added to or deleted from {@code LegacyServletRegistry} is added to or
 * deleted from this catalogue in the same change.
 *
 * <h2>SPA routes</h2>
 *
 * <p>{@code spaRoute} is the SPA route a user of the screen should use instead;
 * {@code null} means none is recorded. It points at the replacement; it does
 * not claim parity, which is recorded per screen and role in the Phase E
 * feature catalogues before a screen is closed. Screens that the
 * administrator catalogue (§16.3) found uncovered have no route here, and
 * neither have the screens added on 2026-09-30 until their coverage is
 * recorded. A route that is named exists in the SPA router; the test checks
 * that too.
 */
@Component
public class LegacyServletDeprecationCatalog {

    /**
     * Functional area of a legacy screen, logged with every hit so the log can
     * be aggregated per area ("the user-accounts screens still have traffic").
     * The names are part of the log format: add new ones, but do not rename.
     */
    public enum Bucket {
        SUBJECTS_AND_EVENTS,
        STUDY_ADMIN_AND_BUILD,
        DATA_EXPORT,
        USER_ACCOUNTS,
        AUDIT_TRAIL,
        DISCREPANCY_NOTES,
        SITES_GROUPS_RULES,
        /** Public, unauthenticated forms. */
        SUPPORT_FORMS,
        /** Sysadmin tooling. */
        ADMIN_TOOLING,
        /** Print-friendly views. */
        PRINT_PDF,
        /** Quartz job administration. */
        JOB_ADMIN,
        /** CRF data entry, import and the event-CRF views around it. */
        DATA_ENTRY,
        /** Source data verification (Spring MVC pages). */
        SOURCE_DATA_VERIFICATION,
        /** Main menu, study switch, logout, profile and password screens. */
        SHELL_AND_LOGIN
    }

    /**
     * One catalogue row.
     *
     * @param legacyPath the key: a servlet path, or {@code /pages} plus a route
     * @param spaRoute   the SPA route that replaces the screen, or {@code null}
     *                   when none is recorded
     * @param bucket     the functional area
     */
    public record Entry(String legacyPath, String spaRoute, Bucket bucket) {

        /** True when {@link #spaRoute()} names a replacement. */
        public boolean hasSpaRoute() {
            return spaRoute != null;
        }

        /** True for a Spring MVC route under {@code /pages}, false for a servlet. */
        public boolean isPagesRoute() {
            return legacyPath.startsWith(PAGES_PREFIX);
        }
    }

    /** Servlet path of the {@code pages} DispatcherServlet ({@code web.xml}). */
    static final String PAGES_SERVLET_PATH = "/pages";

    private static final String PAGES_PREFIX = PAGES_SERVLET_PATH + "/";

    /** No SPA route recorded; reads better in the table than a bare {@code null}. */
    private static final String NONE = null;

    /** Every entry, by key, in declaration order. */
    private final Map<String, Entry> byPath;

    /** Servlet entries, by servlet path. */
    private final Map<String, Entry> servlets;

    /** Spring MVC entries, by the route below {@code /pages} ({@code /studymodule}). */
    private final Map<String, Entry> pagesRoutes;

    /** First segment of every Spring MVC route, so that other {@code /pages} requests cost one probe. */
    private final Set<String> pagesFirstSegments;

    public LegacyServletDeprecationCatalog() {
        Map<String, Entry> m = new LinkedHashMap<>();

        // --- SUBJECTS_AND_EVENTS — SPA `/subjects`, `/event-crfs/:oid`, `/events/:eventId` ---
        put(m, "/ListStudySubjects", "/app/subjects", Bucket.SUBJECTS_AND_EVENTS);
        // ListStudySubjectsManageServlet is mapped at the singular path.
        put(m, "/ListStudySubject", "/app/subjects", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/ListStudySubjectsSubmit", "/app/subjects", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/AddNewSubject", "/app/subjects/new", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/ViewStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/UpdateStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/RemoveStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/RestoreStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/ReassignStudySubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/SignStudySubject", "/app/subjects/:subjectId/sign", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/EnterDataForStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        // The SPA has no study-event list: there is no /app/events route.
        put(m, "/ViewStudyEvents", NONE, Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/ListEventsForSubject", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/ListEventsForSubjects", "/app/subjects", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/CreateNewStudyEvent", "/app/subjects/:subjectId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/DeleteStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/UpdateStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/RemoveStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/RestoreStudyEvent", "/app/events/:eventId", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/MarkEventCRFComplete", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/DeleteEventCRF", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/RestoreEventCRF", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/DoubleDataEntry", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/AdministrativeEditing", "/app/event-crfs/:eventCrfOid", Bucket.SUBJECTS_AND_EVENTS);

        // --- STUDY_ADMIN_AND_BUILD — SPA `/studies/:oid/edit`, `/build-study`,
        // `/event-definitions`, `/crf-library` ---
        put(m, "/CreateStudy", "/app/studies/new", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/CreateSubStudy", "/app/studies/new", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/UpdateStudy", "/app/studies/:oid/edit", Bucket.STUDY_ADMIN_AND_BUILD);
        // UpdateStudyServletNew; the key used to be its class name.
        put(m, "/UpdateStudyNew", "/app/studies/:oid/edit", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/InitUpdateStudy", "/app/studies/:oid/edit", Bucket.STUDY_ADMIN_AND_BUILD);
        // Admin catalogue §16.3: the study edit view offers neither remove nor restore.
        put(m, "/RemoveStudy", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/RestoreStudy", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ListEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/DefineStudyEvent", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ViewEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/InitUpdateEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/LockEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/UnlockEventDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/CreateCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ListCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ViewCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/RemoveCRF", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        // Admin catalogue §16.3: no API or view restores or updates a CRF.
        put(m, "/RestoreCRF", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/InitUpdateCRF", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ViewCRFVersion", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/CreateCRFVersion", "/app/crf-authoring-canvas/:crfOid", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/DeleteCRFVersion", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/RemoveCRFVersion", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/RestoreCRFVersion", "/app/crf-library", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/InitCreateCRFVersion", "/app/crf-authoring-canvas/:crfOid", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/AddCRFToDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/RemoveCRFFromDefinition", "/app/event-definitions", Bucket.STUDY_ADMIN_AND_BUILD);

        // --- DATA_EXPORT — SPA `/export`, `/datasets/*` ---
        put(m, "/ExtractDatasetsMain", "/app/export", Bucket.DATA_EXPORT);
        put(m, "/ViewDatasets", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/CreateDataset", "/app/datasets/new", Bucket.DATA_EXPORT);
        put(m, "/EditDataset", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);
        put(m, "/RemoveDataset", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/RestoreDataset", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/ApplyFilter", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/CreateFiltersOne", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/CreateFiltersTwo", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/CreateFiltersThree", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/EditFilter", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/RemoveFilter", "/app/datasets", Bucket.DATA_EXPORT);
        put(m, "/SelectItems", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);
        put(m, "/ViewSelected", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);
        put(m, "/ExportDataset", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);
        put(m, "/ChooseDownloadFormat", "/app/datasets/:datasetId/edit", Bucket.DATA_EXPORT);

        // --- USER_ACCOUNTS — SPA `/manage-users` ---
        put(m, "/ListUserAccounts", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/CreateUserAccount", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/EditUserAccount", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/ViewUserAccount", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/DeleteUser", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/UnLockUser", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/SetUserRole", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/DeleteStudyUserRole", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/EditStudyUserRole", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/ListStudyUser", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/AssignUserToStudy", "/app/manage-users", Bucket.USER_ACCOUNTS);
        put(m, "/SetStudyUserRole", "/app/manage-users", Bucket.USER_ACCOUNTS);

        // --- AUDIT_TRAIL — SPA `/audit-log`, `/system/audit-log` ---
        put(m, "/AuditLogStudy", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/AuditLogUser", "/app/system/audit-log", Bucket.AUDIT_TRAIL);
        // Admin catalogue §16.3: /system/audit-log reads neither the user-activity
        // table nor the database audit, so neither screen (nor the activity
        // page's data endpoint) has a replacement.
        put(m, "/AuditUserActivity", NONE, Bucket.AUDIT_TRAIL);
        put(m, "/AuditUserActivityData", NONE, Bucket.AUDIT_TRAIL);
        put(m, "/AuditDatabase", NONE, Bucket.AUDIT_TRAIL);
        put(m, "/ViewItemAuditLog", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/StudyAuditLog", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/StudyAuditLogData", "/app/audit-log", Bucket.AUDIT_TRAIL);
        put(m, "/ViewStudySubjectAuditLog", "/app/audit-log", Bucket.AUDIT_TRAIL);
        // Admin catalogue §16.3: an import-job log-file viewer, not an audit log.
        put(m, "/ViewLogMessage", NONE, Bucket.AUDIT_TRAIL);
        put(m, "/ExportExcelStudySubjectAuditLog", "/app/audit-log", Bucket.AUDIT_TRAIL);

        // --- DISCREPANCY_NOTES — SPA `/notes` ---
        put(m, "/CreateDiscrepancyNote", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/CreateOneDiscrepancyNote", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/ViewDiscrepancyNote", "/app/notes", Bucket.DISCREPANCY_NOTES);
        // ListDiscNotesForCRFServlet is mapped under its class name.
        put(m, "/ListDiscNotesForCRFServlet", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/ListDiscNotesForCRFData", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/ResolveDiscrepancy", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/ViewNotes", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/ViewNotesData", "/app/notes", Bucket.DISCREPANCY_NOTES);
        put(m, "/ViewNote", "/app/notes", Bucket.DISCREPANCY_NOTES);

        // --- SITES_GROUPS_RULES — SPA `/sites`, `/group-classes`, `/rules` ---
        put(m, "/CreateSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/ListSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/UpdateSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/RemoveSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/RestoreSubjectGroupClass", "/app/group-classes", Bucket.SITES_GROUPS_RULES);
        put(m, "/ListSite", "/app/sites", Bucket.SITES_GROUPS_RULES);
        put(m, "/RemoveSite", "/app/sites", Bucket.SITES_GROUPS_RULES);
        put(m, "/RestoreSite", "/app/sites", Bucket.SITES_GROUPS_RULES);
        put(m, "/ViewSite", "/app/sites", Bucket.SITES_GROUPS_RULES);
        put(m, "/RemoveRuleSet", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/RestoreRuleSet", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/RunRule", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/RunRuleSet", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/TestRule", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/ViewRuleSet", "/app/rules", Bucket.SITES_GROUPS_RULES);
        put(m, "/ViewRuleAssignment", "/app/rules", Bucket.SITES_GROUPS_RULES);

        // --- SUPPORT_FORMS — SPA `/contact` ---
        put(m, "/Contact", "/app/contact", Bucket.SUPPORT_FORMS);

        // --- ADMIN_TOOLING — SPA `/admin/*` ---
        put(m, "/SystemStatus", "/app/admin/system-status", Bucket.ADMIN_TOOLING);
        put(m, "/ConfigurePasswordRequirements", "/app/admin/password-policy", Bucket.ADMIN_TOOLING);
        // Admin catalogue §16.3: this is the lockout configuration, which
        // /admin/config does not show.
        put(m, "/Configure", NONE, Bucket.ADMIN_TOOLING);

        // --- PRINT_PDF — SPA `/event-crfs/:eventCrfOid/print` ---
        put(m, "/PrintEventCRF", "/app/event-crfs/:eventCrfOid/print", Bucket.PRINT_PDF);
        put(m, "/PrintCRFById", "/app/event-crfs/:eventCrfOid/print", Bucket.PRINT_PDF);
        put(m, "/PrintAllEventCRF", "/app/event-crfs/:eventCrfOid/print", Bucket.PRINT_PDF);
        put(m, "/PrintAllSiteEventCRF", "/app/event-crfs/:eventCrfOid/print", Bucket.PRINT_PDF);

        // --- JOB_ADMIN — SPA `/admin/jobs` ---
        put(m, "/ViewAllJobs", "/app/admin/jobs", Bucket.JOB_ADMIN);
        put(m, "/ViewJob", "/app/admin/jobs", Bucket.JOB_ADMIN);
        put(m, "/ViewImportJob", "/app/admin/jobs", Bucket.JOB_ADMIN);
        put(m, "/ViewSingleJob", "/app/admin/jobs", Bucket.JOB_ADMIN);
        // Admin catalogue §16.3: /admin/jobs is read-only; nothing pauses,
        // resumes or deletes a job.
        put(m, "/PauseJob", NONE, Bucket.JOB_ADMIN);

        // --- Registered servlets with no SPA route recorded, added 2026-09-30
        // so that every legacy servlet is logged and can be closed. A route
        // is added once the feature catalogues record the replacement. ---
        put(m, "/InitialDataEntry", NONE, Bucket.DATA_ENTRY);
        put(m, "/SubmitData", NONE, Bucket.DATA_ENTRY);
        put(m, "/CheckCRFLocked", NONE, Bucket.DATA_ENTRY);
        put(m, "/TableOfContents", NONE, Bucket.DATA_ENTRY);
        put(m, "/ViewTableOfContent", NONE, Bucket.DATA_ENTRY);
        put(m, "/ViewSectionDataEntry", NONE, Bucket.DATA_ENTRY);
        put(m, "/ViewSectionDataEntryById", NONE, Bucket.DATA_ENTRY);
        put(m, "/ViewSectionDataEntryRESTUrlServlet", NONE, Bucket.DATA_ENTRY);
        put(m, "/ViewEventCRF", NONE, Bucket.DATA_ENTRY);
        put(m, "/ViewEventCRFContent", NONE, Bucket.DATA_ENTRY);
        put(m, "/ImportCRFData", NONE, Bucket.DATA_ENTRY);
        put(m, "/VerifyImportedCRFData", NONE, Bucket.DATA_ENTRY);
        put(m, "/UploadFile", NONE, Bucket.DATA_ENTRY);
        put(m, "/DownloadAttachedFile", NONE, Bucket.DATA_ENTRY);

        put(m, "/FindStudyEvent", NONE, Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/FindSubjectsData", NONE, Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/ListEventsForSubjectsData", NONE, Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/RemoveEventCRF", NONE, Bucket.SUBJECTS_AND_EVENTS);

        put(m, "/ListStudy", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ViewStudy", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ManageStudy", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ManageStudy1", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/DownloadStudyMetadata", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/UpdateEventDefinition", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/RemoveEventDefinition", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/RestoreEventDefinition", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ViewEventDefinitionReadOnly", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ChangeDefinitionOrdinal", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ChangeDefinitionCRFOrdinal", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/RestoreCRFFromDefinition", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        // Admin catalogue §16.3: /UpdateCRF and /BatchCRFMigration are uncovered.
        put(m, "/UpdateCRF", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/BatchCRFMigration", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/LockCRFVersion", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/UnlockCRFVersion", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/ViewCRFVersionPreview", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/SectionPreview", NONE, Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/DownloadVersionSpreadSheet", NONE, Bucket.STUDY_ADMIN_AND_BUILD);

        put(m, "/AccessFile", NONE, Bucket.DATA_EXPORT);
        put(m, "/ShowFile", NONE, Bucket.DATA_EXPORT);
        put(m, "/EditSelected", NONE, Bucket.DATA_EXPORT);
        put(m, "/ViewItemDetail", NONE, Bucket.DATA_EXPORT);

        put(m, "/ViewStudyUser", NONE, Bucket.USER_ACCOUNTS);
        put(m, "/RemoveStudyUserRole", NONE, Bucket.USER_ACCOUNTS);
        put(m, "/RestoreStudyUserRole", NONE, Bucket.USER_ACCOUNTS);
        put(m, "/PrintoutCertificate", NONE, Bucket.USER_ACCOUNTS);

        put(m, "/DiscrepancyNoteOutputServlet", NONE, Bucket.DISCREPANCY_NOTES);

        put(m, "/InitUpdateSubStudy", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/UpdateSubStudy", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/ViewSubjectGroupClass", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/ImportRule", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/VerifyImportedRule", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/UpdateRuleSetRule", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/ViewRuleAssignmentNew", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/ViewRuleAssignmentData", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/ViewRuleSetAudit", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/DownloadRuleSetXml", NONE, Bucket.SITES_GROUPS_RULES);
        put(m, "/ExecuteCrossEditCheck", NONE, Bucket.SITES_GROUPS_RULES);

        put(m, "/RequestAccount", NONE, Bucket.SUPPORT_FORMS);
        put(m, "/RequestPassword", NONE, Bucket.SUPPORT_FORMS);

        put(m, "/AdminSystem", NONE, Bucket.ADMIN_TOOLING);
        put(m, "/TechAdmin", NONE, Bucket.ADMIN_TOOLING);
        put(m, "/SendTestEmail", NONE, Bucket.ADMIN_TOOLING);
        // The cross-study subject registry (admin catalogue §10).
        put(m, "/ListSubject", NONE, Bucket.ADMIN_TOOLING);
        put(m, "/ListSubjectData", NONE, Bucket.ADMIN_TOOLING);
        put(m, "/ViewSubject", NONE, Bucket.ADMIN_TOOLING);
        put(m, "/UpdateSubject", NONE, Bucket.ADMIN_TOOLING);
        put(m, "/RemoveSubject", NONE, Bucket.ADMIN_TOOLING);
        put(m, "/RestoreSubject", NONE, Bucket.ADMIN_TOOLING);

        put(m, "/PrintCRF", NONE, Bucket.PRINT_PDF);
        put(m, "/PrintCRFOld", NONE, Bucket.PRINT_PDF);
        put(m, "/PrintDataEntry", NONE, Bucket.PRINT_PDF);

        put(m, "/CreateJobExport", NONE, Bucket.JOB_ADMIN);
        put(m, "/UpdateJobExport", NONE, Bucket.JOB_ADMIN);
        put(m, "/CreateJobImport", NONE, Bucket.JOB_ADMIN);
        put(m, "/UpdateJobImport", NONE, Bucket.JOB_ADMIN);

        // The shell. A login lands on /MainMenu and the SPA's logout calls
        // GET /Logout, so closing either before wave W5 breaks the SPA.
        put(m, "/MainMenu", NONE, Bucket.SHELL_AND_LOGIN);
        put(m, "/ChangeStudy", NONE, Bucket.SHELL_AND_LOGIN);
        put(m, "/Logout", NONE, Bucket.SHELL_AND_LOGIN);
        put(m, "/UpdateProfile", NONE, Bucket.SHELL_AND_LOGIN);
        put(m, "/ResetPassword", NONE, Bucket.SHELL_AND_LOGIN);
        put(m, "/MatchPassword", NONE, Bucket.SHELL_AND_LOGIN);
        put(m, "/RequestStudy", NONE, Bucket.SHELL_AND_LOGIN);
        put(m, "/Enterprise", NONE, Bucket.SHELL_AND_LOGIN);

        // --- Spring MVC routes under /pages that render a JSP. Each also
        // covers the paths below it. ---
        put(m, "/pages/studymodule", "/app/build-study", Bucket.STUDY_ADMIN_AND_BUILD);
        put(m, "/pages/viewAllSubjectSDVtmp", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/viewAllSubjectSDV", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/viewAllSubjectSDVform", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/viewAllSubjectSdvData", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/viewSubjectAggregate", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/viewSubjectAggregateData", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/handleSDVPost", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/handleSDVGet", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/handleSDVRemove", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/sdvStudySubject", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/unSdvStudySubject", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        put(m, "/pages/sdvStudySubjects", "/app/sdv", Bucket.SOURCE_DATA_VERIFICATION);
        // Admin catalogue §9: the SPA lists its own export jobs and cancels none.
        put(m, "/pages/listCurrentScheduledJobs", NONE, Bucket.JOB_ADMIN);
        put(m, "/pages/listCurrentScheduledJobsData", NONE, Bucket.JOB_ADMIN);
        put(m, "/pages/cancelScheduledJob", NONE, Bucket.JOB_ADMIN);
        put(m, "/pages/extract", NONE, Bucket.DATA_EXPORT);
        put(m, "/pages/managestudy/chooseCRFVersion", NONE, Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/managestudy/confirmCRFVersionChange", NONE, Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/managestudy/changeCRFVersion", NONE, Bucket.SUBJECTS_AND_EVENTS);
        put(m, "/pages/admin/listLdapUsers", NONE, Bucket.USER_ACCOUNTS);
        put(m, "/pages/admin/selectLdapUser", NONE, Bucket.USER_ACCOUNTS);
        // The upstream demo stub that fills user.jsp with sample names (DR-018).
        put(m, "/pages/user", NONE, Bucket.USER_ACCOUNTS);

        Map<String, Entry> servletMap = new LinkedHashMap<>();
        Map<String, Entry> pagesMap = new LinkedHashMap<>();
        Set<String> firstSegments = new HashSet<>();
        for (Entry e : m.values()) {
            if (e.isPagesRoute()) {
                String route = e.legacyPath().substring(PAGES_SERVLET_PATH.length());
                pagesMap.put(route, e);
                firstSegments.add(firstSegment(route));
            } else {
                servletMap.put(e.legacyPath(), e);
            }
        }
        this.byPath = Collections.unmodifiableMap(m);
        this.servlets = Map.copyOf(servletMap);
        this.pagesRoutes = Map.copyOf(pagesMap);
        this.pagesFirstSegments = Set.copyOf(firstSegments);
    }

    private static void put(Map<String, Entry> m, String legacyPath, String spaRoute, Bucket bucket) {
        Entry previous = m.put(legacyPath, new Entry(legacyPath, spaRoute, bucket));
        if (previous != null) {
            throw new IllegalStateException("Duplicate legacy catalogue key " + legacyPath);
        }
    }

    /** {@code /studymodule/S_1/x} → {@code /studymodule}. */
    private static String firstSegment(String route) {
        int next = route.indexOf('/', 1);
        return next < 0 ? route : route.substring(0, next);
    }

    /**
     * The screen a request is for, found from the request's own mapping:
     * {@code HttpServletRequest.getServletPath()} and {@code getPathInfo()}.
     * Neither includes the context path, so the result does not depend on
     * where the WAR is deployed.
     *
     * <p>A request that is not for a legacy screen costs one hash probe; one
     * to the {@code pages} DispatcherServlet also costs a substring of its
     * first path segment.
     */
    public Optional<Entry> lookup(String servletPath, String pathInfo) {
        if (servletPath == null) {
            return Optional.empty();
        }
        if (PAGES_SERVLET_PATH.equals(servletPath)) {
            return Optional.ofNullable(pagesRoute(pathInfo));
        }
        // Servlets are mapped at exact paths, which leave pathInfo null. A
        // request with a pathInfo went to some other, prefix-mapped servlet.
        return pathInfo == null ? Optional.ofNullable(servlets.get(servletPath)) : Optional.empty();
    }

    /**
     * The screen for a path inside the application ({@code /ListUserAccounts},
     * {@code /pages/studymodule/S_1/deactivate}). For paths that did not come
     * from the container's own mapping, such as the part of a
     * {@code /legacy/…} request after the alias.
     */
    public Optional<Entry> lookup(String path) {
        if (path == null) {
            return Optional.empty();
        }
        if (path.startsWith(PAGES_PREFIX)) {
            return Optional.ofNullable(pagesRoute(path.substring(PAGES_SERVLET_PATH.length())));
        }
        return Optional.ofNullable(servlets.get(path));
    }

    /** The entry with exactly this key, if any. */
    public Optional<Entry> entry(String legacyPath) {
        return legacyPath == null ? Optional.empty() : Optional.ofNullable(byPath.get(legacyPath));
    }

    /**
     * A route below {@code /pages} matches its own path and every path under
     * it, on a segment boundary.
     */
    private Entry pagesRoute(String pathInfo) {
        if (pathInfo == null || pathInfo.length() < 2 || pathInfo.charAt(0) != '/') {
            return null;
        }
        if (!pagesFirstSegments.contains(firstSegment(pathInfo))) {
            return null;
        }
        String route = pathInfo;
        while (true) {
            Entry e = pagesRoutes.get(route);
            if (e != null) {
                return e;
            }
            int cut = route.lastIndexOf('/');
            if (cut <= 0) {
                return null;
            }
            route = route.substring(0, cut);
        }
    }

    /** All entries, by key, in declaration order; for the tests and the startup summary. */
    public Map<String, Entry> all() {
        return byPath;
    }
}
