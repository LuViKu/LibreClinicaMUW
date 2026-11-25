/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.export;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tables at the top of a dataset bundle: one file to open per question,
 * instead of a manifest to parse and a folder per participant to walk.
 *
 * <ul>
 *   <li>{@code participants.csv} — one row per participant in the bundle;</li>
 *   <li>{@code visits.csv} — one row per visit of those participants;</li>
 *   <li>{@code images.csv} — one row per acquisition, with its visit, device,
 *       modality, eye and its path in the zip (or why it is not there);</li>
 *   <li>{@code data_dictionary.csv} — the CRF items the dataset selects: what
 *       they are called, their type, unit and answer options. A study whose
 *       data so far is images, with no CRF value yet, still gets its
 *       metadata.</li>
 * </ul>
 *
 * <p>Comma-separated, CRLF, UTF-8 without BOM, quoted as RFC 4180 — the same
 * dialect as {@code casebook.csv}. Every table is written even when it has no
 * row, so a reader can tell "none" from "not exported".
 */
public final class BundleIndexes {

    private static final Logger LOG = LoggerFactory.getLogger(BundleIndexes.class);

    /** The four file names, in the order the bundle writes them. */
    public static final List<String> FILES =
            List.of("participants.csv", "visits.csv", "images.csv", "data_dictionary.csv");

    private BundleIndexes() {}

    /** One visit, as the indexes and the manifest describe it. */
    public record Visit(int studyEventId, String participant, String definitionOid, String name,
                        int repeat, String date, String status) {}

    /**
     * The visits of one participant, by study_event id. Read once per
     * participant: the manifest's acquisition entries and {@code visits.csv}
     * both use it.
     */
    public static Map<Integer, Visit> visitsOf(DataSource ds, int studySubjectId, String label) {
        Map<Integer, Visit> out = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT se.study_event_id, sed.oc_oid, sed.name, se.sample_ordinal, "
                             + "       to_char(se.date_start, 'YYYY-MM-DD'), ses.name "
                             + "  FROM study_event se "
                             + "  JOIN study_event_definition sed "
                             + "    ON sed.study_event_definition_id = se.study_event_definition_id "
                             + "  LEFT JOIN subject_event_status ses "
                             + "    ON ses.subject_event_status_id = se.subject_event_status_id "
                             + " WHERE se.study_subject_id = ? AND se.status_id NOT IN (5, 7) "
                             + " ORDER BY se.date_start NULLS LAST, sed.ordinal, se.sample_ordinal")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), new Visit(rs.getInt(1), label, rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getString(5), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            LOG.error("bundle: could not list the visits of study_subject {}: {}", studySubjectId, e.getMessage());
        }
        return out;
    }

    /**
     * {@code participants.csv}: ID, the study or site they are in, enrolment
     * date, status. Deliberately no birth date or sex: the bundle's job is the
     * imaging and the CRF, and the casebook carries what the CRF records.
     */
    public static byte[] participants(DataSource ds, List<Integer> studySubjectIds) {
        Csv csv = new Csv("participantId", "studyOid", "studyName", "enrollmentDate", "status");
        if (studySubjectIds.isEmpty()) return csv.bytes();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ss.label, s.oc_oid, s.name, to_char(ss.enrollment_date, 'YYYY-MM-DD'), st.name "
                             + "  FROM study_subject ss "
                             + "  JOIN study s ON s.study_id = ss.study_id "
                             + "  LEFT JOIN status st ON st.status_id = ss.status_id "
                             + " WHERE ss.study_subject_id = ANY (?) "
                             + " ORDER BY ss.label, ss.study_subject_id")) {
            Array ids = c.createArrayOf("integer", studySubjectIds.toArray());
            ps.setArray(1, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    csv.row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
                }
            }
        } catch (SQLException e) {
            LOG.error("bundle: could not list the participants: {}", e.getMessage());
            csv.failed();
        }
        return csv.bytes();
    }

    /** {@code visits.csv}, from the visits read for the manifest. */
    public static byte[] visits(List<Visit> visits) {
        Csv csv = new Csv("participantId", "visitOid", "visitName", "visitRepeat", "visitDate", "visitStatus");
        for (Visit v : visits) {
            csv.row(v.participant(), v.definitionOid(), v.name(), String.valueOf(v.repeat()), v.date(), v.status());
        }
        return csv.bytes();
    }

    /**
     * {@code images.csv}, from the manifest's acquisition entries of every
     * participant. The path is the file's entry in this zip; an acquisition
     * the bundle could not carry has no path and says why.
     */
    public static byte[] images(List<Map<String, Object>> subjectSections) {
        Csv csv = new Csv("participantId", "visitOid", "visitName", "visitDate", "device", "modality",
                "laterality", "acquisitionDate", "kind", "source", "path", "byteSize", "sha256",
                "deidentified", "omitted");
        for (Map<String, Object> section : subjectSections) {
            Object label = section.get("label");
            Object acquisitions = section.get("acquisitions");
            if (!(acquisitions instanceof List<?> list)) continue;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> e)) continue;
                csv.row(str(label), str(e.get("visitOid")), str(e.get("visitName")), str(e.get("visitDate")),
                        str(e.get("device")), str(e.get("modalityCode")), str(e.get("laterality")),
                        str(e.get("acquisitionDate")), str(e.get("kind")), str(e.get("sourceKind")),
                        str(e.get("path")), str(e.get("byteSize")), str(e.get("sha256")),
                        str(e.get("deidentified")),
                        Boolean.TRUE.equals(e.get("omitted"))
                                ? (e.get("omittedReason") != null ? str(e.get("omittedReason")) : "not exported")
                                : "");
            }
        }
        return csv.bytes();
    }

    /**
     * {@code data_dictionary.csv}: one row per selected item and CRF version
     * it appears on. The answer options are the CRF's own, as "value=text"
     * pairs separated by "|".
     */
    public static byte[] dataDictionary(DataSource ds, List<Integer> itemIds) {
        Csv csv = new Csv("crfName", "crfVersion", "crfVersionOid", "itemGroup", "itemOid", "itemName",
                "itemLabel", "description", "dataType", "units", "required", "responseOptions");
        if (itemIds == null || itemIds.isEmpty()) return csv.bytes();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT c.name, cv.name, cv.oc_oid, ig.name, i.oc_oid, i.name, ifm.left_item_text, "
                             + "       i.description, t.code, i.units, ifm.required, "
                             + "       rs.options_values, rs.options_text "
                             + "  FROM item i "
                             + "  JOIN item_form_metadata ifm ON ifm.item_id = i.item_id "
                             + "  JOIN crf_version cv ON cv.crf_version_id = ifm.crf_version_id "
                             + "  JOIN crf c ON c.crf_id = cv.crf_id "
                             + "  LEFT JOIN item_group_metadata igm "
                             + "    ON igm.item_id = i.item_id AND igm.crf_version_id = cv.crf_version_id "
                             + "  LEFT JOIN item_group ig ON ig.item_group_id = igm.item_group_id "
                             + "  LEFT JOIN item_data_type t ON t.item_data_type_id = i.item_data_type_id "
                             + "  LEFT JOIN response_set rs ON rs.response_set_id = ifm.response_set_id "
                             + " WHERE i.item_id = ANY (?) "
                             + " ORDER BY c.name, cv.name, ifm.ordinal, i.oc_oid")) {
            ps.setArray(1, c.createArrayOf("integer", itemIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    csv.row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getString(9), rs.getString(10), rs.getBoolean(11) ? "yes" : "no",
                            options(rs.getString(12), rs.getString(13)));
                }
            }
        } catch (SQLException e) {
            LOG.error("bundle: could not write the data dictionary: {}", e.getMessage());
            csv.failed();
        }
        return csv.bytes();
    }

    /**
     * "1=yes|0=no" from the response set's two comma lists. A free-text item
     * has the single option the CRF builder gives it; that reads as nothing.
     */
    static String options(String values, String texts) {
        if (values == null || values.isBlank()) return "";
        String[] v = splitOptions(values);
        String[] t = texts == null ? new String[0] : splitOptions(texts);
        if (v.length <= 1 && (t.length == 0 || t[0].isBlank())) return "";
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < v.length; i++) {
            String text = i < t.length ? t[i] : "";
            pairs.add(v[i] + "=" + text);
        }
        return String.join("|", pairs);
    }

    /** The CRF stores a comma inside an option as "\,". */
    private static String[] splitOptions(String list) {
        String[] parts = list.split("(?<!\\\\),", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].replace("\\,", ",").trim();
        return parts;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** A small RFC 4180 writer, in casebook.csv's dialect. */
    static final class Csv {
        private final StringBuilder sb = new StringBuilder(4096);

        Csv(String... header) {
            row(header);
        }

        void row(String... cells) {
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(cell(cells[i]));
            }
            sb.append("\r\n");
        }

        /** A table that could not be read says so, rather than looking empty. */
        void failed() {
            sb.append("# could not be read; see the server log\r\n");
        }

        byte[] bytes() {
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        static String cell(String v) {
            if (v == null) return "";
            boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                    || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0;
            return quote ? '"' + v.replace("\"", "\"\"") + '"' : v;
        }
    }
}
