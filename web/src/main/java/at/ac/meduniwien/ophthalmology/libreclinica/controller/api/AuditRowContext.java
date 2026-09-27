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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestFileReference;

/**
 * The context an audit page needs beyond its rows, loaded once for the page:
 * the visits rows point at, the files older ingest rows point at, and every
 * type's title.
 *
 * <p>Only relationships that never change are read here. A visit never moves
 * to another subject, and a file's device, arrival time and checksum never
 * change, so reading them now says the same as reading them when the row was
 * written. What a file is bound to does change, which is why an ingest row's
 * visit comes from its own values and never from the file's current binding.
 */
final class AuditRowContext {

    /** One audit row as stored. */
    record Row(int auditId, Timestamp auditDate, String auditTable, int entityId,
               String entityName, int eventCrfId, int typeId, String oldValue,
               String newValue, String reason, String userName, String typeName,
               String typeDisplay) {

        static Row read(ResultSet rs) throws SQLException {
            return new Row(
                    rs.getInt("audit_id"),
                    rs.getTimestamp("audit_date"),
                    rs.getString("audit_table"),
                    rs.getInt("entity_id"),
                    rs.getString("entity_name"),
                    rs.getInt("event_crf_id"),
                    rs.getInt("audit_log_event_type_id"),
                    rs.getString("old_value"),
                    rs.getString("new_value"),
                    rs.getString("reason_for_change"),
                    rs.getString("user_name"),
                    rs.getString("type_name"),
                    rs.getString("type_display_name"));
        }

        boolean on(String table) {
            return table.equalsIgnoreCase(auditTable);
        }

        /** The visit this row is about, from its locator or its own values. */
        Integer visitId() {
            if (on("study_event")) return entityId > 0 ? entityId : null;
            if (on("ingest_item")) return AuditRowLabels.studyEventIdIn(newValue, oldValue);
            return null;
        }
    }

    /** A visit as the audit view names it. */
    record Visit(String subjectLabel, String label) {}

    private final Map<Integer, Visit> visits;
    private final Map<Long, String> files;
    private final Map<Integer, String> typeTitles;

    private AuditRowContext(Map<Integer, Visit> visits, Map<Long, String> files,
                            Map<Integer, String> typeTitles) {
        this.visits = visits;
        this.files = files;
        this.typeTitles = typeTitles;
    }

    static AuditRowContext load(DataSource dataSource, List<Row> rows) throws SQLException {
        Set<Integer> visitIds = new HashSet<>();
        Set<Long> fileIds = new HashSet<>();
        for (Row r : rows) {
            Integer visit = r.visitId();
            if (visit != null) visitIds.add(visit);
            if (r.on("ingest_item") && r.entityId() > 0 && AuditRowLabels.isBareMarker(r.entityName())) {
                fileIds.add((long) r.entityId());
            }
        }
        try (Connection c = dataSource.getConnection()) {
            return new AuditRowContext(loadVisits(c, visitIds),
                    IngestFileReference.describeAll(c, fileIds), loadTypeTitles(c));
        }
    }

    /** No context: every lookup answers null, and rows show what they carry. */
    static AuditRowContext empty() {
        return new AuditRowContext(Map.of(), Map.of(), Map.of());
    }

    Visit visit(Integer studyEventId) {
        return studyEventId == null ? null : visits.get(studyEventId);
    }

    /** The reference of a file an older row names only by id, while the file exists. */
    String file(int ingestItemId) {
        return files.get((long) ingestItemId);
    }

    /** The display name of a type, else its name, else null. */
    String typeTitle(int typeId) {
        return typeTitles.get(typeId);
    }

    private static Map<Integer, Visit> loadVisits(Connection c, Set<Integer> ids) throws SQLException {
        Map<Integer, Visit> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT se.study_event_id, ss.label, sed.name, se.sample_ordinal, sed.repeating "
                        + "  FROM study_event se "
                        + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                        + "  JOIN study_event_definition sed "
                        + "    ON sed.study_event_definition_id = se.study_event_definition_id "
                        + " WHERE se.study_event_id = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("integer", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt("study_event_id"), new Visit(
                            rs.getString("label"),
                            AuditRowLabels.visitLabel(rs.getString("name"),
                                    rs.getInt("sample_ordinal"), rs.getBoolean("repeating"))));
                }
            }
        }
        return out;
    }

    private static Map<Integer, String> loadTypeTitles(Connection c) throws SQLException {
        Map<Integer, String> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT audit_log_event_type_id, display_name, name FROM audit_log_event_type");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String display = rs.getString("display_name");
                String name = rs.getString("name");
                String title = display != null && !display.isBlank() ? display
                        : name != null && !name.isBlank() ? name : null;
                if (title != null) out.put(rs.getInt("audit_log_event_type_id"), title);
            }
        }
        return out;
    }
}
