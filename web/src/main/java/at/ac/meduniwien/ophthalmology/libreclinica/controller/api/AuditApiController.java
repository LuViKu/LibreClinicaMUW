/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.export.XlsxWorkbookBuilder;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M10 — Audit-log adapter.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /pages/api/v1/audit?actor=…&variant=…&subjectId=…}
 *       — returns {@link AuditEventDto} rows for the session-bound
 *       active study, newest first. The SPA's audit-log view groups
 *       these by ISO date and renders {@code before}/{@code after}
 *       values via a diff card on {@code data} / {@code
 *       reason-for-change} rows.</li>
 * </ul>
 *
 * <p>The schema's {@code audit_log_event} table has no study_id
 * column — it stores raw entity_id values whose meaning depends on
 * {@code audit_table}. The scoping query below unions five
 * {@code audit_table} cases (item_data → event_crf → study_event →
 * study_subject → study; event_crf via event_crf_id; study_subject
 * direct; subject via study_subject join; study_event direct) so a
 * single SQL fetch covers everything happening inside the active
 * study without a per-entity walk.
 *
 * <p>Variant mapping (audit_log_event_type_id):
 * <ul>
 *   <li>1, 12, 13, 30, 40, 41 → {@code data} (item-data / event-crf
 *       lifecycle)</li>
 *   <li>8, 10, 11, 14, 15, 16 → {@code data} (CRF completion)</li>
 *   <li>17–26, 35 → {@code data} (study-event lifecycle)</li>
 *   <li>31 → {@code signed}</li>
 *   <li>32 → {@code sdv}</li>
 *   <li>2–7, 9, 27, 33 → {@code admin}</li>
 *   <li>28, 29 → {@code subject-group-change} (subject added to or
 *       moved between treatment-arm groups; lets the SPA render the
 *       before/after group labels separately from generic admin
 *       events). Phase E.5 #2 promoted these out of the admin bucket.</li>
 *   <li>140 → {@code reason-for-change}; 137-139 → {@code data}</li>
 *   <li>any row with non-blank {@code reason_for_change} → {@code
 *       reason-for-change} (overrides the type mapping), except rows about
 *       an ingested file, whose reason is a dismissal's</li>
 * </ul>
 *
 * <p>Since 2026-09-27 each row is labelled by what it records
 * ({@link AuditRowLabels#effectiveType}), not by its id alone: 11, 27 and
 * 128 were written with two meanings each. Visit and ingest rows name their
 * subject and visit, ingest rows their file, and failures their operation
 * and error ({@link AuditRowContext}).
 *
 * <p>The database filters, counts and pages the whole trail
 * ({@link AuditLogQuery}): actor, variant, subject, item and a date
 * range. The rows come newest first, ties broken by {@code audit_id},
 * one page at a time with the total count
 * ({@link AuditPageDto}); the export reads every matching row. Until
 * 2026-09-30 both read the newest 500 rows of the study and filtered
 * those, so older rows of a subject, user or item could not be reached.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Study-scoped audit-log query.")
public class AuditApiController {

    private static final Logger LOG = LoggerFactory.getLogger(AuditApiController.class);

    /** The columns {@link AuditRowContext.Row#read} reads. */
    private static final String AUDIT_COLUMNS = """
            SELECT
              a.audit_id, a.audit_date, a.audit_table, a.entity_id,
              a.entity_name, a.reason_for_change, a.audit_log_event_type_id,
              a.old_value, a.new_value, a.event_crf_id, a.study_event_id,
              a.user_id, ua.user_name, alet.name AS type_name,
              alet.display_name AS type_display_name
            """;

    /**
     * The tables every audit read joins: the actor's name and the type's
     * name and visibility. The study scope and the filters refer to both.
     */
    private static final String AUDIT_FROM = """
            FROM audit_log_event a
            LEFT JOIN user_account ua ON ua.user_id = a.user_id
            LEFT JOIN audit_log_event_type alet
              ON alet.audit_log_event_type_id = a.audit_log_event_type_id
            """;

    /**
     * Newest first; {@code audit_id} orders rows of the same instant, so
     * pages neither repeat nor skip a row. Served by
     * {@code i_audit_log_event_date_id} (lc-muw-2026-09-30-audit-log-event-date-index.xml).
     */
    private static final String NEWEST_FIRST = " ORDER BY a.audit_date DESC, a.audit_id DESC";

    /** Rows per page when the caller does not say, and the most it may ask for. */
    static final int DEFAULT_PAGE_SIZE = 100;
    static final int MAX_PAGE_SIZE = 500;

    /** Rows the export reads per round trip; it reads until none are left. */
    static final int EXPORT_BATCH = 1000;

    /**
     * The rows of a set of studies: the {@code WHERE} predicate of every
     * study-scoped read. The literal {@code __IN__} token is replaced at
     * call time with a placeholder list of the right arity, one IN per
     * audit-table branch. Until A4, the SQL had a literal {@code = ?}
     * per branch; A4 generalises to per-site visibility — Monitor with
     * a single site grant under a multi-site study now sees only that
     * site's rows.
     *
     * <p>The system log (sysadmin only) reads every row instead: no
     * {@code is_user_visible=true} filter, so {@code OPERATION_FAILED(61)}
     * and {@code JOB_FAILED(62)} rows are there for §11.10(e) review, and
     * no study scope.
     */
    private static final String STUDY_SCOPE_TEMPLATE = """
            -- Phase A1 (2026-06-10) — hide OPERATION_FAILED / JOB_FAILED
            -- rows from the per-study investigator view. They are
            -- recorded for §11.10(e) compliance + sysadmin / compliance
            -- review but are operational noise for a study coordinator.
            -- Legacy event types default to is_user_visible=true via
            -- the Liquibase column add. A future sysadmin / compliance
            -- audit-log endpoint drops this clause to see everything.
            -- COALESCE keeps the historical rows that pre-date the
            -- audit_log_event_type lookup join (NULL type id) visible.
            COALESCE(alet.is_user_visible, true) = true
            AND (
              ( a.audit_table = 'item_data'
                AND a.audit_log_event_type_id IS DISTINCT FROM 129
                AND a.entity_id IN (
                  SELECT id.item_data_id FROM item_data id
                    JOIN event_crf ec ON ec.event_crf_id = id.event_crf_id
                    JOIN study_event se ON se.study_event_id = ec.study_event_id
                    JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id
                  WHERE ss.study_id IN __IN__))
              OR ( a.audit_table = 'event_crf' AND a.entity_id IN (
                  SELECT ec.event_crf_id FROM event_crf ec
                    JOIN study_event se ON se.study_event_id = ec.study_event_id
                    JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id
                  WHERE ss.study_id IN __IN__))
              OR ( a.audit_table = 'study_subject' AND a.entity_id IN (
                  SELECT study_subject_id FROM study_subject WHERE study_id IN __IN__))
              OR ( a.audit_table = 'subject' AND a.entity_id IN (
                  SELECT subject_id FROM study_subject WHERE study_id IN __IN__))
              OR ( a.audit_table = 'study_event' AND a.entity_id IN (
                  SELECT se.study_event_id FROM study_event se
                    JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id
                  WHERE ss.study_id IN __IN__))
              -- Phase E.6 (2026-06-03): include study-identity edits.
              -- StudiesApiController.writeStudyFieldAudit emits rows
              -- with audit_table='study' and entity_id=study_id when
              -- an admin edits the name, sponsor, PI, etc. The SPA
              -- Audit Log was missing those entirely until this branch
              -- joined them in.
              OR ( a.audit_table = 'study' AND a.entity_id IN __IN__)
              -- Phase E.6 (2026-06-05): include dataset-export events.
              -- ExportAuditService.emitExportAudit + the retention GC
              -- pass write rows with audit_table='dataset' and
              -- entity_id=dataset_id. Dataset rows belong to a study
              -- via the dataset.study_id FK, so visibility is gated
              -- on that join rather than on the entity_id itself.
              OR ( a.audit_table = 'dataset' AND a.entity_id IN (
                  SELECT dataset_id FROM dataset WHERE study_id IN __IN__))
              -- Phase E.6 follow-up 2026-06-11 — include eye-cohort
              -- transitions. The row appears in BOTH the source-study
              -- AND target-study audit logs (a transition affects
              -- both) so the per-study reviewer sees the move from
              -- either side.
              OR ( a.audit_table = 'eye_cohort_transition' AND a.entity_id IN (
                  SELECT transition_id FROM eye_cohort_transition
                  WHERE source_study_id IN __IN__
                     OR target_study_id IN __IN__))
              -- 2026-09-27: auto-tick rows (129) sit on item_data but hold
              -- the ingest file's id as entity_id, so the item_data branch
              -- matched them to whichever item shared that number, in any
              -- study. They are placed by the CRF they record instead, which
              -- also covers every such row already written.
              OR ( a.audit_log_event_type_id = 129 AND a.event_crf_id IN (
                  SELECT ec.event_crf_id FROM event_crf ec
                    JOIN study_event se ON se.study_event_id = ec.study_event_id
                    JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id
                  WHERE ss.study_id IN __IN__))
              -- 2026-09-27: a file filed to or taken off a visit changes
              -- that visit's source data, so the row belongs in the visit's
              -- study. Binds, unbinds and the analysis jobs following a file
              -- record the visit in study_event_id; camera and upload binds
              -- written before that recorded it only in new_value. A row
              -- about a file that was never filed (dismissal, restore) names
              -- no visit and stays in the system log.
              OR ( a.audit_table = 'ingest_item' AND COALESCE(a.study_event_id,
                    CAST(substring(a.new_value
                         FROM '(?:^|;)study_event_id=([0-9]{1,9})(?:;|$)') AS integer)) IN (
                  SELECT se.study_event_id FROM study_event se
                    JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id
                  WHERE ss.study_id IN __IN__))
            )
            """;

    /** How many visibility IN-lists the per-study template has; each binds the visible ids. */
    static final int STUDY_SCOPED_IN_SLOTS = countOccurrences(STUDY_SCOPE_TEMPLATE, "__IN__");

    private static int countOccurrences(String text, String token) {
        int n = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) n++;
        return n;
    }

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public AuditApiController(@Qualifier("dataSource") DataSource dataSource,
                              SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    /**
     * Audit-log event-type ids written by the export endpoints. Both
     * map to the "admin" variant via {@link #variantForType}; the
     * Liquibase changesets that seed the rows are
     * {@code lc-muw-2026-06-06-audit-event-type-audit-log-export.xml}
     * and {@code lc-muw-2026-06-06-audit-event-type-discrepancy-export.xml}.
     */
    static final int AUDIT_TYPE_AUDIT_LOG_EXPORTED = 55;

    /**
     * One page of the study's audit trail, newest first, with the number of
     * rows that match. The filters narrow in the database, so a page and its
     * total cover the whole trail.
     *
     * @param itemFilter item OID: the rows about its values
     * @param fromDay    first day, {@code yyyy-MM-dd} (UTC), inclusive
     * @param toDay      last day, {@code yyyy-MM-dd} (UTC), inclusive
     * @param page       0-based page number
     * @param pageSize   rows per page, {@value #DEFAULT_PAGE_SIZE} by default,
     *                   at most {@value #MAX_PAGE_SIZE}
     */
    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = AuditPageDto.class)))
    public ResponseEntity<?> list(
            @RequestParam(value = "actor", required = false) String actorFilter,
            @RequestParam(value = "variant", required = false) String variantFilter,
            @RequestParam(value = "subjectId", required = false) String subjectIdFilter,
            @RequestParam(value = "item", required = false) String itemFilter,
            @RequestParam(value = "from", required = false) String fromDay,
            @RequestParam(value = "to", required = false) String toDay,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            HttpSession session) {

        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!mayViewStudyAudit(ub, currentStudy, currentRole)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit reading the study audit log"));
        }
        AuditFilter filter;
        try {
            filter = AuditFilter.of(actorFilter, variantFilter, subjectIdFilter, itemFilter, fromDay, toDay);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("message", BAD_DAY));
        }
        try (Connection c = dataSource.getConnection()) {
            return ResponseEntity.ok(readPage(c,
                    studyQuery(c, ub, currentStudy, currentRole, filter), page, pageSize));
        } catch (SQLException e) {
            LOG.error("Failed to load audit-log rows for study_id={}", currentStudy.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load audit log: " + e.getMessage()));
        }
    }

    /**
     * The values the study log's actor and subject filters offer: every
     * actor in the study's trail and every subject of the study, not only
     * those on the page shown.
     */
    @GetMapping("/facets")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = AuditFacetsDto.class)))
    public ResponseEntity<?> facets(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!mayViewStudyAudit(ub, currentStudy, currentRole)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit reading the study audit log"));
        }
        List<Integer> visible = visibleStudyIds(ub, currentStudy, currentRole);
        try (Connection c = dataSource.getConnection()) {
            return ResponseEntity.ok(new AuditFacetsDto(
                    actors(c, AuditLogQuery.study(STUDY_SCOPE_TEMPLATE, STUDY_SCOPED_IN_SLOTS, visible)),
                    subjectLabels(c, visible)));
        } catch (SQLException e) {
            LOG.error("Failed to load audit-log facets for study_id={}", currentStudy.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load audit log filters: " + e.getMessage()));
        }
    }

    /**
     * Phase E.6 — XLSX hand-off of the audit log. Takes the same filters as
     * {@link #list} and writes every matching row, read in batches of
     * {@value #EXPORT_BATCH} newest first, so sponsor / inspector downloads
     * hold what the view pages through. Emits one
     * {@code audit_log_event} row (type 55) per successful download
     * so the GxP audit trail records who took the egress + which
     * filters were active.
     *
     * <p>Failures during the audit-emission INSERT log at WARN but
     * never roll back the download — matches the
     * {@link SubjectExportApiController#emitExportAudit} pattern:
     * losing one audit row is annoying, refusing to ship the
     * already-rendered workbook is worse.
     */
    @GetMapping("/export.xlsx")
    @ApiResponse(responseCode = "200",
                 content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    public ResponseEntity<?> exportXlsx(
            @RequestParam(value = "actor", required = false) String actorFilter,
            @RequestParam(value = "variant", required = false) String variantFilter,
            @RequestParam(value = "subjectId", required = false) String subjectIdFilter,
            @RequestParam(value = "item", required = false) String itemFilter,
            @RequestParam(value = "from", required = false) String fromDay,
            @RequestParam(value = "to", required = false) String toDay,
            HttpSession session) {

        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!mayViewStudyAudit(ub, currentStudy, currentRole)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit reading the study audit log"));
        }
        AuditFilter filter;
        try {
            filter = AuditFilter.of(actorFilter, variantFilter, subjectIdFilter, itemFilter, fromDay, toDay);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("message", BAD_DAY));
        }

        byte[] xlsx;
        int rowCount = 0;
        try (Connection c = dataSource.getConnection();
             XlsxWorkbookBuilder b = new XlsxWorkbookBuilder("Audit log")) {
            b.writeHeader("When (UTC)", "Actor", "Variant", "Title",
                    "Subject", "Scope", "Details", "Before", "After", "Reason");
            AuditLogQuery q = studyQuery(c, ub, currentStudy, currentRole, filter);
            Integer after = null;
            while (true) {
                List<AuditRowContext.Row> batch = readBatch(c, q, after);
                for (AuditEventDto r : toDtos(batch)) {
                    b.writeRow(
                            nz(r.occurredAt()),
                            nz(r.actor()),
                            nz(r.variant()),
                            nz(r.title()),
                            nz(r.subjectId()),
                            nz(r.scope()),
                            nz(r.details()),
                            nz(r.before()),
                            nz(r.after()),
                            nz(r.reason()));
                    rowCount++;
                }
                if (batch.size() < EXPORT_BATCH) break;
                after = batch.get(batch.size() - 1).auditId();
            }
            b.autoSize();
            xlsx = b.toByteArray();
        } catch (SQLException e) {
            LOG.error("Failed to load audit-log rows for study_id={} during export",
                    currentStudy.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load audit log for export: " + e.getMessage()));
        } catch (IOException e) {
            LOG.error("Failed to render audit-export workbook for study_id={}",
                    currentStudy.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to render audit-export workbook: " + e.getMessage()));
        }

        emitExportAudit(ub.getId(), currentStudy, AUDIT_TYPE_AUDIT_LOG_EXPORTED, filter.describe(rowCount));

        String filename = "audit_" + safeOid(currentStudy.getOid()) + "_"
                + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
                + ".xlsx";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(xlsx.length);
        return new ResponseEntity<>(xlsx, headers, 200);
    }

    /**
     * Phase E hardening B (sysadmin audit UI) — system-wide audit-log
     * read.
     *
     * <p>The per-study {@link #list} handler filters rows where
     * {@code audit_log_event_type.is_user_visible = true}, which hides
     * the failure-class audit rows ({@code OPERATION_FAILED(61)} +
     * {@code JOB_FAILED(62)}) from study coordinators. This handler
     * drops that filter so sysadmins can review §11.10(e) failure
     * rows. It also drops the per-study scoping so the entire
     * institution's audit trail is visible.
     *
     * <p>Gate: sysadmin / techadmin only via
     * {@link UserAdminAuthorization#roleMayAdministerUsers}. Matches
     * the convention used by every other admin-only endpoint in this
     * controller family (UsersApiController create / disable / etc.).
     *
     * <p>Same query parameters and page shape as {@link #list} so the
     * SPA filter components can drop in without bespoke wiring.
     */
    @GetMapping("/system")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = AuditPageDto.class)))
    public ResponseEntity<?> listSystem(
            @RequestParam(value = "actor", required = false) String actorFilter,
            @RequestParam(value = "variant", required = false) String variantFilter,
            @RequestParam(value = "subjectId", required = false) String subjectIdFilter,
            @RequestParam(value = "item", required = false) String itemFilter,
            @RequestParam(value = "from", required = false) String fromDay,
            @RequestParam(value = "to", required = false) String toDay,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            HttpSession session) {

        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!UserAdminAuthorization.roleMayAdministerUsers(ub)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit system audit-log access — sysadmin only"));
        }
        AuditFilter filter;
        try {
            filter = AuditFilter.of(actorFilter, variantFilter, subjectIdFilter, itemFilter, fromDay, toDay);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("message", BAD_DAY));
        }
        try (Connection c = dataSource.getConnection()) {
            return ResponseEntity.ok(readPage(c, systemQuery(c, filter), page, pageSize));
        } catch (SQLException e) {
            LOG.error("Failed to load system-wide audit-log rows for user_id={}",
                    ub.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load system audit log: " + e.getMessage()));
        }
    }

    /** The system log's filter values: every actor in the trail and every subject label. */
    @GetMapping("/system/facets")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = AuditFacetsDto.class)))
    public ResponseEntity<?> systemFacets(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!UserAdminAuthorization.roleMayAdministerUsers(ub)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit system audit-log access — sysadmin only"));
        }
        try (Connection c = dataSource.getConnection()) {
            return ResponseEntity.ok(new AuditFacetsDto(actors(c, AuditLogQuery.all()), subjectLabels(c, null)));
        } catch (SQLException e) {
            LOG.error("Failed to load system audit-log facets for user_id={}", ub.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load system audit log filters: " + e.getMessage()));
        }
    }

    private static final String BAD_DAY = "from and to are days in the form yyyy-MM-dd";

    /** The filters of one read, as the request gave them. */
    record AuditFilter(String actor, String variant, String subject, String item, LocalDate from, LocalDate to) {

        /** @throws DateTimeParseException for a day that is not {@code yyyy-MM-dd} */
        static AuditFilter of(String actor, String variant, String subject, String item,
                              String from, String to) {
            return new AuditFilter(actor, variant, subject, item, day(from), day(to));
        }

        private static LocalDate day(String s) {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
        }

        /** The {@code new_value} of an export's audit row: the rows written and the filters applied. */
        String describe(int rowCount) {
            StringBuilder sb = new StringBuilder(describeFilters(actor, variant, subject, rowCount));
            if (item != null && !item.isBlank()) sb.append(" item=").append(item);
            if (from != null) sb.append(" from=").append(from);
            if (to != null) sb.append(" to=").append(to);
            return sb.toString();
        }
    }

    /**
     * A4 — per-site visibility. The scope embeds the visible ids as a
     * parameterised IN clause, one per audit_table branch. An empty set
     * would build an invalid {@code IN ()} clause; we fall back to the bare
     * currentStudy.id in that defensive case so the endpoint still produces
     * a result.
     */
    private List<Integer> visibleStudyIds(UserAccountBean ub, StudyBean currentStudy,
                                          StudyUserRoleBean currentRole) {
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(ub, currentStudy, currentRole);
        if (visible.isEmpty()) visible = Set.of(currentStudy.getId());
        return new ArrayList<>(visible);
    }

    /**
     * Who may read the study audit log: a system administrator, or a user
     * whose role on the active study (or on its parent, for a site) is
     * director, coordinator or monitor. Legacy parity:
     * {@code StudyAuditLogServlet.mayProceed}. The session role is tried first;
     * a user with several bindings on the study (Investigator and Data Manager,
     * say) may have the session role land on the weaker one, so every active
     * binding is walked before refusing. Fails closed.
     */
    private boolean mayViewStudyAudit(UserAccountBean ub, StudyBean currentStudy,
                                      StudyUserRoleBean currentRole) {
        if (ub.isSysAdmin()) return true;
        if (currentRole != null && roleMayViewStudyAudit(currentRole.getRole())) return true;
        try {
            List<StudyUserRoleBean> bindings =
                    new at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO(dataSource)
                            .findAllRolesByUserName(ub.getName());
            if (bindings == null) return false;
            for (StudyUserRoleBean b : bindings) {
                if (b == null || b.getRole() == null) continue;
                if (b.getStatus() == null
                        || b.getStatus().getId() != at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.AVAILABLE.getId()) continue;
                boolean onStudy = b.getStudyId() == currentStudy.getId()
                        || (currentStudy.getParentStudyId() > 0 && b.getStudyId() == currentStudy.getParentStudyId());
                if (onStudy && roleMayViewStudyAudit(b.getRole())) return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    static boolean roleMayViewStudyAudit(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role r) {
        return r == at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR
                || r == at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.COORDINATOR
                || r == at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.MONITOR;
    }

    private AuditLogQuery studyQuery(Connection c, UserAccountBean ub, StudyBean currentStudy,
                                     StudyUserRoleBean currentRole, AuditFilter f) throws SQLException {
        List<Integer> visible = visibleStudyIds(ub, currentStudy, currentRole);
        return AuditLogQuery.study(STUDY_SCOPE_TEMPLATE, STUDY_SCOPED_IN_SLOTS, visible)
                .actor(f.actor())
                .variant(f.variant())
                .subject(f.subject(), AuditLogQuery.studySubjectIds(c, f.subject(), visible))
                .item(f.item())
                .from(f.from())
                .to(f.to());
    }

    private AuditLogQuery systemQuery(Connection c, AuditFilter f) throws SQLException {
        return AuditLogQuery.all()
                .actor(f.actor())
                .variant(f.variant())
                .subject(f.subject(), AuditLogQuery.studySubjectIds(c, f.subject(), null))
                .item(f.item())
                .from(f.from())
                .to(f.to());
    }

    /** The count of matching rows and one page of them. */
    private AuditPageDto readPage(Connection c, AuditLogQuery q, Integer page, Integer pageSize)
            throws SQLException {
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int number = page == null || page < 0 ? 0 : page;
        long total;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) " + AUDIT_FROM + q.where())) {
            q.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getLong(1);
            }
        }
        List<AuditRowContext.Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                AUDIT_COLUMNS + AUDIT_FROM + q.where() + NEWEST_FIRST + " LIMIT ? OFFSET ?")) {
            int idx = q.bind(ps);
            ps.setInt(idx++, size);
            ps.setLong(idx, (long) number * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(AuditRowContext.Row.read(rs));
            }
        }
        return new AuditPageDto(total, number, size, toDtos(rows));
    }

    /**
     * The next {@value #EXPORT_BATCH} matching rows older than the row with
     * id {@code afterAuditId} (none: the newest). Rows written while the
     * export runs are newer, so they neither shift nor repeat a batch.
     */
    private List<AuditRowContext.Row> readBatch(Connection c, AuditLogQuery q, Integer afterAuditId)
            throws SQLException {
        String keyset = afterAuditId == null ? ""
                : " AND (a.audit_date, a.audit_id) < "
                        + "(SELECT x.audit_date, x.audit_id FROM audit_log_event x WHERE x.audit_id = ?)";
        List<AuditRowContext.Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                AUDIT_COLUMNS + AUDIT_FROM + q.where() + keyset + NEWEST_FIRST + " LIMIT ?")) {
            int idx = q.bind(ps);
            if (afterAuditId != null) ps.setInt(idx++, afterAuditId);
            ps.setInt(idx, EXPORT_BATCH);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(AuditRowContext.Row.read(rs));
            }
        }
        return rows;
    }

    /** Every actor name the scope's rows show, {@code system} for rows without a named user. */
    private static List<String> actors(Connection c, AuditLogQuery scope) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT CASE WHEN ua.user_name IS NULL OR btrim(ua.user_name) = '' "
                        + "THEN 'system' ELSE ua.user_name END AS actor "
                        + AUDIT_FROM + scope.where() + " ORDER BY 1")) {
            scope.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** The labels of the subjects in the given studies, or of every subject when null. */
    private static List<String> subjectLabels(Connection c, List<Integer> studyIds) throws SQLException {
        List<String> out = new ArrayList<>();
        String sql = "SELECT DISTINCT label FROM study_subject WHERE label IS NOT NULL"
                + (studyIds == null ? "" : " AND study_id IN " + buildInClause(studyIds.size()))
                + " ORDER BY label";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            if (studyIds != null) for (Integer id : studyIds) ps.setInt(idx++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    /**
     * Rows to DTOs, shared by the pages and the export: label each row by
     * what it records, resolve its subject, visit and file, and prettify its
     * values. Subject and scope lookups that existed before stay as they
     * were; the rest fills what they left empty. The filters ran in the
     * database ({@link AuditLogQuery}), asking what this method shows.
     */
    private List<AuditEventDto> toDtos(List<AuditRowContext.Row> rows) {
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
        ItemDAO itemDao = new ItemDAO(dataSource);
        Map<Integer, String> ssLabelCache = new HashMap<>();
        Map<Integer, Integer> subjectToStudySubjectCache = new HashMap<>();
        Map<Integer, EventCRFBean> ecCache = new HashMap<>();
        Map<Integer, ItemDataBean> itemDataCache = new HashMap<>();
        Map<Integer, ItemBean> itemCache = new HashMap<>();
        AuditRowContext context;
        try {
            context = AuditRowContext.load(dataSource, rows);
        } catch (SQLException e) {
            // The rows themselves loaded; show them as before rather than
            // failing the page over the extra context.
            LOG.warn("Audit log: visit / file / type context unavailable: {}", e.getMessage());
            context = AuditRowContext.empty();
        }

        List<AuditEventDto> out = new ArrayList<>();
        for (AuditRowContext.Row r : rows) {
            String auditTable = r.auditTable();
            int typeId = r.typeId();
            // A row written under an id that meant something else at the
            // time reads as the type it records (AuditRowLabels).
            int effectiveType = AuditRowLabels.effectiveType(typeId, auditTable,
                    r.entityName(), r.oldValue(), r.newValue());
            String variant = AuditRowLabels.variant(effectiveType, auditTable, r.reason());
            String actor = (r.userName() == null || r.userName().isBlank()) ? "system" : r.userName();
            String title = titleFor(r, effectiveType, context);

            String subjectLabel = resolveSubjectLabel(
                    auditTable, r.entityId(), r.eventCrfId(),
                    ssDao, ecDao, ssLabelCache, subjectToStudySubjectCache, ecCache);
            String scope;
            if (typeId == AuditTypeIds.IMAGE_PERFORMED_AUTOTICK) {
                // The auto-tick row sits on item_data but holds the file's id
                // where the item_data id belongs; its entity name is the item.
                scope = blankToNull(r.entityName());
            } else {
                scope = resolveScope(auditTable, r.entityId(), r.eventCrfId(),
                        itemDataDao, itemDao, itemDataCache, itemCache);
            }
            // Visit rows and ingest rows name a visit; show whose and which.
            AuditRowContext.Visit visit = context.visit(r.visitId());
            if (visit != null) {
                if (subjectLabel == null) subjectLabel = visit.subjectLabel();
                if (scope == null) scope = visit.label();
            }


            Timestamp ts = r.auditDate();
            String occurredAt = ts == null ? null
                    : ts.toInstant().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toInstant().toString();

            String before;
            String after;
            if (AuditRowLabels.isFailure(typeId)) {
                // The error is packed as class|message|request id.
                before = null;
                after = AuditRowLabels.formatFailure(r.newValue());
            } else {
                // A5 — prettify the raw before/after columns, keyed on the
                // row's column marker (entity_name).
                before = blankToNull(prettifyValue(typeId, auditTable, r.entityName(), r.oldValue()));
                after = blankToNull(prettifyValue(typeId, auditTable, r.entityName(), r.newValue()));
            }

            out.add(new AuditEventDto(
                    String.valueOf(r.auditId()),
                    occurredAt,
                    variant,
                    actor,
                    /* actorRole */ null,
                    title,
                    subjectLabel,
                    scope,
                    detailsFor(r, context),
                    before,
                    after,
                    blankToNull(r.reason())));
        }
        return out;
    }

    /**
     * The row's title: its own type's display name, unless it records another
     * type ({@link AuditRowLabels#effectiveType}); then that type's.
     */
    private static String titleFor(AuditRowContext.Row r, int effectiveType, AuditRowContext context) {
        if (effectiveType != r.typeId()) {
            String recorded = context.typeTitle(effectiveType);
            if (recorded != null) return recorded;
        }
        if (r.typeDisplay() != null && !r.typeDisplay().isBlank()) return r.typeDisplay();
        if (r.typeName() != null && !r.typeName().isBlank()) return r.typeName();
        return "Audit event #" + r.auditId();
    }

    /**
     * The short line shown next to the title.
     *
     * <ul>
     *   <li>Study, user and dataset rows: the entity name, which holds the
     *       changed column or the export label (Phase E.6); configuration
     *       rows: the key of the setting that changed (2026-09-30).</li>
     *   <li>Failures: the operation that failed.</li>
     *   <li>Ingest rows: the file's reference. Rows written before the
     *       reference was recorded hold a bare column marker; for those it is
     *       read from the file while the file exists.</li>
     * </ul>
     */
    private static String detailsFor(AuditRowContext.Row r, AuditRowContext context) {
        String entityName = r.entityName();
        if (AuditRowLabels.isFailure(r.typeId())) return blankToNull(entityName);
        if (r.on("ingest_item")) {
            if (!AuditRowLabels.isBareMarker(entityName)) return entityName.trim();
            return r.entityId() > 0 ? context.file(r.entityId()) : null;
        }
        if ((r.on("study") || r.on("user_account") || r.on("dataset") || r.on("configuration"))
                && entityName != null && !entityName.isBlank()) {
            return entityName;
        }
        return null;
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    static String variantForType(int typeId, String reasonForChange) {
        if (reasonForChange != null && !reasonForChange.isBlank()) {
            return "reason-for-change";
        }
        return switch (typeId) {
            // A reason-for-change note carries its reason (140, since
            // 2026-09-27); rows written before read as 140 too.
            case 140 -> "reason-for-change";
            case 31 -> "signed";
            // A CRF signature removed when the CRF moved to another version (144).
            case AuditTypeIds.EVENT_CRF_SIGNATURE_REMOVED -> "signed";
            case 32 -> "sdv";
            // Subject-group-map lifecycle (types 28 + 29 — "added to
            // group" + "moved between groups"). Phase E.5 #2 follow-up:
            // promoted out of the generic "admin" bucket so the SPA can
            // render the before/after group labels (already populated
            // in audit_log_event.{old_value,new_value} by the
            // subject_group_map trigger).
            case 28, 29 -> "subject-group-change";
            // Subject / study-subject / EDC lifecycle, plus user-profile
            // (50, Phase E.5 follow-up), study-identity (51, Phase E.6),
            // dataset-export (52), subject-data-export (53),
            // study-parameters (54, Phase E.6 study-params — per-handle
            // audit fan-out from PUT /studies/{oid}/parameters),
            // audit-log-export (55, Phase E.6 — XLSX hand-off of the
            // audit trail) and discrepancy-log-export (56, Phase E.6 —
            // CSV hand-off of the discrepancy list) edits — all
            // administrative actions surface under the existing "Admin"
            // filter rather than the data-stream bucket so operators
            // reviewing the audit trail can pivot by intent.
            case 2, 3, 4, 5, 6, 7, 9, 27, 33, 50, 51, 52, 53, 54, 55, 56,
            // PR #186 gap-coverage admin actions: CRF library (63-65),
            // user lifecycle (66-69), event definition create (70).
                 63, 64, 65, 66, 67, 68, 69, 70,
            // Modality CRUD (58-60) — configuration, admin-bucket.
                 58, 59, 60,
            // Operation / job failures (61-62) — sysadmin-only via
            // is_user_visible=false, but route to "admin" when surfaced.
                 61, 62,
            // CRF-library + version lifecycle (75-76), version-migration
            // (79), rule CRUD (81-89), site + study + study-status
            // (90-93), event-definition lifecycle + field updates (95-97),
            // group-class lifecycle + field updates (98-99). Phase
            // audit-unification.
                 75, 76, 79, 81, 82, 83, 84, 85, 86, 87, 88, 89,
                 90, 91, 92, 93, 95, 96, 97, 98, 99,
            // User-account stream: login failed (101, hidden), login
            // (102), legacy password (103), admin action (104), generic
            // helper (105). Failed-login is hidden but the variant routes
            // to "admin" for the sysadmin view's filter chips.
                 101, 102, 103, 104, 105,
            // Extract-job execution (106-107). Backfill catch-all (108,
            // hidden) routes to admin for the sysadmin view.
                 106, 107, 108,
            // Password-policy and lockout settings (145, 2026-09-30).
                 145,
            // CRF name / description edit (142) and a batch move of event
            // CRFs to another CRF version (143, one row per run).
                 AuditTypeIds.CRF_FIELD_UPDATED, AuditTypeIds.EVENT_CRF_BATCH_MIGRATION -> "admin";
            // Item-data + event-crf + study-event lifecycle — actual
            // data movement.
            case 1, 8, 10, 11, 12, 13, 14, 15, 16,
                 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
                 30, 35, 40, 41,
            // CRF reopened (138) and restored (139), a dismissed file
            // restored (137) — 2026-09-27.
                 137, 138, 139,
            // An event CRF removed with its CRF or with its CRF version
            // (190-191); its restore is 139.
                 AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF, AuditTypeIds.EVENT_CRF_REMOVED_WITH_VERSION,
            // Eye-cohort transition (57) — per-subject clinical event,
            // not admin config. Discrepancy-note threading + create
            // (71-74) and the subject-demographics update (100) also
            // belong with data-entry; the event_crf start (77),
            // SDV unverification (78), bulk-import attempt (80),
            // and study-event update (94) ride the same data-stream.
                 57, 71, 72, 73, 74, 77, 78, 80, 94, 100 -> "data";
            default -> "data";
        };
    }

    private String resolveSubjectLabel(
            String auditTable, int entityId, int eventCrfId,
            StudySubjectDAO ssDao, EventCRFDAO ecDao,
            Map<Integer, String> ssLabelCache,
            Map<Integer, Integer> subjectToSs,
            Map<Integer, EventCRFBean> ecCache) {

        Integer studySubjectId = null;
        if ("study_subject".equalsIgnoreCase(auditTable) && entityId > 0) {
            studySubjectId = entityId;
        } else if ("subject".equalsIgnoreCase(auditTable) && entityId > 0) {
            studySubjectId = subjectToSs.computeIfAbsent(entityId,
                    id -> studySubjectFromSubjectId(ssDao, id));
        } else if ("event_crf".equalsIgnoreCase(auditTable) && entityId > 0) {
            EventCRFBean ec = ecCache.computeIfAbsent(entityId,
                    id -> (EventCRFBean) ecDao.findByPK(id));
            if (ec != null && ec.getId() > 0) studySubjectId = ec.getStudySubjectId();
        } else if ("item_data".equalsIgnoreCase(auditTable) && eventCrfId > 0) {
            EventCRFBean ec = ecCache.computeIfAbsent(eventCrfId,
                    id -> (EventCRFBean) ecDao.findByPK(id));
            if (ec != null && ec.getId() > 0) studySubjectId = ec.getStudySubjectId();
        }
        if (studySubjectId == null || studySubjectId <= 0) return null;
        return ssLabelCache.computeIfAbsent(studySubjectId, id -> {
            StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(id);
            return (ss != null && ss.getId() > 0) ? ss.getLabel() : null;
        });
    }

    private static Integer studySubjectFromSubjectId(StudySubjectDAO ssDao, int subjectId) {
        ArrayList<StudySubjectBean> rows = ssDao.findAllBySubjectId(subjectId);
        if (rows == null || rows.isEmpty()) return null;
        return rows.get(0).getId();
    }

    private String resolveScope(
            String auditTable, int entityId, int eventCrfId,
            ItemDataDAO itemDataDao, ItemDAO itemDao,
            Map<Integer, ItemDataBean> itemDataCache, Map<Integer, ItemBean> itemCache) {
        if ("item_data".equalsIgnoreCase(auditTable) && entityId > 0) {
            ItemDataBean idb = itemDataCache.computeIfAbsent(entityId,
                    id -> (ItemDataBean) itemDataDao.findByPK(id));
            if (idb != null && idb.getId() > 0) {
                ItemBean item = itemCache.computeIfAbsent(idb.getItemId(),
                        id -> (ItemBean) itemDao.findByPK(id));
                if (item != null && item.getId() > 0) return item.getOid();
            }
        }
        if ("event_crf".equalsIgnoreCase(auditTable) && entityId > 0) {
            return "event_crf:" + entityId;
        }
        if (eventCrfId > 0 && !"event_crf".equalsIgnoreCase(auditTable)) {
            return "event_crf:" + eventCrfId;
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Build a SQL {@code IN (?, ?, …)} clause with {@code n}
     * placeholders. Caller is responsible for binding {@code n}
     * values in order.
     */
    static String buildInClause(int n) {
        if (n <= 0) return "(NULL)"; // defensive — caller pre-clamps
        StringBuilder sb = new StringBuilder("(?");
        for (int i = 1; i < n; i++) sb.append(",?");
        sb.append(")");
        return sb.toString();
    }

    /**
     * A5 — prettify a raw {@code audit_log_event.{old,new}_value}
     * value for the SPA's diff card.
     *
     * <p>Two passes:
     * <ol>
     *   <li>Status-code mapping. Keyed on {@code (audit_table,
     *       marker)} where {@code marker} is the row's column marker,
     *       {@code entity_name}, as the trigger writes it. Numeric
     *       status ids ({@code "1"}, {@code "8"}, etc.) become human
     *       labels ({@code "Available"}, {@code "Signed"}, etc.).</li>
     *   <li>Boolean prettification. Raw {@code "TRUE"}/{@code "FALSE"}
     *       (postgres-trigger output) become {@code "yes"}/{@code "no"}.</li>
     * </ol>
     *
     * <p>Anything outside both mapping tables falls through unchanged
     * (e.g. ISO dates, free-text fields).
     */
    static String prettifyValue(String auditTable, String marker, String raw) {
        if (raw == null) return null;
        String mapped = mapStatusCode(auditTable, marker, raw);
        if (mapped != null) return mapped;
        // Boolean prettification — strip whitespace before comparing
        // because some triggers emit padded strings.
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("TRUE")) return "yes";
        if (trimmed.equalsIgnoreCase("FALSE")) return "no";
        return raw;
    }

    /**
     * As {@link #prettifyValue(String, String, String)}, knowing the row's
     * type. A visit's {@code Status} marker means two different status sets:
     * the heritage trigger writes the removal (23) and restore (35) of a visit
     * with its entity status, and every other visit status change with its
     * subject-event status. A newly scheduled visit's previous status is
     * written as {@code 0}, which means none.
     *
     * <p>The marker is the row's {@code entity_name}. Until 2026-09-27 the
     * callers passed the type's name here, which never equals
     * {@code "Status"}, so visit, CRF and subject status changes were shown
     * as raw numbers.
     */
    static String prettifyValue(int typeId, String auditTable, String marker, String raw) {
        if (raw == null) return null;
        if ("study_event".equalsIgnoreCase(auditTable) && marker != null
                && "Status".equalsIgnoreCase(marker.trim())) {
            String t = raw.trim();
            if (typeId == 23 || typeId == 35) {
                String entityStatus = mapEntityStatus(t);
                if (entityStatus != null) return entityStatus;
            } else if ("0".equals(t)) {
                return "";
            }
        }
        return prettifyValue(auditTable, marker, raw);
    }

    /**
     * Map a raw status-id string to its human label per the
     * (audit_table, column marker) pair. Returns {@code null} when no
     * mapping applies — caller falls back to the raw value.
     *
     * <p>Status id sets:
     * <ul>
     *   <li>{@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status}
     *       — for {@code study_subject.Status} + {@code event_crf.Status}:
     *       1→Available, 2→Unavailable, 5→Removed, 8→Signed.</li>
     *   <li>{@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus}
     *       — for {@code study_event.Status}: 1→Scheduled,
     *       4→Completed, 8→Signed.</li>
     *   <li>EventCRF SDV Status — {@code "TRUE"} / {@code "FALSE"}
     *       → "SDV complete" / "SDV pending".</li>
     * </ul>
     */
    static String mapStatusCode(String auditTable, String marker, String raw) {
        if (auditTable == null || marker == null || raw == null) return null;
        String t = marker.trim();
        // EventCRF SDV Status — true/false mapping rather than numeric.
        if ("event_crf".equalsIgnoreCase(auditTable) && "EventCRF SDV Status".equalsIgnoreCase(t)) {
            String r = raw.trim();
            if (r.equalsIgnoreCase("TRUE")) return "SDV complete";
            if (r.equalsIgnoreCase("FALSE")) return "SDV pending";
        }
        if (("study_subject".equalsIgnoreCase(auditTable)
                || "event_crf".equalsIgnoreCase(auditTable))
                && "Status".equalsIgnoreCase(t)) {
            return mapEntityStatus(raw);
        }
        if ("study_event".equalsIgnoreCase(auditTable) && "Status".equalsIgnoreCase(t)) {
            return mapSubjectEventStatus(raw);
        }
        return null;
    }

    private static String mapEntityStatus(String raw) {
        String trimmed = raw.trim();
        return switch (trimmed) {
            case "1" -> "Available";
            case "2" -> "Unavailable";
            case "3" -> "Private";
            case "4" -> "Pending";
            case "5" -> "Removed";
            case "6" -> "Locked";
            case "7" -> "Auto-removed";
            case "8" -> "Signed";
            default -> null;
        };
    }

    private static String mapSubjectEventStatus(String raw) {
        String trimmed = raw.trim();
        return switch (trimmed) {
            case "1" -> "Scheduled";
            case "2" -> "Not Scheduled";
            case "3" -> "Data Entry Started";
            case "4" -> "Completed";
            case "5" -> "Stopped";
            case "6" -> "Skipped";
            case "7" -> "Locked";
            case "8" -> "Signed";
            default -> null;
        };
    }

    /* ------------------------------------------------------------------ */
    /* Export helpers                                                     */
    /* ------------------------------------------------------------------ */

    /** Null-safe coalesce for {@link XlsxWorkbookBuilder} string cells. */
    static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Render the safe StudyOID slug used in the export filename — alphanumerics
     * + dash + underscore only so the filename is portable across the
     * Windows / macOS / Linux clients sponsors typically use.
     */
    static String safeOid(String oid) {
        if (oid == null || oid.isBlank()) return "study";
        StringBuilder sb = new StringBuilder(oid.length());
        for (int i = 0; i < oid.length(); i++) {
            char c = oid.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * Build a one-line filter summary stored in {@code audit_log_event.new_value}
     * so the audit-trail row records what the operator selected when they
     * pulled the export (matches the SPA filter chip set).
     */
    static String describeFilters(String actor, String variant, String subjectId, int rowCount) {
        StringBuilder sb = new StringBuilder("rows=").append(rowCount);
        if (actor != null && !actor.isBlank()) sb.append(" actor=").append(actor);
        if (variant != null && !variant.isBlank()) sb.append(" variant=").append(variant);
        if (subjectId != null && !subjectId.isBlank()) sb.append(" subjectId=").append(subjectId);
        return sb.toString();
    }

    /**
     * Phase E.6 — best-effort INSERT into {@code audit_log_event} so the
     * GxP audit trail captures the egress event. Mirrors
     * {@code SubjectExportApiController.emitExportAudit} — failures log at
     * WARN but never roll back the already-rendered download.
     *
     * <p>Row shape:
     * <ul>
     *   <li>{@code audit_table = 'study'}, {@code entity_id = study.id} so
     *       the {@link #STUDY_SCOPED_AUDIT_SQL_TEMPLATE} study-branch joins
     *       it back to the active study with no extra plumbing.</li>
     *   <li>{@code entity_name} carries the study OID for at-a-glance
     *       context in the SPA timeline.</li>
     *   <li>{@code new_value} carries the filter summary from
     *       {@link #describeFilters}.</li>
     * </ul>
     */
    private void emitExportAudit(int userId, StudyBean study, int typeId, String filterSummary) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study', ?, ?, ?, ?)")) {
            ps.setInt(1, typeId);
            ps.setInt(2, userId);
            ps.setInt(3, study.getId());
            ps.setString(4, study.getOid() == null ? "" : study.getOid());
            ps.setString(5, "");
            ps.setString(6, filterSummary == null ? "" : filterSummary);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write audit-export audit row for study_id={} type={}: {}",
                    study.getId(), typeId, e.getMessage());
        }
    }
}
