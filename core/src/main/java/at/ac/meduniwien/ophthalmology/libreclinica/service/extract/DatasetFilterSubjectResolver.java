/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource ds;

    public DatasetFilterSubjectResolver(DataSource ds) {
        this.ds = ds;
    }

    /**
     * @param datasetId the dataset whose saved filters to apply
     * @param studyId   the study (or parent study) the extract runs for
     * @return the matching study_subject ids, or {@code null} when the dataset
     *         has no filters — meaning "do not restrict". An empty list means
     *         the filters matched nobody and the export must be empty.
     */
    public List<Integer> resolve(int datasetId, int studyId) {
        List<FilterBean> saved;
        try {
            saved = new DatasetFilterDAO(ds).findFiltersByDataset(datasetId);
        } catch (RuntimeException e) {
            LOG.error("could not load filters for dataset {}: {}", datasetId, e.getMessage());
            return null;
        }
        if (saved == null || saved.isEmpty()) {
            return null;
        }

        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT ss.study_subject_id FROM study_subject ss "
                        + "JOIN study s ON s.study_id = ss.study_id "
                        + "WHERE (s.study_id = ? OR s.parent_study_id = ?) ");
        List<Object> params = new ArrayList<>();
        params.add(studyId);
        params.add(studyId);

        int applied = 0;
        for (FilterBean fb : saved) {
            ParsedFilter pf = parse(fb.getSQLStatement());
            if (pf == null) continue;
            Integer itemId = resolveItemId(pf.itemOid());
            if (itemId == null) {
                // The CRF changed under a saved dataset. Refusing to export is
                // worse than exporting the rest, but silently widening the
                // result would be worst — so this is logged loudly.
                LOG.warn("dataset {} filter references unknown item OID '{}' — filter ignored",
                        datasetId, pf.itemOid());
                continue;
            }
            DatasetFilterPredicates.Fragment frag;
            try {
                frag = DatasetFilterPredicates.render(pf.operator(), pf.value(), pf.values());
            } catch (IllegalArgumentException e) {
                LOG.warn("dataset {} filter is not usable ({}) — filter ignored",
                        datasetId, e.getMessage());
                continue;
            }
            sql.append(" AND EXISTS (SELECT 1 FROM item_data id "
                    + "JOIN event_crf ec ON ec.event_crf_id = id.event_crf_id "
                    + "JOIN study_event se ON se.study_event_id = ec.study_event_id "
                    + "WHERE se.study_subject_id = ss.study_subject_id "
                    + "  AND id.item_id = ? AND ").append(frag.sql()).append(") ");
            params.add(itemId);
            params.addAll(frag.params());
            applied++;
        }
        if (applied == 0) {
            LOG.warn("dataset {} has {} saved filter(s) but none could be applied — "
                    + "exporting unrestricted", datasetId, saved.size());
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
            LOG.error("dataset {} filter resolution failed ({}) — exporting nothing",
                    datasetId, e.getMessage());
            return List.of();
        }
        LOG.info("dataset {}: {} filter(s) matched {} subject(s)", datasetId, applied, ids.size());
        return ids;
    }

    /** The wizard's predicate as stored. */
    private record ParsedFilter(String itemOid, String operator, String value, List<String> values) {}

    private static ParsedFilter parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(json);
            List<String> values = new ArrayList<>();
            JsonNode arr = n.get("values");
            if (arr != null && arr.isArray()) {
                arr.forEach(v -> values.add(v.asText()));
            }
            JsonNode value = n.get("value");
            return new ParsedFilter(
                    text(n.get("itemOid")),
                    text(n.get("operator")),
                    value == null || value.isNull() ? null : value.asText(),
                    values);
        } catch (Exception e) {
            LOG.warn("unreadable saved filter — ignored: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private Integer resolveItemId(String itemOid) {
        if (itemOid == null || itemOid.isBlank()) return null;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT item_id FROM item WHERE oc_oid = ?")) {
            ps.setString(1, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            LOG.error("item lookup failed for a saved filter: {}", e.getMessage());
            return null;
        }
    }
}
