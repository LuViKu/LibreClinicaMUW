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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 2026-06-24 user-feedback round — public BCVA-entry portal backend.
 *
 * <p>Sibling of {@link PublicOctUploadController}: study nurses
 * bookmark {@code /app/bcva-entry/<studyOid>} and enter today's
 * decimal BCVA + refraction without logging in. The institutional
 * reverse proxy is the only access gate; this controller is
 * whitelisted in {@code SecurityConfig} alongside the OCT-upload
 * portal under {@code /pages/api/v1/public/bcva-entry/**}.
 *
 * <p>Two endpoints (the plan's 60-s undo is deferred to a follow-up
 * commit — single-shot persistence ships first, undo is a
 * quality-of-life add-on the plan calls out as v2):
 *
 * <ul>
 *   <li>{@code GET /{studyOid}/visits?date=YYYY-MM-DD} — lists every
 *       study_event on the named date in the study, with subject
 *       label + event-definition label + the event_crf_id of any
 *       existing BCVA CRF row + a boolean flagging prior writes.</li>
 *   <li>{@code POST /commit} — looks up or creates the BCVA event_crf
 *       row, upserts item_data for each provided OID, writes a
 *       {@code BCVA_ENTRY_PUBLIC} audit row with {@code user_id NULL}
 *       and the operator-supplied "entered by" name.</li>
 * </ul>
 *
 * <p>The controller is convention-driven on which CRF row carries
 * BCVA: it looks for any {@code event_crf.crf_version_id} on the
 * matching {@code study_event} whose CRF version exposes at least
 * one of the canonical BCVA item OIDs ({@code OD_BCVA_DECIMAL},
 * {@code OS_BCVA_DECIMAL}, {@code OD_BCVA_LETTERS},
 * {@code OS_BCVA_LETTERS}). When no such CRF exists on the
 * event_definition, commit fails with HTTP 404 — the study admin
 * must drop the {@code bcvaDecimalPreset} (or the legacy
 * {@code bcvaPreset}) on a CRF first.
 */
@Controller
@RequestMapping("/pages/api/v1/public/bcva-entry")
public class PublicBcvaEntryController {

    private static final Logger LOG = LoggerFactory.getLogger(PublicBcvaEntryController.class);

    /** Canonical BCVA item OIDs the portal probes + writes. */
    private static final Set<String> BCVA_ITEM_OIDS = Set.of(
            "OD_BCVA_DECIMAL", "OS_BCVA_DECIMAL",
            "OD_BCVA_PARTIAL", "OS_BCVA_PARTIAL",
            "OD_BCVA_LETTERS", "OS_BCVA_LETTERS",
            "OD_BCVA_REFRACTION_SPHERE", "OS_BCVA_REFRACTION_SPHERE",
            "OD_BCVA_REFRACTION_CYLINDER", "OS_BCVA_REFRACTION_CYLINDER",
            "OD_BCVA_REFRACTION_AXIS", "OS_BCVA_REFRACTION_AXIS"
    );

    /**
     * Subset of {@link #BCVA_ITEM_OIDS} used to PROBE whether a CRF
     * version exposes BCVA capture. The four decimal-letters
     * variants are enough to identify a BCVA-flavoured CRF —
     * refraction items live on the same CRF by convention.
     */
    private static final Set<String> BCVA_PROBE_OIDS = Set.of(
            "OD_BCVA_DECIMAL", "OS_BCVA_DECIMAL",
            "OD_BCVA_LETTERS", "OS_BCVA_LETTERS"
    );

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Schedule / data-entry-started / signed-completed — the only
     *  states a visit can be in for the BCVA portal to accept entry.
     *  Removed (5), Stopped (5 too in some encodings) and skipped
     *  are excluded so the operator doesn't accidentally land BCVA
     *  on a withdrawn / cancelled visit. */
    private static final Set<Integer> ENTRY_OK_STATUS_IDS = Set.of(1, 2, 4, 8);

    private final DataSource dataSource;

    @Autowired
    public PublicBcvaEntryController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ====================================================================== */
    /* GET /{studyOid}/visits?date=YYYY-MM-DD                                */
    /* ====================================================================== */

    /**
     * List planned / in-progress / completed visits on the given date
     * for the named study, with enough metadata for the portal to
     * render a card per visit + indicate whether BCVA has already
     * been entered.
     *
     * @param studyOid {@code study.oc_oid} (e.g. {@code S_RIS_DEMO}).
     * @param dateRaw  ISO yyyy-MM-dd; defaults to "today" in the
     *                 server's local zone.
     */
    @GetMapping(path = "/{studyOid}/visits", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> listVisits(@PathVariable("studyOid") String studyOid,
                                        @RequestParam(value = "date", required = false) String dateRaw) {
        LocalDate date;
        try {
            date = dateRaw == null || dateRaw.isBlank()
                    ? LocalDate.now()
                    : LocalDate.parse(dateRaw, ISO_DATE);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Invalid 'date' (expected ISO yyyy-MM-dd): " + dateRaw));
        }

        // Resolve study identity first — supplies the header the
        // portal renders + lets us 404 cleanly when the operator's
        // bookmark points at a deleted study.
        StudyHeader study = fetchStudyByOid(studyOid);
        if (study == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No study with oc_oid " + studyOid));
        }

        // The SQL walks study_event → study_subject + event_def, then
        // for each row probes (a) the first BCVA-flavoured event_crf
        // on the event and (b) whether any BCVA item_data already
        // holds a value. The probes are correlated subqueries — one
        // round-trip total.
        String sql = "SELECT "
                + "  se.study_event_id, "
                + "  se.study_subject_id, "
                + "  ss.label AS subject_label, "
                + "  sed.name AS event_def_label, "
                + "  se.date_start, "
                + "  se.subject_event_status_id, "
                + "  ( SELECT ec.event_crf_id FROM event_crf ec "
                + "      JOIN item_form_metadata ifm ON ifm.crf_version_id = ec.crf_version_id "
                + "      JOIN item i ON i.item_id = ifm.item_id "
                + "     WHERE ec.study_event_id = se.study_event_id "
                + "       AND COALESCE(ec.status_id, 0) NOT IN (5, 7) "
                + "       AND i.name IN ('OD_BCVA_DECIMAL','OS_BCVA_DECIMAL','OD_BCVA_LETTERS','OS_BCVA_LETTERS') "
                + "     ORDER BY ec.event_crf_id ASC LIMIT 1 ) AS event_crf_id, "
                + "  EXISTS ( "
                + "     SELECT 1 FROM item_data idata "
                + "       JOIN event_crf ec ON ec.event_crf_id = idata.event_crf_id "
                + "       JOIN item i ON i.item_id = idata.item_id "
                + "      WHERE ec.study_event_id = se.study_event_id "
                + "        AND i.name IN ('OD_BCVA_DECIMAL','OS_BCVA_DECIMAL','OD_BCVA_LETTERS','OS_BCVA_LETTERS') "
                + "        AND COALESCE(idata.deleted, false) = false "
                + "        AND idata.value IS NOT NULL AND idata.value <> '' "
                + "  ) AS bcva_already_entered "
                + "FROM study_event se "
                + "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + "JOIN study_event_definition sed ON sed.study_event_definition_id = se.study_event_definition_id "
                + "WHERE ss.study_id = ? "
                + "  AND date(se.date_start) = ? "
                + "  AND se.subject_event_status_id IN (1, 2, 4, 8) "
                + "ORDER BY se.date_start ASC, ss.label ASC";

        List<Map<String, Object>> visits = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, study.studyId);
            ps.setObject(2, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("studyEventId", rs.getInt("study_event_id"));
                    row.put("studySubjectId", rs.getInt("study_subject_id"));
                    row.put("subjectLabel", rs.getString("subject_label"));
                    row.put("eventDefinitionLabel", rs.getString("event_def_label"));
                    Timestamp ds = rs.getTimestamp("date_start");
                    row.put("dateStarted", ds == null ? null : ds.toInstant().toString().substring(0, 10));
                    int ecid = rs.getInt("event_crf_id");
                    row.put("eventCrfId", rs.wasNull() ? null : ecid);
                    row.put("bcvaAlreadyEntered", rs.getBoolean("bcva_already_entered"));
                    visits.add(row);
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("listVisits failed for studyOid={} date={}: {}",
                    studyOid, date, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list visits: " + sqlEx.getMessage()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("study", Map.of(
                "oid", study.oid,
                "name", study.name,
                "uniqueIdentifier", study.uniqueIdentifier == null ? "" : study.uniqueIdentifier));
        body.put("date", date.toString());
        body.put("visits", visits);
        return ResponseEntity.ok(body);
    }

    /* ====================================================================== */
    /* POST /commit                                                          */
    /* ====================================================================== */

    /**
     * Persist a BCVA-entry submission. Body shape (see plan):
     *
     * <pre>{
     *   "studyEventId": 112,
     *   "enteredBy": "Maria Müller",
     *   "values": {
     *     "OD_BCVA_DECIMAL": 1.0,
     *     "OD_BCVA_PARTIAL": -2,
     *     ...
     *   }
     * }</pre>
     *
     * <p>The {@code values} keys are canonical BCVA item OIDs;
     * unknown keys are rejected (400). Values may be number or
     * string — the controller renders to text for {@code item_data.value}.
     */
    @PostMapping(path = "/commit", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> commit(@RequestBody CommitRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Request body required"));
        }
        if (body.studyEventId == null || body.studyEventId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "'studyEventId' required"));
        }
        if (body.enteredBy == null || body.enteredBy.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'enteredBy' required"));
        }
        if (body.values == null || body.values.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'values' required"));
        }
        // Whitelist the OIDs the portal accepts — defence-in-depth
        // (no unknown OID can be written via the public path).
        Set<String> unknownOids = new HashSet<>(body.values.keySet());
        unknownOids.removeAll(BCVA_ITEM_OIDS);
        if (!unknownOids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Unknown item OIDs: " + String.join(", ", unknownOids)));
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // 1. Locate the BCVA CRF version attached to this
                //    event's event_definition. Errors 404 when the
                //    study admin hasn't authored a BCVA CRF yet.
                BcvaCrfTarget target = resolveBcvaCrfTarget(c, body.studyEventId);
                if (target == null) {
                    c.rollback();
                    return ResponseEntity.status(404).body(Map.of(
                            "message", "No BCVA CRF authored on this event's definition"));
                }

                // 2. Find an existing event_crf for that CRF version on
                //    this event, or create one. Both paths leave us
                //    with a stable event_crf_id.
                int eventCrfId = findOrCreateEventCrf(c, body.studyEventId, target);

                // 3. Resolve item_id for each (oid → id). Only the
                //    OIDs that exist on this CRF version write — the
                //    portal payload may include items the CRF doesn't
                //    declare (e.g. PARTIAL when the study uses the
                //    legacy letters-only preset); those are skipped.
                Map<String, Integer> itemIdByOid = resolveItemIds(
                        c, target.crfVersionId, body.values.keySet());

                // 4. Upsert each value + collect the item_data ids so
                //    the audit row can surface them.
                List<Integer> writtenItemDataIds = new ArrayList<>();
                for (Map.Entry<String, Object> e : body.values.entrySet()) {
                    Integer itemId = itemIdByOid.get(e.getKey());
                    if (itemId == null) continue; // CRF doesn't expose this OID — skip
                    String value = renderValue(e.getValue());
                    if (value == null || value.isBlank()) continue;
                    int itemDataId = upsertItemData(c, eventCrfId, itemId, value);
                    writtenItemDataIds.add(itemDataId);
                }
                if (writtenItemDataIds.isEmpty()) {
                    c.rollback();
                    return ResponseEntity.badRequest().body(Map.of(
                            "message", "No supplied OID matched the target CRF — nothing written"));
                }

                // 5. Audit row. user_id NULL (trust-the-reverse-proxy).
                long auditId = writeAuditRow(c, eventCrfId, body.enteredBy.trim(),
                        body.studyEventId, writtenItemDataIds);

                c.commit();
                return ResponseEntity.ok(Map.of(
                        "eventCrfId", eventCrfId,
                        "auditId", auditId,
                        "itemDataIds", writtenItemDataIds));
            } catch (Exception ex) {
                c.rollback();
                LOG.error("commit failed for studyEventId={}: {}",
                        body.studyEventId, ex.getMessage(), ex);
                return ResponseEntity.internalServerError().body(Map.of(
                        "message", "Commit failed: " + ex.getMessage()));
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException sqlEx) {
            LOG.error("commit connection failed: {}", sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Database connection failed: " + sqlEx.getMessage()));
        }
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    /** Lightweight study identity record. */
    private record StudyHeader(int studyId, String oid, String name, String uniqueIdentifier) {}

    private StudyHeader fetchStudyByOid(String studyOid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_id, oc_oid, name, unique_identifier "
                             + "  FROM study "
                             + " WHERE oc_oid = ? AND status_id = 1 "
                             + " LIMIT 1")) {
            ps.setString(1, studyOid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new StudyHeader(
                        rs.getInt("study_id"),
                        rs.getString("oc_oid"),
                        rs.getString("name"),
                        rs.getString("unique_identifier"));
            }
        } catch (SQLException e) {
            LOG.error("fetchStudyByOid failed for {}: {}", studyOid, e.getMessage());
            return null;
        }
    }

    /** (event_definition_id, crf_id, crf_version_id) tuple for the
     *  BCVA target CRF on the given study_event. */
    private record BcvaCrfTarget(int studyEventDefinitionId, int crfId, int crfVersionId) {}

    /**
     * Walk the study_event's event_definition's CRF list. Return the
     * first CRF version that exposes any of the BCVA probe OIDs.
     * null when no such CRF exists (study admin hasn't authored a
     * BCVA CRF on this event_definition).
     */
    private BcvaCrfTarget resolveBcvaCrfTarget(Connection c, int studyEventId) throws SQLException {
        String sql = "SELECT edc.study_event_definition_id, "
                + "       cv.crf_id, cv.crf_version_id "
                + "  FROM study_event se "
                + "  JOIN event_definition_crf edc "
                + "    ON edc.study_event_definition_id = se.study_event_definition_id "
                + "  JOIN crf_version cv ON cv.crf_id = edc.crf_id "
                + " WHERE se.study_event_id = ? "
                + "   AND EXISTS ( "
                + "        SELECT 1 FROM item_form_metadata ifm "
                + "          JOIN item i ON i.item_id = ifm.item_id "
                + "         WHERE ifm.crf_version_id = cv.crf_version_id "
                + "           AND i.name IN ('OD_BCVA_DECIMAL','OS_BCVA_DECIMAL','OD_BCVA_LETTERS','OS_BCVA_LETTERS') "
                + "   ) "
                + " ORDER BY cv.crf_version_id DESC "
                + " LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new BcvaCrfTarget(
                        rs.getInt("study_event_definition_id"),
                        rs.getInt("crf_id"),
                        rs.getInt("crf_version_id"));
            }
        }
    }

    /**
     * Find the existing event_crf for the target CRF version on the
     * named study_event, or create one with a portal-friendly default
     * status. Returns the event_crf_id.
     */
    private int findOrCreateEventCrf(Connection c, int studyEventId, BcvaCrfTarget target) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_crf_id FROM event_crf "
                        + " WHERE study_event_id = ? AND crf_version_id = ? "
                        + "   AND COALESCE(status_id, 0) NOT IN (5, 7) "
                        + " ORDER BY event_crf_id ASC LIMIT 1")) {
            ps.setInt(1, studyEventId);
            ps.setInt(2, target.crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        // Resolve study_subject_id for the new row (event_crf carries
        // it for denormalised lookups in legacy DAOs).
        int studySubjectId;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_subject_id FROM study_event WHERE study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("study_event " + studyEventId + " not found");
                studySubjectId = rs.getInt(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO event_crf ("
                        + "  study_event_id, crf_version_id, "
                        + "  status_id, completion_status_id, "
                        + "  owner_id, date_created, "
                        + "  study_subject_id, "
                        + "  interviewer_name, date_interviewed, "
                        + "  electronic_signature_status, sdv_status, "
                        + "  old_status_id, sdv_update_id) "
                        + "VALUES (?, ?, 1, 1, 0, now(), ?, '', NULL, false, false, 1, 0) "
                        + "RETURNING event_crf_id")) {
            ps.setInt(1, studyEventId);
            ps.setInt(2, target.crfVersionId);
            ps.setInt(3, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("INSERT event_crf returned no id");
                return rs.getInt(1);
            }
        }
    }

    /**
     * Return a map of (item OID → item_id) for the OIDs that exist
     * on the target CRF version. OIDs not on the CRF are silently
     * omitted; the caller skips writes for those.
     */
    private Map<String, Integer> resolveItemIds(Connection c, int crfVersionId,
                                                Set<String> wantedOids) throws SQLException {
        if (wantedOids.isEmpty()) return Map.of();
        // Build a parameterised IN list — OIDs are whitelisted from
        // BCVA_ITEM_OIDS so SQL injection is moot; the explicit
        // PreparedStatement parameterisation is still hygiene.
        String placeholders = wantedOids.stream().map(o -> "?").collect(Collectors.joining(","));
        String sql = "SELECT i.name, i.item_id "
                + "  FROM item_form_metadata ifm "
                + "  JOIN item i ON i.item_id = ifm.item_id "
                + " WHERE ifm.crf_version_id = ? "
                + "   AND i.name IN (" + placeholders + ")";
        Map<String, Integer> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, crfVersionId);
            int idx = 2;
            for (String oid : wantedOids) ps.setString(idx++, oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("name"), rs.getInt("item_id"));
                }
            }
        }
        return out;
    }

    /**
     * INSERT a fresh item_data row OR UPDATE the existing one for
     * the (event_crf_id, item_id, ordinal=1) tuple. Returns the
     * item_data_id either way.
     *
     * <p>Status / ownership: portal writes land in {@code status_id = 1}
     * (available); {@code owner_id = 0} (portal sentinel); {@code ordinal = 1}
     * (BCVA items are single-row, no repeating group).
     */
    private int upsertItemData(Connection c, int eventCrfId, int itemId, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_data_id FROM item_data "
                        + " WHERE event_crf_id = ? AND item_id = ? AND COALESCE(ordinal, 1) = 1 "
                        + "   AND COALESCE(deleted, false) = false "
                        + " ORDER BY item_data_id ASC LIMIT 1")) {
            ps.setInt(1, eventCrfId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int idataId = rs.getInt(1);
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE item_data SET value = ?, date_updated = now(), update_id = 0 "
                                    + " WHERE item_data_id = ?")) {
                        upd.setString(1, value);
                        upd.setInt(2, idataId);
                        upd.executeUpdate();
                    }
                    return idataId;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO item_data ("
                        + "  item_id, event_crf_id, status_id, value, "
                        + "  date_created, owner_id, ordinal, deleted, source_kind) "
                        + "VALUES (?, ?, 1, ?, now(), 0, 1, false, 'bcva_portal') "
                        + "RETURNING item_data_id")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("INSERT item_data returned no id");
                return rs.getInt(1);
            }
        }
    }

    /**
     * Emit the BCVA_ENTRY_PUBLIC audit row. user_id NULL per the
     * trust-the-reverse-proxy posture; the operator-supplied
     * "entered by" name lands in old_value so the audit timeline
     * surfaces it. new_value carries the written item_data ids so
     * a future undo path can locate them.
     */
    private long writeAuditRow(Connection c, int eventCrfId, String enteredBy,
                               int studyEventId, List<Integer> writtenItemDataIds) throws SQLException {
        String oldValue = "enteredBy=" + enteredBy + ";studyEventId=" + studyEventId;
        String newValue = writtenItemDataIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                        + "  user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), NULL, 'event_crf', ?, 'item_data_ids', ?, ?) "
                        + "RETURNING audit_id")) {
            ps.setInt(1, AuditTypeIds.BCVA_ENTRY_PUBLIC);
            ps.setInt(2, eventCrfId);
            ps.setString(3, oldValue);
            ps.setString(4, newValue);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("INSERT audit_log_event returned no id");
                return rs.getLong(1);
            }
        }
    }

    /**
     * Render any JSON-deserialised value into the {@code item_data.value}
     * text shape. Numbers + booleans render via {@code toString}; null
     * / blank → null.
     */
    private static String renderValue(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.trim().isEmpty() ? null : s.trim();
        if (v instanceof Number n) {
            // Strip trailing ".0" so an integer-valued double renders
            // cleanly (the legacy CRF entry view uses string equality
            // against displayed values).
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return Long.toString((long) d);
            }
            return n.toString();
        }
        return String.valueOf(v).trim();
    }

    /* ====================================================================== */
    /* DTOs                                                                  */
    /* ====================================================================== */

    /** Commit request body. Jackson populates this via setters. */
    public static final class CommitRequest {
        public Integer studyEventId;
        public String enteredBy;
        public Map<String, Object> values;

        @SuppressWarnings("unused")
        public Integer getStudyEventId() { return studyEventId; }
        @SuppressWarnings("unused")
        public void setStudyEventId(Integer studyEventId) { this.studyEventId = studyEventId; }
        @SuppressWarnings("unused")
        public String getEnteredBy() { return enteredBy; }
        @SuppressWarnings("unused")
        public void setEnteredBy(String enteredBy) { this.enteredBy = enteredBy; }
        @SuppressWarnings("unused")
        public Map<String, Object> getValues() { return values; }
        @SuppressWarnings("unused")
        public void setValues(Map<String, Object> values) { this.values = values; }
    }

    // Convenience unused — silences IDE warnings about unused imports
    // when this controller is built in isolation. The Locale import
    // is reserved for future locale-aware error-message rendering.
    @SuppressWarnings("unused")
    private static final Locale RESERVED = Locale.GERMAN;
    @SuppressWarnings("unused")
    private static final Set<Integer> RESERVED_STATUSES = ENTRY_OK_STATUS_IDS;
    @SuppressWarnings("unused")
    private static final Set<String> RESERVED_PROBE_OIDS = BCVA_PROBE_OIDS;
}
