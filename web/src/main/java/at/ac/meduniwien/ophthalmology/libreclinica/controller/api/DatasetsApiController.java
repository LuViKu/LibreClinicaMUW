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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ItemDataType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.6 Data Export — Phase 3 (filters) entry point.
 *
 * <p>Surfaces the legacy {@code dataset_filter_map} contract to the
 * SPA. The endpoint shipped in this phase is
 * {@code POST /api/v1/datasets/{datasetId}:test-filter}: it accepts
 * an in-memory filter list (as the operator would author it in the
 * wizard), composes the WHERE-clause fragment the legacy extraction
 * service would build, runs a COUNT against
 * {@code study_subject + event_crf + item_data}, and returns
 * matching-vs-total subject + CRF counts. The dataset row is NOT
 * mutated — the wizard uses this for live inline preview before the
 * operator commits.
 *
 * <p>This controller intentionally does not (yet) ship the full
 * dataset CRUD surface ({@code POST /datasets},
 * {@code PUT /datasets/{id}}) — those land with Phase 1 + Phase 2's
 * create-dataset wizard. The {@code CreateDatasetRequest} +
 * {@link DatasetFilterDto} records published here are the wire
 * vocabulary Phase 2 will extend; this PR fixes the filter portion of
 * the contract so the SPA's FilterBuilder can be developed against a
 * real backend.
 *
 * <h2>Authorization</h2>
 *
 * <p>Chain-level {@code .anyRequest().hasRole("USER")} (matches the
 * other {@code /api/v1/*} adapters). The session must have a bound
 * {@code userBean} + {@code study}; the controller does not run a
 * per-dataset ACL check beyond "the dataset belongs to the active
 * study" because the legacy extract surface is study-scoped and
 * cross-study reads are already filtered upstream by site visibility.
 *
 * <h2>Operator semantics for {@code :test-filter}</h2>
 *
 * <p>The legacy {@code FilterDAO.genSQLStatement} composes
 * {@code AND subject_id IN (SELECT subject_id FROM extract_data_table
 * WHERE (item_id = X AND value op 'Y'))}. This controller mirrors the
 * predicate shape against the cleaner
 * {@code study_subject + event_crf + item_data} join (the legacy view
 * is built on the same join). Each filter contributes one
 * {@code EXISTS} clause on {@code item_data}. The filters are
 * AND-ed together (Phase 3 ships AND-only; OR + grouping are
 * deferred until the wizard surfaces the connector).
 *
 * <p>Counts:
 * <ul>
 *   <li>{@code matchingSubjects} — distinct study_subjects whose row
 *       set satisfies every filter predicate.</li>
 *   <li>{@code totalSubjects} — study_subjects in the active study
 *       (the wizard renders this as the denominator).</li>
 *   <li>{@code matchingCrfs} — event_crfs whose linked item_data rows
 *       satisfy every filter predicate.</li>
 *   <li>{@code totalCrfs} — event_crfs in the active study.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/datasets")
@Tag(name = "Datasets", description = "Data Export dataset + filter surface (Phase E.6).")
public class DatasetsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetsApiController.class);

    /**
     * Operators the {@link DatasetFilterDto} accepts.
     *
     * <p>{@code in} carries a list of values, {@code between} carries
     * a {@code [low, high]} pair, the unary {@code is-null} +
     * {@code not-null} need no value. Anything else is treated as a
     * scalar comparison.
     */
    static final Set<String> KNOWN_OPS = Set.of(
            "=", "!=", "<", "<=", ">", ">=", "in", "between", "is-null", "not-null");

    private static final Set<String> NUMERIC_ONLY_OPS = Set.of("<", "<=", ">", ">=", "between");
    private static final Set<String> UNARY_OPS = Set.of("is-null", "not-null");

    /** ItemDataType.name() values that admit numeric/date-style ordering operators. */
    private static final Set<String> NUMERIC_OR_DATE_TYPES = Set.of(
            ItemDataType.INTEGER.getName(),
            ItemDataType.REAL.getName(),
            ItemDataType.DATE.getName(),
            ItemDataType.PDATE.getName());

    private final DataSource dataSource;

    @Autowired
    public DatasetsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/v1/datasets/{datasetId}:test-filter                       */
    /* ------------------------------------------------------------------ */

    /**
     * Counts subjects + CRFs that satisfy the supplied predicate
     * list. Does NOT persist anything — this is the wizard's live
     * preview endpoint.
     */
    @PostMapping("/{datasetId}:test-filter")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = FilterTestResult.class)))
    public ResponseEntity<?> testFilter(@PathVariable("datasetId") String datasetId,
                                        @RequestBody(required = false) TestFilterRequest body,
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

        if (body == null || body.filters() == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "'filters' is required"));
        }

        // Validate each filter row against the same rules the wizard
        // enforces client-side, so the live preview can't desync from
        // what the persist path would accept later.
        ItemDAO itemDao = new ItemDAO(dataSource);
        Map<String, ItemBean> resolvedItems = new HashMap<>();
        for (int i = 0; i < body.filters().size(); i++) {
            DatasetFilterDto row = body.filters().get(i);
            ResponseEntity<?> err = validateFilterRow(row, i, itemDao, resolvedItems);
            if (err != null) return err;
        }

        try {
            FilterTestResult result = runCounts(currentStudy.getId(), body.filters(), resolvedItems);
            return ResponseEntity.ok(result);
        } catch (SQLException e) {
            LOG.warn("Failed to run :test-filter count for datasetId={} studyId={}",
                    datasetId, currentStudy.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to run filter count — see server log"));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Validation                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Returns a 400 ResponseEntity when the row is malformed, or
     * {@code null} when it's accepted. Populates
     * {@code resolvedItems} as a side-effect so the SQL-builder
     * downstream doesn't re-resolve the OID.
     */
    private ResponseEntity<?> validateFilterRow(DatasetFilterDto row, int index,
                                                ItemDAO itemDao,
                                                Map<String, ItemBean> resolvedItems) {
        if (row == null || row.itemOid() == null || row.itemOid().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].itemOid is required"));
        }
        if (row.operator() == null || row.operator().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].operator is required"));
        }
        String op = row.operator();
        if (!KNOWN_OPS.contains(op)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].operator '" + op + "' is not supported"));
        }

        ItemBean item = resolvedItems.computeIfAbsent(row.itemOid(), oid -> {
            ArrayList<ItemBean> hits = itemDao.findByOid(oid);
            return hits.isEmpty() ? null : hits.get(0);
        });
        if (item == null || item.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].itemOid '" + row.itemOid() + "' does not resolve to a known item"));
        }

        // Numeric/date-only operators (<, <=, >, >=, between) must
        // sit on a numeric or date item.
        if (NUMERIC_ONLY_OPS.contains(op)
                && !NUMERIC_OR_DATE_TYPES.contains(item.getDataType().getName())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].operator '" + op
                    + "' requires a numeric or date item; '" + row.itemOid()
                    + "' is " + item.getDataType().getName()));
        }

        // `in` needs a non-empty value list.
        if ("in".equals(op)) {
            if (row.values() == null || row.values().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "filters[" + index + "].values is required for 'in'"));
            }
        }
        // `between` needs exactly two values.
        if ("between".equals(op)) {
            if (row.values() == null || row.values().size() != 2) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "filters[" + index + "].values must hold exactly two entries for 'between'"));
            }
        }
        // Scalar comparisons need a value.
        if (!UNARY_OPS.contains(op) && !"in".equals(op) && !"between".equals(op)) {
            if (row.value() == null) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "filters[" + index + "].value is required for '" + op + "'"));
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* SQL                                                                 */
    /* ------------------------------------------------------------------ */

    private FilterTestResult runCounts(int studyId, List<DatasetFilterDto> filters,
                                       Map<String, ItemBean> resolvedItems) throws SQLException {
        int totalSubjects = countTotalSubjects(studyId);
        int totalCrfs = countTotalCrfs(studyId);

        // No filters → match-all.
        if (filters.isEmpty()) {
            return new FilterTestResult(totalSubjects, totalCrfs, totalSubjects, totalCrfs);
        }

        StringBuilder subjectSql = new StringBuilder(
                "SELECT COUNT(DISTINCT ss.study_subject_id) " +
                "FROM study_subject ss WHERE ss.study_id = ? ");
        StringBuilder crfSql = new StringBuilder(
                "SELECT COUNT(DISTINCT ec.event_crf_id) " +
                "FROM event_crf ec " +
                "JOIN study_event se ON se.study_event_id = ec.study_event_id " +
                "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id " +
                "WHERE ss.study_id = ? ");

        // Each filter row appends an EXISTS clause to both queries
        // and contributes (item_id, ...value params...) — collect
        // both parameter lists in lock-step.
        List<Object> subjectParams = new ArrayList<>();
        List<Object> crfParams = new ArrayList<>();
        subjectParams.add(studyId);
        crfParams.add(studyId);

        for (DatasetFilterDto row : filters) {
            ItemBean item = resolvedItems.get(row.itemOid());
            PredicateFragment pf = renderPredicate(row);
            subjectSql.append(" AND EXISTS (SELECT 1 FROM item_data id " +
                    "JOIN event_crf ec2 ON ec2.event_crf_id = id.event_crf_id " +
                    "JOIN study_event se2 ON se2.study_event_id = ec2.study_event_id " +
                    "WHERE se2.study_subject_id = ss.study_subject_id " +
                    "  AND id.item_id = ? AND " + pf.sqlFragment() + ") ");
            crfSql.append(" AND EXISTS (SELECT 1 FROM item_data id " +
                    "WHERE id.event_crf_id = ec.event_crf_id " +
                    "  AND id.item_id = ? AND " + pf.sqlFragment() + ") ");
            subjectParams.add(item.getId());
            subjectParams.addAll(pf.params());
            crfParams.add(item.getId());
            crfParams.addAll(pf.params());
        }

        int matchingSubjects = runCount(subjectSql.toString(), subjectParams);
        int matchingCrfs = runCount(crfSql.toString(), crfParams);
        return new FilterTestResult(matchingSubjects, matchingCrfs, totalSubjects, totalCrfs);
    }

    private int countTotalSubjects(int studyId) throws SQLException {
        return runCount(
                "SELECT COUNT(*) FROM study_subject WHERE study_id = ?",
                List.of(studyId));
    }

    private int countTotalCrfs(int studyId) throws SQLException {
        return runCount(
                "SELECT COUNT(*) FROM event_crf ec " +
                "JOIN study_event se ON se.study_event_id = ec.study_event_id " +
                "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id " +
                "WHERE ss.study_id = ?",
                List.of(studyId));
    }

    private int runCount(String sql, List<Object> params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * Renders the predicate fragment for one filter row. The result
     * is a {@code (sqlFragment, params)} pair that gets spliced into
     * the EXISTS clause downstream. The operator is whitelisted
     * upstream by {@link #validateFilterRow}, so the raw splice into
     * the SQL string is safe.
     *
     * <p>{@code id.value} is a {@code TEXT} column; the lexicographic
     * comparison stands for {@code DATE}/{@code PDATE} (ISO-formatted
     * values sort correctly) and {@code INTEGER}/{@code REAL} get the
     * lex comparison too — the Phase 4 export path is what produces
     * canonically formatted data, and the count probe just needs to
     * be conservative for the wizard preview. Type-correct numeric
     * comparison can ride on a later iteration if operators report
     * mismatches against zero-padded data.
     */
    private PredicateFragment renderPredicate(DatasetFilterDto row) {
        String op = row.operator();
        if ("is-null".equals(op)) {
            return new PredicateFragment(
                    "(id.value IS NULL OR id.value = '')", Collections.emptyList());
        }
        if ("not-null".equals(op)) {
            return new PredicateFragment(
                    "(id.value IS NOT NULL AND id.value <> '')", Collections.emptyList());
        }
        if ("in".equals(op)) {
            String placeholders = String.join(",",
                    Collections.nCopies(row.values().size(), "?"));
            return new PredicateFragment(
                    "id.value IN (" + placeholders + ")",
                    new ArrayList<>(row.values()));
        }
        if ("between".equals(op)) {
            return new PredicateFragment(
                    "id.value BETWEEN ? AND ?",
                    List.of(row.values().get(0), row.values().get(1)));
        }
        return new PredicateFragment(
                "id.value " + op + " ?",
                List.of(row.value()));
    }

    /* ------------------------------------------------------------------ */
    /* Wire records                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Phase 3 wire shape — one predicate row.
     *
     * <p>{@code value} holds the scalar (or {@code null} for unary +
     * list ops); {@code values} holds the list (for {@code in}) or
     * the two-tuple (for {@code between}). Older clients can omit
     * either field — the controller picks the right one based on
     * the operator.
     */
    public record DatasetFilterDto(String itemOid, String operator,
                                   String value, List<String> values) {}

    /** Request body for {@code POST /datasets/{id}:test-filter}. */
    public record TestFilterRequest(List<DatasetFilterDto> filters) {}

    /**
     * Response body for {@code POST /datasets/{id}:test-filter}.
     *
     * <p>The matching counts include the predicate set; the totals
     * are the un-filtered study population. The wizard renders the
     * ratio.
     */
    public record FilterTestResult(int matchingSubjects, int matchingCrfs,
                                   int totalSubjects, int totalCrfs) {}

    /**
     * Future Phase 2 extension — the create-dataset wizard's full
     * payload. Published in this PR so Phase 3 can reference the
     * shared {@link DatasetFilterDto} list shape without circular
     * dependencies; Phase 2's wizard PR will extend this record with
     * the rest of the dataset metadata + the items tree it
     * authors.
     */
    public record CreateDatasetRequest(String name, String description,
                                       List<String> selectedItemOids,
                                       List<DatasetFilterDto> filters) {}

    /**
     * Internal pair of rendered SQL fragment + the JDBC parameters
     * it needs (in order). Not part of the public wire vocabulary.
     */
    private record PredicateFragment(String sqlFragment, List<Object> params) {}

    /**
     * Stable lowercase canonicalization for operator strings — used
     * in the unit tests to assert the validation path treats
     * mixed-case operator strings consistently.
     */
    static String normalizeOperator(String op) {
        if (op == null) return null;
        return op.toLowerCase(Locale.ROOT);
    }

    /**
     * Visible-for-test helper — exposes the static op classification
     * so the test class doesn't duplicate the predicate tables.
     */
    static boolean operatorIsNumericOnly(String op) {
        return NUMERIC_ONLY_OPS.contains(op);
    }

    static boolean operatorIsUnary(String op) {
        return UNARY_OPS.contains(op);
    }

    static Set<String> dataTypesAcceptingOrderingOps() {
        return new HashSet<>(NUMERIC_OR_DATE_TYPES);
    }
}
