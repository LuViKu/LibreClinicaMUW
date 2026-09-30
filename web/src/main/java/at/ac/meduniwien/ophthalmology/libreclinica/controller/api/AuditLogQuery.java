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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The {@code WHERE} clause of one audit-log read, with its binds: the scope
 * (a study's rows, or every row) and the filters the audit views offer, so
 * that the database filters, counts and pages the whole trail instead of the
 * newest rows only.
 *
 * <p>Each filter asks what the row's DTO would show
 * ({@code AuditApiController.toDtos}), so the rows a filter returns are the
 * rows the view labels that way:
 * <ul>
 *   <li><b>actor</b> — the user name, or {@code system} for a row with no
 *       (named) user; case-insensitive.</li>
 *   <li><b>variant</b> — {@link AuditRowLabels#variant} of
 *       {@link AuditRowLabels#effectiveType}, both written out in SQL below;
 *       the type ids of each variant are read off
 *       {@link AuditApiController#variantForType}.</li>
 *   <li><b>subject</b> — the rows whose subject the view names by that
 *       label: the subject's own rows, its event CRFs' and values' rows, and
 *       rows about one of its visits.</li>
 *   <li><b>item</b> — the rows about a value of that item (the scope the
 *       view shows for them).</li>
 *   <li><b>from / to</b> — days, both inclusive, in UTC like the times the
 *       view shows.</li>
 * </ul>
 */
final class AuditLogQuery {

    /** {@link AuditRowLabels#effectiveType} as SQL. */
    static final String EFFECTIVE_TYPE = """
            (CASE
              WHEN a.audit_log_event_type_id = 11 AND lower(a.audit_table) = 'event_crf'
                   AND lower(btrim(a.entity_name)) = 'date_completed' THEN 138
              WHEN a.audit_log_event_type_id = 11 AND lower(a.audit_table) = 'event_crf'
                   AND lower(btrim(a.entity_name)) = 'status_id' THEN 139
              WHEN a.audit_log_event_type_id = 27 AND lower(a.audit_table) = 'item_data' THEN 140
              WHEN a.audit_log_event_type_id = 128 AND upper(btrim(a.old_value)) LIKE 'DISMISSED%'
                   AND upper(btrim(a.new_value)) LIKE 'UNBOUND%' THEN 137
              ELSE COALESCE(a.audit_log_event_type_id, 0)
            END)""";

    /**
     * A reason puts a row in {@code reason-for-change}, except a row about an
     * ingested file ({@link AuditRowLabels#variant}).
     */
    static final String HAS_REASON = "(btrim(COALESCE(a.reason_for_change, '')) <> '' "
            + "AND lower(COALESCE(a.audit_table, '')) <> 'ingest_item')";

    /**
     * Upper bound of the type ids read off {@code variantForType}. Type ids
     * are allocated in sequence (the highest is in the low hundreds); any id
     * the switch does not name reads as {@code data}.
     */
    static final int TYPE_ID_BOUND = 4096;

    /** Type ids of each variant other than data, in id order. */
    private static final Map<String, List<Integer>> VARIANT_TYPE_IDS = variantTypeIds();

    private final StringBuilder where = new StringBuilder();
    private final List<Object> binds = new ArrayList<>();

    private static Map<String, List<Integer>> variantTypeIds() {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (int id = 0; id < TYPE_ID_BOUND; id++) {
            String variant = AuditApiController.variantForType(id, null);
            if (!"data".equals(variant)) out.computeIfAbsent(variant, v -> new ArrayList<>()).add(id);
        }
        return out;
    }

    /** Every id whose variant is not data. */
    private static List<Integer> nonDataTypeIds() {
        List<Integer> all = new ArrayList<>();
        VARIANT_TYPE_IDS.values().forEach(all::addAll);
        return all;
    }

    /** The rows of a study: {@code scope} with each {@code __IN__} bound to the visible study ids. */
    static AuditLogQuery study(String scopeTemplate, int inSlots, Collection<Integer> visibleStudyIds) {
        AuditLogQuery q = new AuditLogQuery();
        q.where.append(" WHERE ").append(scopeTemplate.replace("__IN__",
                AuditApiController.buildInClause(visibleStudyIds.size())));
        for (int slot = 0; slot < inSlots; slot++) q.binds.addAll(visibleStudyIds);
        return q;
    }

    /** Every row (the system log). */
    static AuditLogQuery all() {
        AuditLogQuery q = new AuditLogQuery();
        q.where.append(" WHERE TRUE");
        return q;
    }

    AuditLogQuery actor(String actor) {
        if (isBlank(actor)) return this;
        if ("system".equalsIgnoreCase(actor.trim())) {
            where.append(" AND (ua.user_name IS NULL OR btrim(ua.user_name) = '' OR lower(ua.user_name) = lower(?))");
        } else {
            where.append(" AND lower(ua.user_name) = lower(?)");
        }
        binds.add(actor.trim());
        return this;
    }

    AuditLogQuery variant(String variant) {
        if (isBlank(variant)) return this;
        String v = variant.trim().toLowerCase(java.util.Locale.ROOT);
        if ("data".equals(v)) {
            where.append(" AND NOT ").append(HAS_REASON)
                 .append(" AND ").append(EFFECTIVE_TYPE).append(" NOT IN ").append(ids(nonDataTypeIds()));
        } else if ("reason-for-change".equals(v)) {
            where.append(" AND (").append(HAS_REASON)
                 .append(" OR ").append(EFFECTIVE_TYPE).append(" IN ").append(ids(VARIANT_TYPE_IDS.get(v)))
                 .append(')');
        } else if (VARIANT_TYPE_IDS.containsKey(v)) {
            where.append(" AND NOT ").append(HAS_REASON)
                 .append(" AND ").append(EFFECTIVE_TYPE).append(" IN ").append(ids(VARIANT_TYPE_IDS.get(v)));
        } else {
            // A variant no row carries, as the view's own filter found none.
            where.append(" AND FALSE");
        }
        return this;
    }

    /**
     * @param studySubjectIds the study subjects the label names, in the
     *                        scope's studies; none makes the read empty
     */
    AuditLogQuery subject(String label, List<Integer> studySubjectIds) {
        if (isBlank(label)) return this;
        if (studySubjectIds.isEmpty()) {
            where.append(" AND FALSE");
            return this;
        }
        String ss = ids(studySubjectIds);
        String visits = "(SELECT se.study_event_id FROM study_event se WHERE se.study_subject_id IN " + ss + ")";
        String eventCrfs = "(SELECT ec.event_crf_id FROM event_crf ec WHERE ec.study_subject_id IN " + ss + ")";
        where.append(" AND (")
             .append("(a.audit_table = 'study_subject' AND a.entity_id IN ").append(ss).append(')')
             .append(" OR (a.audit_table = 'subject' AND a.entity_id IN (SELECT s.subject_id FROM study_subject s ")
             .append("WHERE s.study_subject_id IN ").append(ss).append("))")
             .append(" OR (a.audit_table = 'event_crf' AND a.entity_id IN ").append(eventCrfs).append(')')
             .append(" OR (a.audit_table = 'item_data' AND a.event_crf_id IN ").append(eventCrfs).append(')')
             // A row about one of the subject's visits (AuditRowContext.Row#visitId).
             .append(" OR (a.audit_table = 'study_event' AND a.entity_id IN ").append(visits).append(')')
             .append(" OR (a.audit_table IS DISTINCT FROM 'study_event' AND a.study_event_id IN ").append(visits).append(')')
             .append(" OR (a.audit_table = 'ingest_item' AND COALESCE(NULLIF(a.study_event_id, 0),")
             .append(" CAST(substring(a.new_value FROM '(?:^|;)study_event_id=([0-9]{1,9})') AS integer),")
             .append(" CAST(substring(a.old_value FROM '(?:^|;)study_event_id=([0-9]{1,9})') AS integer)) IN ")
             .append(visits).append(')')
             .append(')');
        return this;
    }

    /** Rows about a value of the item with this OID. */
    AuditLogQuery item(String itemOid) {
        if (isBlank(itemOid)) return this;
        where.append(" AND ((a.audit_table = 'item_data' AND a.audit_log_event_type_id IS DISTINCT FROM 129")
             .append(" AND a.entity_id IN (SELECT idt.item_data_id FROM item_data idt")
             .append(" JOIN item i ON i.item_id = idt.item_id WHERE lower(i.oc_oid) = lower(?)))")
             // An auto-tick row names its item in entity_name and holds the file's id.
             .append(" OR (a.audit_log_event_type_id = 129 AND lower(btrim(a.entity_name)) = lower(?)))");
        binds.add(itemOid.trim());
        binds.add(itemOid.trim());
        return this;
    }

    AuditLogQuery from(LocalDate day) {
        if (day == null) return this;
        where.append(" AND a.audit_date >= ?");
        binds.add(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()));
        return this;
    }

    AuditLogQuery to(LocalDate day) {
        if (day == null) return this;
        where.append(" AND a.audit_date < ?");
        binds.add(Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        return this;
    }

    /** Rows older than a row already read, in the newest-first order. */
    AuditLogQuery before(Timestamp auditDate, int auditId) {
        where.append(" AND (a.audit_date, a.audit_id) < (?, ?)");
        binds.add(auditDate);
        binds.add(auditId);
        return this;
    }

    String where() {
        return where.toString();
    }

    /** Bind the clause's values from index 1; returns the next free index. */
    int bind(PreparedStatement ps) throws SQLException {
        int idx = 1;
        for (Object b : binds) {
            if (b instanceof Integer i) ps.setInt(idx++, i);
            else if (b instanceof Timestamp t) ps.setTimestamp(idx++, t);
            else ps.setString(idx++, String.valueOf(b));
        }
        return idx;
    }

    /**
     * The study subjects a label names: in the given studies, or in any when
     * {@code studyIds} is null (the system log).
     */
    static List<Integer> studySubjectIds(Connection c, String label, Collection<Integer> studyIds)
            throws SQLException {
        List<Integer> out = new ArrayList<>();
        if (isBlank(label)) return out;
        String sql = "SELECT study_subject_id FROM study_subject WHERE lower(label) = lower(?)"
                + (studyIds == null ? "" : " AND study_id IN " + AuditApiController.buildInClause(studyIds.size()));
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, label.trim());
            int idx = 2;
            if (studyIds != null) for (Integer id : studyIds) ps.setInt(idx++, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    /** An id list for SQL; the ids are the server's own (type ids, study subject ids), never request text. */
    private static String ids(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return "(NULL)";
        StringJoiner j = new StringJoiner(",", "(", ")");
        for (Integer id : ids) j.add(String.valueOf(id.intValue()));
        return j.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
