/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import at.ac.meduniwien.ophthalmology.libreclinica.core.util.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.FilterBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetFilterDAO;

/**
 * Turns a dataset's saved item filters into the set of study_subject ids the
 * extract should be restricted to.
 *
 * <p>The filters are stored one per {@code filter} row, linked through
 * {@code dataset_filter_map}, with the wizard's predicate held as JSON in
 * {@code filter.sql_statement} — JSON rather than SQL so the predicate is
 * re-validated (and re-rendered by {@link DatasetFilterPredicates}) at extract
 * time instead of a stored string being executed.
 *
 * <p>Resolution is a subject-id set rather than an inline predicate because
 * {@code EntityDAO.genDatabaseDateConstraint} — the single fragment every
 * subject sub-select of the extract splices in — has to stay parseable: callers
 * split the dataset SQL positionally on the quote character, so a filter value
 * containing a quote would corrupt extraction if it were embedded there.
 */
public class DatasetFilterSubjectResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetFilterSubjectResolver.class);

    private static final ObjectMapper JSON = Json.mapper();

    private final DataSource ds;

    public DatasetFilterSubjectResolver(DataSource ds) {
        this.ds = ds;
    }

    /**
     * One filter row: a CRF item ({@code field} null) or a participant field
     * ({@link DatasetFilterPredicates#PARTICIPANT_FIELDS}), an operator and
     * its operands. The wizard's wire shape and the JSON a saved filter holds.
     */
    public record Row(String field, String itemOid, String operator, String value, List<String> values) {

        /** Does the row name a participant field rather than an item? */
        public boolean onParticipant() {
            return field != null && !field.isBlank() && !"item".equals(field);
        }
    }

    /**
     * @param datasetId the dataset whose saved filters to apply
     * @param studyId   the study (or parent study) the extract runs for
     * @return the matching study_subject ids, or {@code null} when the dataset
     *         has no filters — meaning "do not restrict". An empty list means
     *         the filters matched nobody and the export must be empty.
     */
    public List<Integer> resolve(int datasetId, int studyId) {
        List<Row> rows = savedRows(datasetId);
        if (rows == null) {
            // Unreadable filters: restrict to nothing rather than widen.
            return List.of();
        }
        if (rows.isEmpty()) {
            return null;
        }
        List<Integer> ids = subjectsMatching(studyId, rows, "dataset " + datasetId);
        if (ids != null) {
            LOG.info("dataset {}: {} filter(s) matched {} subject(s)", datasetId, rows.size(), ids.size());
        }
        return ids;
    }

    /**
     * The dataset's saved filter rows, in authoring order; empty when it has
     * none, null when they could not be read.
     */
    public List<Row> savedRows(int datasetId) {
        List<FilterBean> saved;
        try {
            saved = new DatasetFilterDAO(ds).findFiltersByDataset(datasetId);
        } catch (RuntimeException e) {
            LOG.error("could not load filters for dataset {}: {}", datasetId, e.getMessage());
            return null;
        }
        List<Row> rows = new ArrayList<>();
        if (saved == null) return rows;
        for (FilterBean fb : saved) {
            Row row = parse(fb.getSQLStatement());
            // Kept as an unusable row rather than dropped, so it is counted,
            // logged and ignored like any other filter that cannot be applied.
            rows.add(row != null ? row : new Row(null, null, null, null, List.of()));
        }
        return rows;
    }

    /**
     * The participants of {@code studyId} and its sites that every row
     * admits. The wizard's preview counts these, and the extract restricts to
     * them, so the two cannot disagree.
     *
     * @param context names the caller in the log
     * @return the matching study_subject ids; {@code null} when no row could
     *         be applied (an unknown item, an unusable operator), which the
     *         extract reads as "do not restrict" and logs loudly; an empty
     *         list when the rows matched nobody or the query failed
     */
    public List<Integer> subjectsMatching(int studyId, List<Row> rows, String context) {
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT ss.study_subject_id FROM study_subject ss "
                        + "JOIN study s ON s.study_id = ss.study_id "
                        + "WHERE (s.study_id = ? OR s.parent_study_id = ?) ");
        List<Object> params = new ArrayList<>();
        params.add(studyId);
        params.add(studyId);

        int applied = 0;
        for (Row row : rows) {
            DatasetFilterPredicates.Fragment frag;
            try {
                if (row.onParticipant()) {
                    frag = DatasetFilterPredicates.renderParticipant(
                            row.field(), row.operator(), row.value(), row.values());
                    sql.append(" AND ").append(frag.sql()).append(' ');
                    params.addAll(frag.params());
                    applied++;
                    continue;
                }
                ItemRef item = resolveItem(row.itemOid());
                if (item == null) {
                    // The CRF changed under a saved dataset. Refusing to export is
                    // worse than exporting the rest, but silently widening the
                    // result would be worst — so this is logged loudly.
                    LOG.warn("{} filter references unknown item OID '{}' — filter ignored",
                            context, row.itemOid());
                    continue;
                }
                frag = DatasetFilterPredicates.render(row.operator(), row.value(), row.values(), item.numeric());
                sql.append(" AND EXISTS (SELECT 1 FROM item_data id "
                        + "JOIN event_crf ec ON ec.event_crf_id = id.event_crf_id "
                        + "JOIN study_event se ON se.study_event_id = ec.study_event_id "
                        + "WHERE se.study_subject_id = ss.study_subject_id "
                        + "  AND id.item_id = ? AND ").append(frag.sql()).append(") ");
                params.add(item.id());
                params.addAll(frag.params());
                applied++;
            } catch (IllegalArgumentException e) {
                LOG.warn("{} filter is not usable ({}) — filter ignored", context, e.getMessage());
            }
        }
        if (applied == 0) {
            LOG.warn("{} has {} filter(s) but none could be applied — exporting unrestricted",
                    context, rows.size());
            return null;
        }

        List<Integer> ids = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            // Widening silently would hand the operator more data than the
            // filters allow, so a failure here restricts to nothing instead.
            LOG.error("{} filter resolution failed ({}) — exporting nothing", context, e.getMessage());
            return List.of();
        }
        return ids;
    }

    /** A saved filter's JSON as a row; null when unreadable. */
    public static Row parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(json);
            List<String> values = new ArrayList<>();
            JsonNode arr = n.get("values");
            if (arr != null && arr.isArray()) {
                arr.forEach(v -> values.add(v.asString()));
            }
            JsonNode value = n.get("value");
            return new Row(
                    text(n.get("field")),
                    text(n.get("itemOid")),
                    text(n.get("operator")),
                    value == null || value.isNull() ? null : value.asString(),
                    values);
        } catch (Exception e) {
            LOG.warn("unreadable saved filter — ignored: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }

    /** An item a filter names: its id, and whether it holds numbers. */
    private record ItemRef(int id, boolean numeric) {}

    private ItemRef resolveItem(String itemOid) {
        if (itemOid == null || itemOid.isBlank()) return null;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT i.item_id, upper(COALESCE(t.code, '')) FROM item i "
                             + "LEFT JOIN item_data_type t ON t.item_data_type_id = i.item_data_type_id "
                             + "WHERE i.oc_oid = ?")) {
            ps.setString(1, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String type = rs.getString(2);
                return new ItemRef(rs.getInt(1), "INT".equals(type) || "REAL".equals(type));
            }
        } catch (SQLException e) {
            LOG.error("item lookup failed for a saved filter: {}", e.getMessage());
            return null;
        }
    }
}
