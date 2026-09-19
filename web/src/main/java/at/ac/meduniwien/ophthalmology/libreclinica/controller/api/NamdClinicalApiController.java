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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.EventCrfEnsurer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.CrtComputeService;

import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * P3.6 — the clinical readings a visit carries, apart from the AI's.
 *
 * <p>BCVA per visit, the per-eye observation flags the treat-and-extend rules
 * read, and the central retinal thickness timeline. These lived in the retinal
 * controller because the nAMD module's charts were their first caller, but
 * none of them is about an inference job: BCVA is measured by a person, the
 * flags are recorded by one, and CRT is computed from layer surfaces that may
 * have been corrected by hand.
 *
 * <p>Paths are unchanged. The split is a move, and a move that alters what an
 * endpoint answers is not one.
 *
 * <p>Blinding still applies here even though nothing on these paths is an AI
 * artifact: the CRT timeline names the job whose layers produced each figure,
 * and for a subject in the hidden arm that is the AI's work showing through a
 * clinical endpoint.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "nAMD clinical",
     description = "BCVA, clinical flags and CRT per visit.")
@SuppressWarnings("null")
public class NamdClinicalApiController {

    private static final Logger LOG = LoggerFactory.getLogger(NamdClinicalApiController.class);

    private final DataSource dataSource;
    private final StudyResourceAccess access;

    /**
     * Nullable so a deployment without the metrics service still serves every
     * other endpoint here; the CRT path guards on it.
     */
    @Autowired(required = false)
    private CrtComputeService crtComputeService;

    @Autowired
    public NamdClinicalApiController(@Qualifier("dataSource") DataSource dataSource,
                                     SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.access = new StudyResourceAccess(dataSource, siteVisibilityFilter);
    }

    /** Test seam for the CRT path. */
    void setCrtComputeService(CrtComputeService crtComputeService) {
        this.crtComputeService = crtComputeService;
    }

    /* ====================================================================== */
    /* GET /study-subjects/{studySubjectId}/bcva-timeline                      */
    /* BCVA values per visit                                                   */
    /* ====================================================================== */

    /**
     * Per-subject BCVA timeline. Returns one row per study_event for
     * which the subject has at least one populated BCVA item. Each
     * row carries the per-eye trio {@code (decimal, partial, letters)};
     * which subset is populated depends on which BCVA preset the
     * study used (decimal preset → decimal + partial; legacy letters
     * preset → letters).
     *
     * <p>Backs the nAMD module's trend chart + Bericht history table:
     * the SPA converts decimal+partial → letters via the shared
     * {@code bcvaConversion.ts} utility when the row carries the
     * decimal-flavoured fields; the letters field is consumed
     * directly for legacy studies. The raw form (canonical
     * {@code 1,0p-2} / {@code 0,8+2}) is reconstructed SPA-side
     * for tooltip / audit display.
     */
    @GetMapping(path = "/study-subjects/{studySubjectId:[0-9]+}/bcva-timeline",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listBcvaTimeline(@PathVariable("studySubjectId") int studySubjectId,
                                              HttpSession session) {
        ResponseEntity<?> denied = access.guardSession(session);
        if (denied != null) return denied;
        Integer subjectStudyId;
        try (Connection c = dataSource.getConnection()) {
            subjectStudyId = access.studyIdForStudySubject(studySubjectId);
        } catch (SQLException sqlEx) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for subject: " + sqlEx.getMessage()));
        }
        if (subjectStudyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "study_subject " + studySubjectId + " not found"));
        }
        ResponseEntity<?> visGuard = access.guardStudyVisibilityAllowingDeepLink(subjectStudyId, session,
                "study_subject " + studySubjectId + " is outside your site visibility");
        if (visGuard != null) return visGuard;

        // Pivot in Java — one SELECT, group by study_event_id, fold
        // each (eye, oid) row into the per-eye trio.
        // 2026-06-24 — covers both OID families: SPA-side BCVA preset
        // (OD_BCVA_*, OS_BCVA_*) AND institutional Ophthalmology Visit
        // CRF (VA_O*_ETDRS / VA_O*_LOGMAR). A row may come from either
        // (or both, if a multi-section CRF has all of them).
        String sql = "SELECT se.study_event_id, "
                + "       date(se.date_start) AS event_date, "
                + "       i.name AS oid, "
                + "       idata.value AS value "
                + "  FROM item_data idata "
                + "  JOIN event_crf ec ON ec.event_crf_id = idata.event_crf_id "
                + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  JOIN item i ON i.item_id = idata.item_id "
                + " WHERE ec.study_subject_id = ? "
                + "   AND COALESCE(idata.deleted, false) = false "
                + "   AND idata.value IS NOT NULL AND idata.value <> '' "
                + "   AND i.name IN ('OD_BCVA_DECIMAL','OS_BCVA_DECIMAL', "
                + "                   'OD_BCVA_PARTIAL','OS_BCVA_PARTIAL', "
                + "                   'OD_BCVA_LETTERS','OS_BCVA_LETTERS', "
                + "                   'VA_OD_ETDRS','VA_OS_ETDRS', "
                + "                   'VA_OD_LOGMAR','VA_OS_LOGMAR') "
                + " ORDER BY se.date_start ASC, se.study_event_id ASC";
        // Per-event accumulator: { studyEventId → { eventDate, od:{}, os:{} } }
        Map<Integer, Map<String, Object>> byEvent = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sev = rs.getInt("study_event_id");
                    Map<String, Object> row = byEvent.computeIfAbsent(sev, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("studyEventId", k);
                        try {
                            java.sql.Date ed = rs.getDate("event_date");
                            m.put("eventDate", ed == null ? null : ed.toString());
                        } catch (SQLException ignored) {
                            m.put("eventDate", null);
                        }
                        Map<String, Object> od = new LinkedHashMap<>();
                        od.put("decimal", null); od.put("partial", null); od.put("letters", null);
                        Map<String, Object> os = new LinkedHashMap<>();
                        os.put("decimal", null); os.put("partial", null); os.put("letters", null);
                        m.put("od", od);
                        m.put("os", os);
                        return m;
                    });
                    String oid = rs.getString("oid");
                    String value = rs.getString("value");
                    // 2026-06-24 — both OID conventions encode the eye
                    // in a prefix: SPA-side uses `OD_*` / `OS_*`,
                    // institutional uses `VA_OD_*` / `VA_OS_*` (and
                    // `REFRACT_OD_*` / `REFRACT_OS_*`). Eye detection
                    // tolerates both.
                    String eyeKey = (oid.startsWith("OD_") || oid.contains("_OD_") || oid.startsWith("VA_OD") )
                            ? "od" : "os";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> eyeRow = (Map<String, Object>) row.get(eyeKey);
                    if (oid.endsWith("_DECIMAL")) {
                        eyeRow.put("decimal", parseDoubleOrNull(value));
                    } else if (oid.endsWith("_PARTIAL")) {
                        eyeRow.put("partial", parseIntOrNull(value));
                    } else if (oid.endsWith("_LETTERS") || oid.endsWith("_ETDRS")) {
                        eyeRow.put("letters", parseIntOrNull(value));
                    } else if (oid.endsWith("_LOGMAR")) {
                        // logMAR → decimal: decimal = 10^(-logMAR).
                        // Surfaces in the response only when there's no
                        // direct decimal write (a CRF-Decimal entry
                        // wins because it lands earlier in the loop).
                        Double logmar = parseDoubleOrNull(value);
                        if (logmar != null && eyeRow.get("decimal") == null) {
                            eyeRow.put("decimal", Math.pow(10.0, -logmar));
                        }
                    }
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list BCVA timeline for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list BCVA timeline: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(new ArrayList<>(byEvent.values()));
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return null; }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) {
            // Some legacy rows might store as a real; try a tolerant
            // path before giving up.
            Double d = parseDoubleOrNull(s);
            return d == null ? null : (int) Math.round(d);
        }
    }

    /* ====================================================================== */
    /* GET /study-subjects/{studySubjectId}/namd-clinical-flags                */
    /* Per-event per-eye observation booleans for the rule engine.             */
    /* ====================================================================== */

    /**
     * Per-subject nAMD clinical-flags timeline. Returns one row per
     * study_event for which any of the four observation flags is set:
     * {@code NAMD_OD_NEW_HEMORRHAGE}, {@code NAMD_OS_NEW_HEMORRHAGE},
     * {@code NAMD_OD_BCVA_LOSS_NAMD_ATTRIBUTED},
     * {@code NAMD_OS_BCVA_LOSS_NAMD_ATTRIBUTED}.
     *
     * <p>The SPA rule engine consumes this alongside the BCVA + CRT
     * timelines to derive the SHORTEN / KEEP / EXTEND recommendation
     * per visit. Missing rows (no flag set) are omitted from the
     * response; the SPA treats missing as {@code false}.
     *
     * <p>Same auth posture as {@link #listBcvaTimeline}: session +
     * site-visibility filter on the subject's study. Tolerant — a
     * subject with zero flag rows returns an empty array.
     */
    @GetMapping(path = "/study-subjects/{studySubjectId:[0-9]+}/namd-clinical-flags",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listNamdClinicalFlagsTimeline(@PathVariable("studySubjectId") int studySubjectId,
                                                           HttpSession session) {
        ResponseEntity<?> denied = access.guardSession(session);
        if (denied != null) return denied;
        Integer subjectStudyId;
        try (Connection c = dataSource.getConnection()) {
            subjectStudyId = access.studyIdForStudySubject(studySubjectId);
        } catch (SQLException sqlEx) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for subject: " + sqlEx.getMessage()));
        }
        if (subjectStudyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "study_subject " + studySubjectId + " not found"));
        }
        ResponseEntity<?> visGuard = access.guardStudyVisibilityAllowingDeepLink(subjectStudyId, session,
                "study_subject " + studySubjectId + " is outside your site visibility");
        if (visGuard != null) return visGuard;

        String sql = "SELECT se.study_event_id, "
                + "       date(se.date_start) AS event_date, "
                + "       i.name AS oid, "
                + "       idata.value AS value "
                + "  FROM item_data idata "
                + "  JOIN event_crf ec ON ec.event_crf_id = idata.event_crf_id "
                + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  JOIN item i ON i.item_id = idata.item_id "
                + " WHERE ec.study_subject_id = ? "
                + "   AND COALESCE(idata.deleted, false) = false "
                + "   AND idata.value IS NOT NULL AND idata.value <> '' "
                + "   AND i.name IN ('NAMD_OD_NEW_HEMORRHAGE','NAMD_OS_NEW_HEMORRHAGE', "
                + "                   'NAMD_OD_BCVA_LOSS_NAMD_ATTRIBUTED', "
                + "                   'NAMD_OS_BCVA_LOSS_NAMD_ATTRIBUTED') "
                + " ORDER BY se.date_start ASC, se.study_event_id ASC";

        Map<Integer, Map<String, Object>> byEvent = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sev = rs.getInt("study_event_id");
                    Map<String, Object> row = byEvent.computeIfAbsent(sev, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("studyEventId", k);
                        try {
                            java.sql.Date ed = rs.getDate("event_date");
                            m.put("eventDate", ed == null ? null : ed.toString());
                        } catch (SQLException ignored) {
                            m.put("eventDate", null);
                        }
                        Map<String, Object> od = new LinkedHashMap<>();
                        od.put("hemorrhage", false);
                        od.put("bcvaLossAttributedToNamd", false);
                        Map<String, Object> os = new LinkedHashMap<>();
                        os.put("hemorrhage", false);
                        os.put("bcvaLossAttributedToNamd", false);
                        m.put("od", od);
                        m.put("os", os);
                        return m;
                    });
                    String oid = rs.getString("oid");
                    String value = rs.getString("value");
                    boolean truthy = "true".equalsIgnoreCase(value) || "1".equals(value)
                            || "yes".equalsIgnoreCase(value);
                    String eyeKey = oid.contains("_OD_") ? "od" : "os";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> eyeRow = (Map<String, Object>) row.get(eyeKey);
                    if (oid.endsWith("_NEW_HEMORRHAGE")) {
                        eyeRow.put("hemorrhage", truthy);
                    } else if (oid.endsWith("_BCVA_LOSS_NAMD_ATTRIBUTED")) {
                        eyeRow.put("bcvaLossAttributedToNamd", truthy);
                    }
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list nAMD clinical flags for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list nAMD clinical flags: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(new ArrayList<>(byEvent.values()));
    }

    /* ====================================================================== */
    /* POST /study-events/{studyEventId}/namd-clinical-flags                   */
    /* Upsert the per-eye clinical-flag observations for a visit.              */
    /* ====================================================================== */

    /**
     * 2026-07-06 — Persist the nAMD clinical-flag observations for one
     * study_event. Fills the write-side gap of {@link #listNamdClinicalFlagsTimeline}:
     * the GET timeline lets the rec engine consume the flags, but until
     * this endpoint shipped nothing let the physician set them from the
     * nAMD workspace UI.
     *
     * <p>Behaviour:
     *
     * <ol>
     *   <li>Guards session + study visibility on the subject's study.</li>
     *   <li>Finds an existing {@code event_crf} row for
     *     {@code (study_event_id, F_NAMD_VISIT crf_version)}; if none,
     *     creates one so a fresh visit can carry the flags without the
     *     physician manually opening the CRF in the legacy UI first.</li>
     *   <li>Upserts {@code item_data} for each supplied per-eye flag
     *     (the request body carries {@code od} and/or {@code os}; either
     *     or both may be omitted).</li>
     * </ol>
     *
     * <p>Response returns the resolved {@code eventCrfId} so the SPA
     * can update its local {@code NamdVisit.eventCrfId} without another
     * roundtrip.
     */
    @PostMapping(path = "/study-events/{studyEventId:[0-9]+}/namd-clinical-flags",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> upsertNamdClinicalFlags(
            @PathVariable("studyEventId") int studyEventId,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        ResponseEntity<?> denied = access.guardSession(session);
        if (denied != null) return denied;
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);

            // The visit has to exist and be visible before anything is written.
            // Its study_subject_id used to be read here for the event_crf insert;
            // EventCrfEnsurer resolves that itself now.
            int studyId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ss.study_id "
                            + "  FROM study_event se "
                            + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                            + " WHERE se.study_event_id = ?")) {
                ps.setInt(1, studyEventId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return ResponseEntity.status(404).body(Map.of(
                                "message", "study_event " + studyEventId + " not found"));
                    }
                    studyId = rs.getInt(1);
                }
            }
            ResponseEntity<?> visGuard = access.guardStudyVisibilityAllowingDeepLink(studyId, session,
                    "study_event " + studyEventId + " is outside your site visibility");
            if (visGuard != null) return visGuard;

            // Resolve F_NAMD_VISIT crf_version_id. The demo seed ships
            // exactly one version; production configurations may add
            // more, in which case the latest wins.
            int crfVersionId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cv.crf_version_id "
                            + "  FROM crf_version cv "
                            + "  JOIN crf c ON c.crf_id = cv.crf_id "
                            + " WHERE c.oc_oid = 'F_NAMD_VISIT' "
                            + " ORDER BY cv.crf_version_id DESC LIMIT 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return ResponseEntity.status(500).body(Map.of(
                                "message", "F_NAMD_VISIT CRF version not found"));
                    }
                    crfVersionId = rs.getInt(1);
                }
            }

            // Find or create the event_crf row for (study_event, crf_version).
            // P3.0 — shared with the BCVA portal, which had the same statement.
            EventCrfEnsurer.Instance instance =
                    EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, currentUser.getId());
            if (instance.removed()) {
                // This used to write into the removed form, which revived it.
                // The unique constraint rules out a replacement instance, so
                // there is nowhere legitimate for these flags to go; say so
                // rather than undoing somebody's removal on their behalf.
                c.rollback();
                return ResponseEntity.status(409).body(Map.of(
                        "message", "The visit CRF has been removed; restore it before saving flags"));
            }
            int eventCrfId = instance.eventCrfId();

            // Resolve item_ids for the four per-eye flag items on this CRF version.
            Map<String, Integer> itemIds = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT i.name, i.item_id "
                            + "  FROM item_form_metadata ifm "
                            + "  JOIN item i ON i.item_id = ifm.item_id "
                            + " WHERE ifm.crf_version_id = ? "
                            + "   AND i.name IN ('NAMD_OD_NEW_HEMORRHAGE','NAMD_OS_NEW_HEMORRHAGE',"
                            + "                   'NAMD_OD_BCVA_LOSS_NAMD_ATTRIBUTED',"
                            + "                   'NAMD_OS_BCVA_LOSS_NAMD_ATTRIBUTED')")) {
                ps.setInt(1, crfVersionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) itemIds.put(rs.getString(1), rs.getInt(2));
                }
            }

            // Walk the body's per-eye maps + upsert each provided flag.
            Map<String, Object> od = castMap(body.get("od"));
            Map<String, Object> os = castMap(body.get("os"));
            upsertFlag(c, itemIds, "NAMD_OD_NEW_HEMORRHAGE", od, "hemorrhage",
                    eventCrfId, currentUser.getId());
            upsertFlag(c, itemIds, "NAMD_OD_BCVA_LOSS_NAMD_ATTRIBUTED", od,
                    "bcvaLossAttributedToNamd", eventCrfId, currentUser.getId());
            upsertFlag(c, itemIds, "NAMD_OS_NEW_HEMORRHAGE", os, "hemorrhage",
                    eventCrfId, currentUser.getId());
            upsertFlag(c, itemIds, "NAMD_OS_BCVA_LOSS_NAMD_ATTRIBUTED", os,
                    "bcvaLossAttributedToNamd", eventCrfId, currentUser.getId());

            c.commit();

            LOG.info("nAMD clinical flags saved: study_event={} event_crf={} by user={}",
                    studyEventId, eventCrfId, currentUser.getName());

            return ResponseEntity.ok(Map.of(
                    "studyEventId", studyEventId,
                    "eventCrfId", eventCrfId
            ));
        } catch (SQLException sqlEx) {
            LOG.error("Failed to upsert nAMD clinical flags for study_event {}: {}",
                    studyEventId, sqlEx.getMessage(), sqlEx);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to save nAMD clinical flags: " + sqlEx.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    /**
     * Upsert one item_data row for the given flag. The uniqueness
     * constraint {@code (item_id, event_crf_id, ordinal)} lets us do
     * this in one statement via ON CONFLICT; ordinal is fixed at 1
     * for these non-repeating items.
     *
     * <p>Skips silently when the CRF version doesn't carry that item
     * (defensive — production installs may drift from the demo seed).
     */
    private static void upsertFlag(Connection c, Map<String, Integer> itemIds,
                                   String itemName, Map<String, Object> eyeBody,
                                   String bodyKey, int eventCrfId, int userId) throws SQLException {
        Integer itemId = itemIds.get(itemName);
        if (itemId == null) return;
        if (eyeBody == null || !eyeBody.containsKey(bodyKey)) return;
        Object raw = eyeBody.get(bodyKey);
        String value = raw instanceof Boolean ? String.valueOf(raw) : String.valueOf(raw);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO item_data ("
                        + "  item_id, event_crf_id, status_id, value, "
                        + "  date_created, owner_id, ordinal, deleted) "
                        + "VALUES (?, ?, 1, ?, now(), ?, 1, false) "
                        + "ON CONFLICT (item_id, event_crf_id, ordinal) DO UPDATE "
                        + "SET value = EXCLUDED.value, "
                        + "    date_updated = now(), "
                        + "    update_id = EXCLUDED.owner_id, "
                        + "    deleted = false")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, value);
            ps.setInt(4, userId);
            ps.executeUpdate();
        }
    }

    /* ====================================================================== */
    /* GET /study-subjects/{studySubjectId}/crt-timeline                       */
    /* central 1 mm retinal thickness                                          */
    /* ====================================================================== */

    /**
     * Per-subject CRT timeline. Returns one row per study_event for
     * which the GA + BM jobs are both {@code done} for at least one
     * eye. Each row carries per-eye {@code crt_um} plus the source
     * job ids so the SPA can deep-link the operator to either of the
     * two artifacts.
     *
     * <p>Same auth posture as {@link #listBcvaTimeline}: session +
     * site-visibility filter on the subject's study. Soft-fails — a
     * missing GA or BM on one eye returns null for that eye rather
     * than erroring out the whole timeline.
     */
    @GetMapping(path = "/study-subjects/{studySubjectId:[0-9]+}/crt-timeline",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listCrtTimeline(@PathVariable("studySubjectId") int studySubjectId,
                                             HttpSession session) {
        ResponseEntity<?> denied = access.guardSession(session);
        if (denied != null) return denied;
        if (crtComputeService == null) {
            // Test-only ctor path with a null CRT service. Be explicit
            // rather than 500ing on NPE.
            return ResponseEntity.status(501).body(Map.of(
                    "message", "CRT compute service is not wired in this context"));
        }
        Integer subjectStudyId;
        try (Connection c = dataSource.getConnection()) {
            subjectStudyId = access.studyIdForStudySubject(studySubjectId);
        } catch (SQLException sqlEx) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for subject: " + sqlEx.getMessage()));
        }
        if (subjectStudyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "study_subject " + studySubjectId + " not found"));
        }
        ResponseEntity<?> visGuard = access.guardStudyVisibilityAllowingDeepLink(subjectStudyId, session,
                "study_subject " + studySubjectId + " is outside your site visibility");
        if (visGuard != null) return visGuard;

        // Trial blinding — CRT/CST is derived from the AI layer segmentation;
        // a treating clinician on an AI_HIDDEN subject gets an empty timeline.
        if (AiArmPolicy.isTreatingRole(session)) {
            String arm = null;
            try (Connection c = dataSource.getConnection()) {
                arm = AiArmPolicy.armForSubject(c, studySubjectId);
            } catch (SQLException e) {
                LOG.warn("arm lookup failed for study_subject {}: {}", studySubjectId, e.getMessage());
            }
            if (AiArmPolicy.maskAiFor(arm, session)) {
                return ResponseEntity.ok(List.of());
            }
        }

        // Pull every study_event the subject has where at least one
        // CRT-source done job exists. 2026-06-25 — the supported source
        // tasks are now {layers, ga, bm}: the consolidated `layers` task
        // returns both ILM + BM in one job (the post-refactor default
        // for RIS uploads), and the legacy `ga` + `bm` pair stays
        // recognised so historical jobs still surface a timeline entry.
        // Per-eye pairing happens in CrtComputeService.
        String sql = "SELECT DISTINCT se.study_event_id, "
                + "       date(se.date_start) AS event_date "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  JOIN study_event se ON se.study_event_id = COALESCE(ec.study_event_id, j.study_event_id) "
                + " WHERE se.study_subject_id = ? "
                + "   AND j.task IN ('layers','ga','bm') "
                + "   AND j.status IN ('done','succeeded') "
                + " ORDER BY event_date ASC, study_event_id ASC";

        List<Map<String, Object>> out = new ArrayList<>();
        List<int[]> events = new ArrayList<>(); // [eventId, dateMillis-ish ordinal]
        Map<Integer, String> eventDateByEventId = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sev = rs.getInt("study_event_id");
                    java.sql.Date ed = rs.getDate("event_date");
                    eventDateByEventId.put(sev, ed == null ? null : ed.toString());
                    events.add(new int[]{sev});
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list CRT-eligible events for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list CRT timeline: " + sqlEx.getMessage()));
        }
        // Per-event per-eye computation. Each event is independent; one
        // failing event doesn't abort the rest.
        for (Map.Entry<Integer, String> e : eventDateByEventId.entrySet()) {
            int eventId = e.getKey();
            Map<CrtComputeService.Eye,
                    CrtComputeService.Result>
                    perEye = crtComputeService.computeForStudyEvent(eventId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studyEventId", eventId);
            row.put("eventDate", e.getValue());
            row.put("od", crtRowOrNull(perEye, at.ac.meduniwien.ophthalmology.libreclinica
                    .service.retinal.metrics.CrtComputeService.Eye.OD));
            row.put("os", crtRowOrNull(perEye, at.ac.meduniwien.ophthalmology.libreclinica
                    .service.retinal.metrics.CrtComputeService.Eye.OS));
            // Only surface events that produced AT LEAST one eye —
            // events where both GA + BM exist but neither paired (e.g.
            // GA done for OD, BM done for OS only) would otherwise
            // surface as a useless empty row.
            if (row.get("od") != null || row.get("os") != null) {
                out.add(row);
            }
        }
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> crtRowOrNull(
            Map<CrtComputeService.Eye,
                    CrtComputeService.Result> perEye,
            CrtComputeService.Eye eye) {
        var r = perEye.get(eye);
        if (r == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("crtMicrons", Math.round(r.crtMicrons() * 100.0) / 100.0); // 2 decimals
        out.put("pixelsInDisk", r.pixelsInDisk());
        out.put("layersJobId", r.layersJobId());
        return out;
    }
}
