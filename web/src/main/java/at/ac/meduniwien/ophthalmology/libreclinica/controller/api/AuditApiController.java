/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

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
 *   <li>any row with non-blank {@code reason_for_change} → {@code
 *       reason-for-change} (overrides the type mapping)</li>
 * </ul>
 *
 * <p>Server-side filters narrow by actor / variant / subjectId so
 * the response stays small on noisy studies. The SPA additionally
 * runs the same filters client-side once loaded.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Study-scoped audit-log query.")
public class AuditApiController {

    private static final Logger LOG = LoggerFactory.getLogger(AuditApiController.class);

    /**
     * Template for the single-pass SQL that returns every audit row
     * scoped to a set of study_ids. The literal {@code __IN__} token
     * is replaced at call time with a placeholder list of the right
     * arity ({@code (?, ?, …)} repeated five times — one IN per
     * audit-table branch). Until A4, the SQL had a literal {@code = ?}
     * per branch; A4 generalises to per-site visibility — Monitor with
     * a single site grant under a multi-site study now sees only that
     * site's rows.
     */
    private static final String STUDY_SCOPED_AUDIT_SQL_TEMPLATE = """
            SELECT
              a.audit_id, a.audit_date, a.audit_table, a.entity_id,
              a.reason_for_change, a.audit_log_event_type_id,
              a.old_value, a.new_value, a.event_crf_id, a.study_event_id,
              a.user_id, ua.user_name, alet.name AS type_name,
              alet.display_name AS type_display_name
            FROM audit_log_event a
            LEFT JOIN user_account ua ON ua.user_id = a.user_id
            LEFT JOIN audit_log_event_type alet
              ON alet.audit_log_event_type_id = a.audit_log_event_type_id
            WHERE
              ( a.audit_table = 'item_data' AND a.entity_id IN (
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
            ORDER BY a.audit_date DESC, a.audit_id DESC
            LIMIT 500
            """;

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public AuditApiController(@Qualifier("dataSource") DataSource dataSource,
                              SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = AuditEventDto.class)))
    public ResponseEntity<?> list(
            @RequestParam(value = "actor", required = false) String actorFilter,
            @RequestParam(value = "variant", required = false) String variantFilter,
            @RequestParam(value = "subjectId", required = false) String subjectIdFilter,
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
        int studyId = currentStudy.getId();

        // A4 — per-site visibility. The audit SQL embeds the visible
        // ids as a parameterised IN clause (one of {n placeholders}
        // per audit_table branch, five branches → 5n binds total). An
        // empty visible set would build an invalid `IN ()` clause; we
        // fall back to the bare currentStudy.id in that defensive
        // case so the endpoint still produces a result.
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        if (visibleStudyIds.isEmpty()) visibleStudyIds = Set.of(studyId);
        String inClause = buildInClause(visibleStudyIds.size());
        String sql = STUDY_SCOPED_AUDIT_SQL_TEMPLATE.replace("__IN__", inClause);

        // Caches for the per-row subject/item resolution.
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
        ItemDAO itemDao = new ItemDAO(dataSource);
        Map<Integer, String> ssLabelCache = new HashMap<>();
        Map<Integer, Integer> subjectToStudySubjectCache = new HashMap<>();
        Map<Integer, EventCRFBean> ecCache = new HashMap<>();
        Map<Integer, ItemDataBean> itemDataCache = new HashMap<>();
        Map<Integer, ItemBean> itemCache = new HashMap<>();

        List<AuditEventDto> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            // 5 IN clauses × n ids each.
            int bindIdx = 1;
            for (int branch = 0; branch < 5; branch++) {
                for (Integer sid : visibleStudyIds) {
                    ps.setInt(bindIdx++, sid);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer auditId = rs.getInt("audit_id");
                    Timestamp ts = rs.getTimestamp("audit_date");
                    String auditTable = rs.getString("audit_table");
                    int entityId = rs.getInt("entity_id");
                    int eventCrfId = rs.getInt("event_crf_id");
                    int typeId = rs.getInt("audit_log_event_type_id");
                    String oldVal = rs.getString("old_value");
                    String newVal = rs.getString("new_value");
                    String reason = rs.getString("reason_for_change");
                    String userName = rs.getString("user_name");
                    String typeName = rs.getString("type_name");
                    String typeDisplay = rs.getString("type_display_name");

                    String variant = variantForType(typeId, reason);
                    String actor = (userName == null || userName.isBlank()) ? "system" : userName;
                    // A5 — prefer the curated display name when available;
                    // fall back to the legacy `name` column (lowercase
                    // snake-case keys) for any type without a display row.
                    String title;
                    if (typeDisplay != null && !typeDisplay.isBlank()) {
                        title = typeDisplay;
                    } else if (typeName != null && !typeName.isBlank()) {
                        title = typeName;
                    } else {
                        title = "Audit event #" + auditId;
                    }

                    String subjectLabel = resolveSubjectLabel(
                            auditTable, entityId, eventCrfId,
                            ssDao, ecDao, ssLabelCache, subjectToStudySubjectCache, ecCache);
                    String scope = resolveScope(
                            auditTable, entityId, eventCrfId,
                            itemDataDao, itemDao, itemDataCache, itemCache);

                    // Apply server-side filters before serialising.
                    if (actorFilter != null && !actorFilter.isBlank()
                            && !actorFilter.equalsIgnoreCase(actor)) continue;
                    if (variantFilter != null && !variantFilter.isBlank()
                            && !variantFilter.equalsIgnoreCase(variant)) continue;
                    if (subjectIdFilter != null && !subjectIdFilter.isBlank()
                            && (subjectLabel == null || !subjectIdFilter.equalsIgnoreCase(subjectLabel)))
                        continue;

                    String occurredAt = ts == null ? null
                            : ts.toInstant().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toInstant().toString();

                    // A5 — prettify the raw before/after columns.
                    // Status-code mapping is keyed on (audit_table,
                    // type_name) because entity_name (the audit row's
                    // column-name marker) isn't a separate DB column —
                    // the trigger packs it into `name` for legacy rows
                    // and into the type_name we already fetched.
                    String prettyOld = prettifyValue(auditTable, typeName, oldVal);
                    String prettyNew = prettifyValue(auditTable, typeName, newVal);

                    out.add(new AuditEventDto(
                            String.valueOf(auditId),
                            occurredAt,
                            variant,
                            actor,
                            /* actorRole */ null,
                            title,
                            subjectLabel,
                            scope,
                            /* details */ null,
                            blankToNull(prettyOld),
                            blankToNull(prettyNew),
                            blankToNull(reason)));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to load audit-log rows for study_id={}", studyId, e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load audit log: " + e.getMessage()));
        }

        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    private static String variantForType(int typeId, String reasonForChange) {
        if (reasonForChange != null && !reasonForChange.isBlank()) {
            return "reason-for-change";
        }
        return switch (typeId) {
            case 31 -> "signed";
            case 32 -> "sdv";
            // Subject-group-map lifecycle (types 28 + 29 — "added to
            // group" + "moved between groups"). Phase E.5 #2 follow-up:
            // promoted out of the generic "admin" bucket so the SPA can
            // render the before/after group labels (already populated
            // in audit_log_event.{old_value,new_value} by the
            // subject_group_map trigger).
            case 28, 29 -> "subject-group-change";
            // Subject / study-subject / EDC lifecycle.
            case 2, 3, 4, 5, 6, 7, 9, 27, 33 -> "admin";
            // Item-data + event-crf + study-event lifecycle — actual
            // data movement.
            case 1, 8, 10, 11, 12, 13, 14, 15, 16,
                 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
                 30, 35, 40, 41 -> "data";
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
     *       type_name)} where {@code type_name} is the legacy
     *       lowercase snake-case key set by the trigger. Numeric
     *       status ids ({@code "1"}, {@code "8"}, etc.) become human
     *       labels ({@code "Available"}, {@code "Signed"}, etc.).</li>
     *   <li>Boolean prettification. Raw {@code "TRUE"}/{@code "FALSE"}
     *       (postgres-trigger output) become {@code "yes"}/{@code "no"}.</li>
     * </ol>
     *
     * <p>Anything outside both mapping tables falls through unchanged
     * (e.g. ISO dates, free-text fields).
     */
    static String prettifyValue(String auditTable, String typeName, String raw) {
        if (raw == null) return null;
        String mapped = mapStatusCode(auditTable, typeName, raw);
        if (mapped != null) return mapped;
        // Boolean prettification — strip whitespace before comparing
        // because some triggers emit padded strings.
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("TRUE")) return "yes";
        if (trimmed.equalsIgnoreCase("FALSE")) return "no";
        return raw;
    }

    /**
     * Map a raw status-id string to its human label per the
     * (audit_table, type_name) pair. Returns {@code null} when no
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
    static String mapStatusCode(String auditTable, String typeName, String raw) {
        if (auditTable == null || typeName == null || raw == null) return null;
        String t = typeName.trim();
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
}
