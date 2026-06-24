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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
@RestController
@RequestMapping("/api/v1/public/bcva-entry")
public class PublicBcvaEntryController {

    private static final Logger LOG = LoggerFactory.getLogger(PublicBcvaEntryController.class);

    /**
     * Item OIDs the portal payload may carry. SPA-side OID convention —
     * the server maps each into the institutional OIDs the target CRF
     * actually exposes via {@link #OID_BY_FIELD} (see below).
     */
    private static final Set<String> BCVA_ITEM_OIDS = Set.of(
            "OD_BCVA_DECIMAL", "OS_BCVA_DECIMAL",
            "OD_BCVA_PARTIAL", "OS_BCVA_PARTIAL",
            "OD_BCVA_LETTERS", "OS_BCVA_LETTERS",
            "OD_BCVA_REFRACTION_SPHERE", "OS_BCVA_REFRACTION_SPHERE",
            "OD_BCVA_REFRACTION_CYLINDER", "OS_BCVA_REFRACTION_CYLINDER",
            "OD_BCVA_REFRACTION_AXIS", "OS_BCVA_REFRACTION_AXIS"
    );

    /**
     * 2026-06-24 — every item OID the resolver recognises as "this CRF
     * captures BCVA". Covers both the OPHTH-Visit institutional schema
     * (seeded in lc-muw-2026-06-05-ophth-visit-crf-seed.xml — {@code
     * VA_OD_ETDRS} / {@code VA_OS_ETDRS} / {@code VA_OD_LOGMAR} /
     * {@code VA_OS_LOGMAR}) AND the SPA-side BCVA preset (decimal /
     * letters per eye). The CRF only needs ONE of these for the
     * portal to write into it.
     */
    private static final Set<String> BCVA_PROBE_OIDS = Set.of(
            "OD_BCVA_DECIMAL", "OS_BCVA_DECIMAL",
            "OD_BCVA_LETTERS", "OS_BCVA_LETTERS",
            "VA_OD_ETDRS",     "VA_OS_ETDRS",
            "VA_OD_LOGMAR",    "VA_OS_LOGMAR"
    );

    /**
     * 2026-06-24 — semantic field model. Each enum value identifies
     * one (eye, attribute) pair the portal captures. The controller
     * materialises a per-submission {@code Map<Field, Double>} from
     * the SPA payload and then writes into the target CRF's item rows
     * via {@link #OID_BY_FIELD} (one OID family per Field).
     */
    private enum Field {
        OD_DECIMAL, OS_DECIMAL,
        OD_PARTIAL, OS_PARTIAL,
        OD_LETTERS, OS_LETTERS,
        OD_LOGMAR,  OS_LOGMAR,
        OD_SPHERE,  OS_SPHERE,
        OD_CYLINDER, OS_CYLINDER,
        OD_AXIS,    OS_AXIS
    }

    /**
     * Synonym table — each semantic field maps to the set of item
     * OIDs that could carry the value. Multiple OIDs per field cover
     * (a) the SPA-side BCVA-Decimal preset OIDs and (b) the
     * institutional Ophthalmology Visit OIDs. The controller writes
     * to EVERY OID in the set that the target CRF version actually
     * exposes — multi-preset CRFs land the value redundantly so
     * downstream consumers (the timeline endpoint, the OPHTH-Visit
     * mark-complete flow, the modality config) all see the same
     * value.
     */
    private static final Map<Field, Set<String>> OID_BY_FIELD = Map.ofEntries(
            Map.entry(Field.OD_DECIMAL,  Set.of("OD_BCVA_DECIMAL")),
            Map.entry(Field.OS_DECIMAL,  Set.of("OS_BCVA_DECIMAL")),
            Map.entry(Field.OD_PARTIAL,  Set.of("OD_BCVA_PARTIAL")),
            Map.entry(Field.OS_PARTIAL,  Set.of("OS_BCVA_PARTIAL")),
            Map.entry(Field.OD_LETTERS,  Set.of("OD_BCVA_LETTERS", "VA_OD_ETDRS")),
            Map.entry(Field.OS_LETTERS,  Set.of("OS_BCVA_LETTERS", "VA_OS_ETDRS")),
            Map.entry(Field.OD_LOGMAR,   Set.of("VA_OD_LOGMAR")),
            Map.entry(Field.OS_LOGMAR,   Set.of("VA_OS_LOGMAR")),
            Map.entry(Field.OD_SPHERE,   Set.of("OD_BCVA_REFRACTION_SPHERE",   "REFRACT_OD_SPH")),
            Map.entry(Field.OS_SPHERE,   Set.of("OS_BCVA_REFRACTION_SPHERE",   "REFRACT_OS_SPH")),
            Map.entry(Field.OD_CYLINDER, Set.of("OD_BCVA_REFRACTION_CYLINDER", "REFRACT_OD_CYL")),
            Map.entry(Field.OS_CYLINDER, Set.of("OS_BCVA_REFRACTION_CYLINDER", "REFRACT_OS_CYL")),
            Map.entry(Field.OD_AXIS,     Set.of("OD_BCVA_REFRACTION_AXIS",     "REFRACT_OD_AXIS")),
            Map.entry(Field.OS_AXIS,     Set.of("OS_BCVA_REFRACTION_AXIS",     "REFRACT_OS_AXIS"))
    );

    /** Inverse lookup: every OID the controller knows about, flat. */
    private static final Set<String> ALL_KNOWN_OIDS = OID_BY_FIELD.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());

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
        // Build the IN-list of every OID that identifies a BCVA-flavoured
        // CRF version. Quoted single-quote SQL literal because the
        // outer string is mechanically composed; the values come from
        // a compile-time-constant Set, never user input.
        String probeInList = BCVA_PROBE_OIDS.stream()
                .map(o -> "'" + o + "'")
                .collect(Collectors.joining(","));
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
                + "       AND i.name IN (" + probeInList + ") "
                + "     ORDER BY ec.event_crf_id ASC LIMIT 1 ) AS event_crf_id, "
                + "  EXISTS ( "
                + "     SELECT 1 FROM item_data idata "
                + "       JOIN event_crf ec ON ec.event_crf_id = idata.event_crf_id "
                + "       JOIN item i ON i.item_id = idata.item_id "
                + "      WHERE ec.study_event_id = se.study_event_id "
                + "        AND i.name IN (" + probeInList + ") "
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

                // 3. Project the SPA payload into the semantic field
                //    model (decimal/partial/letters/logMAR/refraction
                //    per eye), deriving letters + logMAR from the
                //    decimal+partial pair so they're available for
                //    institutional-schema CRFs that don't capture
                //    decimal directly.
                Map<Field, String> valueByField = projectPayloadToFields(body.values);

                // 4. Resolve item_id for every OID the CRF version
                //    actually exposes (across both SPA + institutional
                //    families). Anything the payload would write into
                //    an OID the CRF doesn't expose is simply skipped.
                Set<String> wantedOids = new HashSet<>();
                for (Map.Entry<Field, String> e : valueByField.entrySet()) {
                    if (e.getValue() == null) continue;
                    wantedOids.addAll(OID_BY_FIELD.getOrDefault(e.getKey(), Set.of()));
                }
                Map<String, Integer> itemIdByOid = resolveItemIds(
                        c, target.crfVersionId, wantedOids);

                // 5. Upsert each value into every CRF-exposed OID for
                //    its semantic field + collect the item_data ids so
                //    the audit row can surface them.
                List<Integer> writtenItemDataIds = new ArrayList<>();
                for (Map.Entry<Field, String> e : valueByField.entrySet()) {
                    String value = e.getValue();
                    if (value == null || value.isBlank()) continue;
                    for (String oid : OID_BY_FIELD.getOrDefault(e.getKey(), Set.of())) {
                        Integer itemId = itemIdByOid.get(oid);
                        if (itemId == null) continue;
                        int itemDataId = upsertItemData(c, eventCrfId, itemId, value);
                        writtenItemDataIds.add(itemDataId);
                    }
                }
                if (writtenItemDataIds.isEmpty()) {
                    c.rollback();
                    return ResponseEntity.badRequest().body(Map.of(
                            "message", "No supplied field matched any item the target CRF exposes — nothing written"));
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
        String probeInList = BCVA_PROBE_OIDS.stream()
                .map(o -> "'" + o + "'")
                .collect(Collectors.joining(","));
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
                + "           AND i.name IN (" + probeInList + ") "
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
        // owner_id resolves to the lowest-id active root account (typically
        // user_id=1, 'root'). event_crf has a FK constraint into
        // user_account, so a sentinel like 0 fails the insert.
        // Production deployments should swap this for a dedicated
        // portal-service account once one exists; the audit row already
        // carries the operator-supplied "entered by" name so the
        // human-attribution is preserved either way.
        int portalOwnerId = resolvePortalOwnerId(c);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO event_crf ("
                        + "  study_event_id, crf_version_id, "
                        + "  status_id, completion_status_id, "
                        + "  owner_id, date_created, "
                        + "  study_subject_id, "
                        + "  interviewer_name, date_interviewed, "
                        + "  electronic_signature_status, sdv_status, "
                        + "  old_status_id, sdv_update_id) "
                        + "VALUES (?, ?, 1, 1, ?, now(), ?, '', NULL, false, false, 1, 0) "
                        + "RETURNING event_crf_id")) {
            ps.setInt(1, studyEventId);
            ps.setInt(2, target.crfVersionId);
            ps.setInt(3, portalOwnerId);
            ps.setInt(4, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("INSERT event_crf returned no id");
                return rs.getInt(1);
            }
        }
    }

    /**
     * Resolve the lowest-id active user_account row — the root /
     * institutional admin in the seeded data. Used as a sentinel
     * owner for event_crf rows created by the portal so the FK
     * constraint passes. The audit row written alongside still
     * captures the operator's free-text name; the user_account
     * link here is structural only.
     */
    private int resolvePortalOwnerId(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM user_account "
                        + " WHERE status_id = 1 ORDER BY user_id ASC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        throw new SQLException("no active user_account row available to own portal event_crf");
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
        int portalOwnerId = resolvePortalOwnerId(c);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO item_data ("
                        + "  item_id, event_crf_id, status_id, value, "
                        + "  date_created, owner_id, ordinal, deleted, source_kind) "
                        + "VALUES (?, ?, 1, ?, now(), ?, 1, false, 'bcva_portal') "
                        + "RETURNING item_data_id")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, value);
            ps.setInt(4, portalOwnerId);
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

    /**
     * 2026-06-24 — project the SPA payload (keyed by SPA-side OIDs)
     * into the semantic {@link Field} model. For each eye that
     * supplies a decimal + (optional) partial, derive the
     * letters + logMAR values so institutional-schema CRFs that
     * only capture {@code VA_*_ETDRS} / {@code VA_*_LOGMAR} can be
     * written. Sphere / cylinder / axis pass through unchanged.
     *
     * <p>Letters formula: Bailey-Lovie / Holladay
     *   letters = round(85 + 50 · log10(decimal)) + partial,
     *   clamped to [0, 100].
     * LogMAR formula (clinical convention):
     *   logMAR = (100 − letters) / 50.
     */
    private static Map<Field, String> projectPayloadToFields(Map<String, Object> raw) {
        Map<Field, String> out = new LinkedHashMap<>();
        // Decimal + partial per eye.
        Double odDecimal = readDouble(raw.get("OD_BCVA_DECIMAL"));
        Double osDecimal = readDouble(raw.get("OS_BCVA_DECIMAL"));
        Integer odPartial = readInteger(raw.get("OD_BCVA_PARTIAL"));
        Integer osPartial = readInteger(raw.get("OS_BCVA_PARTIAL"));
        if (odDecimal != null) {
            out.put(Field.OD_DECIMAL, formatDecimal(odDecimal));
            int partial = odPartial == null ? 0 : odPartial;
            if (partial != 0) out.put(Field.OD_PARTIAL, Integer.toString(partial));
            int letters = decimalToLetters(odDecimal, partial);
            out.put(Field.OD_LETTERS, Integer.toString(letters));
            out.put(Field.OD_LOGMAR, formatLogMar(lettersToLogMar(letters)));
        }
        if (osDecimal != null) {
            out.put(Field.OS_DECIMAL, formatDecimal(osDecimal));
            int partial = osPartial == null ? 0 : osPartial;
            if (partial != 0) out.put(Field.OS_PARTIAL, Integer.toString(partial));
            int letters = decimalToLetters(osDecimal, partial);
            out.put(Field.OS_LETTERS, Integer.toString(letters));
            out.put(Field.OS_LOGMAR, formatLogMar(lettersToLogMar(letters)));
        }
        // Refraction: literal pass-through.
        putIfPresent(out, Field.OD_SPHERE,   raw.get("OD_BCVA_REFRACTION_SPHERE"));
        putIfPresent(out, Field.OS_SPHERE,   raw.get("OS_BCVA_REFRACTION_SPHERE"));
        putIfPresent(out, Field.OD_CYLINDER, raw.get("OD_BCVA_REFRACTION_CYLINDER"));
        putIfPresent(out, Field.OS_CYLINDER, raw.get("OS_BCVA_REFRACTION_CYLINDER"));
        putIfPresent(out, Field.OD_AXIS,     raw.get("OD_BCVA_REFRACTION_AXIS"));
        putIfPresent(out, Field.OS_AXIS,     raw.get("OS_BCVA_REFRACTION_AXIS"));
        return out;
    }

    private static void putIfPresent(Map<Field, String> out, Field f, Object v) {
        String rendered = renderValue(v);
        if (rendered != null) out.put(f, rendered);
    }

    private static Double readDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            String trimmed = s.trim().replace(',', '.');
            if (trimmed.isEmpty()) return null;
            try { return Double.parseDouble(trimmed); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Integer readInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return null;
            try { return Integer.parseInt(trimmed); }
            catch (NumberFormatException e) {
                Double d = readDouble(s);
                return d == null ? null : (int) Math.round(d);
            }
        }
        return null;
    }

    /** Bailey-Lovie / Holladay decimal → ETDRS letters with signed partial. */
    static int decimalToLetters(double decimal, int partial) {
        if (decimal <= 0) return 0;
        int base = (int) Math.round(85.0 + 50.0 * Math.log10(decimal));
        return Math.max(0, Math.min(100, base + partial));
    }

    /** Clinical convention: logMAR = (100 − letters) / 50. */
    static double lettersToLogMar(int letters) {
        return (100.0 - letters) / 50.0;
    }

    /** Render a decimal value preserving up to two clinically meaningful
     *  decimal places (so the legacy CRF view's string-equality
     *  comparison still matches). */
    private static String formatDecimal(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.format(java.util.Locale.ROOT, "%.1f", v);
        }
        return java.math.BigDecimal.valueOf(v)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** LogMAR rendered to two decimals — the clinical convention. */
    private static String formatLogMar(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
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
