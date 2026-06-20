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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * App-feedback Wave 1B (2026-06-19) — cross-study patient identity
 * soft-link endpoint. C4 (2026-06-20) — promoted to the hard
 * {@code patient} table FK; the {@code patient_uuid} column is now a
 * soft-deprecated mirror written alongside {@code patient_id} on every
 * UPDATE for one deprecation release.
 *
 * <p>{@code POST /api/v1/study-subjects/{id}/link-patient} ties two
 * {@code study_subject} rows together under a single {@code patient_id}
 * (and the mirrored {@code patient_uuid}), so the cross-study
 * patient-overview view can surface "this human is M-001 in
 * default-study AND GA-008 in GA-Studie" even when the two enrolments
 * live in studies with disjoint subject naming conventions. The
 * {@code patient_uuid} column was seeded by
 * {@code lc-muw-2026-06-19-study-subject-patient-uuid.xml}; the hard
 * {@code patient} table + {@code patient_id} FK by
 * {@code lc-muw-2026-06-20-patient-table.xml}; the audit type id
 * {@link AuditTypeIds#STUDY_SUBJECT_PATIENT_LINKED} (119) by the
 * Wave 1B changeset.
 *
 * <p><strong>Semantics</strong> (the migration guarantees no row has
 * NULL patient_uuid/patient_id after upgrade, but the controller still
 * handles the both-null branch defensively for tests that bypass the
 * migration + for new INSERT paths that might land here before the
 * fresh-patient-mint hook is wired):
 * <ul>
 *   <li>Both rows have NULL {@code patient_uuid} → mint a fresh
 *       {@link UUID#randomUUID()} AND a fresh {@code patient} row, then
 *       apply both to BOTH study_subjects.</li>
 *   <li>Exactly one row has a {@code patient_uuid} → copy that value
 *       (and its existing {@code patient_id}) to the other.</li>
 *   <li>Both rows carry the same {@code patient_uuid} → 200 idempotent
 *       (no audit, no UPDATE).</li>
 *   <li>Both rows carry DIFFERENT {@code patient_uuid}s → 409 Conflict.
 *       Refusing to merge here prevents accidentally collapsing two
 *       independent cross-study patient threads; the operator must
 *       deliberately split + re-link.</li>
 * </ul>
 *
 * <p><strong>Authz:</strong> role gate via
 * {@link SubjectLifecycleAuthorization} (Data Manager / Admin only —
 * mirrors the {@code remove}/{@code restore}/{@code lock} endpoints).
 * Both rows must be visible under the operator's
 * {@link SiteVisibilityFilter} grant tree; a 403 surfaces if either
 * row's {@code study_id} is outside the visible set.
 *
 * <p><strong>Audit:</strong> on every actual write, one
 * {@code audit_log_event} row is emitted per updated {@code study_subject}
 * with {@code audit_log_event_type_id=119}; {@code entity_id} carries
 * the {@code study_subject_id}; {@code new_value} packs
 * {@code "linked-to:<otherSsId>:patientUuid:<uuid>:patientId:<id>"} so
 * the timeline shows the resolved hard id explicitly. The audit
 * emission rides on the same connection as the UPDATEs (single
 * transaction).
 */
@RestController
@RequestMapping("/api/v1/study-subjects")
@Tag(name = "Study Subjects",
     description = "Cross-study patient identity soft-link.")
public class StudySubjectLinkPatientController {

    private static final Logger LOG =
            LoggerFactory.getLogger(StudySubjectLinkPatientController.class);

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public StudySubjectLinkPatientController(
            @Qualifier("dataSource") DataSource dataSource,
            SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    /**
     * Request body for {@link #linkPatient}.
     *
     * @param targetSubjectId study_subject PK to link the path id row to.
     *        Must be a positive int referring to a row distinct from the
     *        path id (a self-link is a 400).
     */
    public record LinkPatientRequest(Integer targetSubjectId) {}

    /**
     * Link two {@code study_subject} rows under a common
     * {@code patient_uuid}.
     *
     * @param id path-bound source study_subject_id
     * @param body { targetSubjectId: <int> }
     * @return 200 with {@code { patientUuid: "<uuid>" }} on success;
     *         400 on missing/blank/self-link target; 401 unauthenticated;
     *         403 role or site-visibility refusal; 404 either row
     *         missing; 409 different non-null patient_uuid mismatch.
     */
    @PostMapping(value = "/{id:[0-9]+}/link-patient",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> linkPatient(@PathVariable("id") int id,
                                         @RequestBody(required = false) LinkPatientRequest body,
                                         HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!SubjectLifecycleAuthorization.roleMayManageLifecycle(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit link-patient on study subjects"));
        }
        if (body == null || body.targetSubjectId() == null
                || body.targetSubjectId().intValue() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "targetSubjectId is required (positive int)."));
        }
        int targetId = body.targetSubjectId().intValue();
        if (targetId == id) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "targetSubjectId must differ from the path id."));
        }

        // ---- load both rows in one connection -------------------------
        LinkRow source;
        LinkRow target;
        try (Connection c = dataSource.getConnection()) {
            source = loadRow(c, id);
            target = loadRow(c, targetId);
        } catch (SQLException sqlEx) {
            LOG.error("Link-patient: failed to load study_subject rows {} + {}: {}",
                    id, targetId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to load study_subject rows — see server log."));
        }
        if (source == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study_subject with id " + id));
        }
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study_subject with id " + targetId));
        }

        // ---- visibility check on BOTH rows ----------------------------
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visible.contains(Integer.valueOf(source.studyId))) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Source study_subject " + id
                            + " belongs to a study outside your grant tree"));
        }
        if (!visible.contains(Integer.valueOf(target.studyId))) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Target study_subject " + targetId
                            + " belongs to a study outside your grant tree"));
        }

        // ---- decide the resulting UUID + which rows to UPDATE ---------
        String sourceUuid = source.patientUuid;
        String targetUuid = target.patientUuid;
        String resolvedUuid;
        boolean updateSource;
        boolean updateTarget;
        boolean mintPatient;

        if (sourceUuid == null && targetUuid == null) {
            // Defensive path — the C4 migration guarantees no row hits
            // production with NULL patient_uuid, but tests + future
            // INSERT paths may. Mint both the UUID and a hard patient
            // row inside the same transaction below.
            resolvedUuid = UUID.randomUUID().toString();
            updateSource = true;
            updateTarget = true;
            mintPatient = true;
        } else if (sourceUuid != null && targetUuid == null) {
            resolvedUuid = sourceUuid;
            updateSource = false;
            updateTarget = true;
            mintPatient = false;
        } else if (sourceUuid == null) { // targetUuid != null
            resolvedUuid = targetUuid;
            updateSource = true;
            updateTarget = false;
            mintPatient = false;
        } else if (sourceUuid.equals(targetUuid)) {
            // Idempotent — both rows already linked under the same UUID.
            // No UPDATE, no audit row. The SPA can re-fire the call
            // without surfacing a misleading "re-linked" toast.
            LOG.info("Link-patient: study_subject {} + {} already share patient_uuid={}",
                    id, targetId, sourceUuid);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("patientUuid", sourceUuid);
            // Surface the resolved patient_id when at least one row has
            // it hydrated (it should — the C4 migration backfilled
            // every row; this is just a defensive null-check in case a
            // future code path inserts a study_subject without it).
            if (source.patientId > 0) {
                resp.put("patientId", source.patientId);
            } else if (target.patientId > 0) {
                resp.put("patientId", target.patientId);
            }
            return ResponseEntity.ok(resp);
        } else {
            // Different non-null UUIDs — refuse. Merging would collapse
            // two independent cross-study patient threads; the operator
            // must deliberately split + re-link.
            LOG.warn("Link-patient refusal: study_subject {} has patient_uuid={}, "
                            + "study_subject {} has patient_uuid={} (mismatch)",
                    id, sourceUuid, targetId, targetUuid);
            return ResponseEntity.status(409).body(Map.of("message",
                    "Both study_subject rows are already linked to different patients."));
        }

        // ---- single transaction: mint patient (if needed) + UPDATE + audit ----
        long resolvedPatientId;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (mintPatient) {
                    // Both study_subjects had NULL — create the patient
                    // row inside this transaction so a rollback discards
                    // the unreferenced patient too.
                    resolvedPatientId = insertPatient(c, resolvedUuid, currentUser.getId());
                } else {
                    // Resolve the hard FK from whichever side held the
                    // existing patient_uuid. The migration guarantees
                    // this lookup hits a row when the uuid is non-null.
                    resolvedPatientId = resolvePatientIdByUuid(c, resolvedUuid);
                    if (resolvedPatientId <= 0) {
                        // Legacy data: a study_subject carries a
                        // patient_uuid that has no `patient` row.
                        // Should be impossible after the C4 migration,
                        // but defensively mint one so the FK invariant
                        // holds going forward.
                        LOG.warn("Link-patient: patient_uuid={} on study_subject {} has no "
                                + "patient row — minting one defensively.",
                                resolvedUuid, sourceUuid != null ? id : targetId);
                        resolvedPatientId = insertPatient(c, resolvedUuid, currentUser.getId());
                    }
                }
                if (updateSource) {
                    applyLink(c, id, resolvedUuid, resolvedPatientId);
                    writeAuditRow(c, AuditTypeIds.STUDY_SUBJECT_PATIENT_LINKED,
                            currentUser.getId(), id,
                            "linked-to:" + targetId
                                    + ":patientUuid:" + resolvedUuid
                                    + ":patientId:" + resolvedPatientId);
                }
                if (updateTarget) {
                    applyLink(c, targetId, resolvedUuid, resolvedPatientId);
                    writeAuditRow(c, AuditTypeIds.STUDY_SUBJECT_PATIENT_LINKED,
                            currentUser.getId(), targetId,
                            "linked-to:" + id
                                    + ":patientUuid:" + resolvedUuid
                                    + ":patientId:" + resolvedPatientId);
                }
                c.commit();
            } catch (SQLException txEx) {
                c.rollback();
                throw txEx;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException sqlEx) {
            LOG.error("Link-patient: failed to persist link for study_subject {} + {}: {}",
                    id, targetId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to persist patient link — see server log."));
        }

        LOG.info("Link-patient: study_subject {} ({}) <-> study_subject {} ({}) "
                        + "under patient_uuid={} (patient_id={}) by user={}",
                id, source.label, targetId, target.label,
                resolvedUuid, resolvedPatientId, currentUser.getName());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("patientUuid", resolvedUuid);
        resp.put("patientId", resolvedPatientId);
        return ResponseEntity.ok(resp);
    }

    /**
     * Load the {@code study_subject} row's identity + study + current
     * {@code patient_uuid} + {@code patient_id} in one SELECT. The
     * {@code patient_uuid} column is added by
     * {@code lc-muw-2026-06-19-study-subject-patient-uuid.xml}; the
     * {@code patient_id} FK by {@code lc-muw-2026-06-20-patient-table.xml}.
     * Before either changeset the lookup throws a column-missing
     * SQLException which the caller surfaces as a 500.
     */
    private LinkRow loadRow(Connection c, int studySubjectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_subject_id, study_id, label, status_id, "
                        + "       patient_uuid, patient_id "
                        + "  FROM study_subject "
                        + " WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                LinkRow row = new LinkRow();
                row.id = rs.getInt("study_subject_id");
                row.studyId = rs.getInt("study_id");
                row.label = rs.getString("label");
                row.statusId = rs.getInt("status_id");
                row.patientUuid = rs.getString("patient_uuid");
                long pid = rs.getLong("patient_id");
                row.patientId = rs.wasNull() ? 0L : pid;
                return row;
            }
        }
    }

    /**
     * Apply the resolved hard FK + mirrored UUID to one
     * {@code study_subject} row. Both columns are written together so
     * the deprecated {@code patient_uuid} mirror stays in sync with
     * {@code patient_id} until the post-C4 follow-up drops it.
     */
    private void applyLink(Connection c, int studySubjectId,
                           String uuid, long patientId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE study_subject "
                        + "   SET patient_uuid = ?, patient_id = ? "
                        + " WHERE study_subject_id = ?")) {
            ps.setString(1, uuid);
            ps.setLong(2, patientId);
            ps.setInt(3, studySubjectId);
            ps.executeUpdate();
        }
    }

    /**
     * INSERT a fresh {@code patient} row and return its generated
     * {@code patient_id}. Used in the both-null branch and the
     * defensive fallback where a {@code patient_uuid} on
     * {@code study_subject} has no matching {@code patient} row
     * (impossible after the C4 migration, but possible in test
     * harnesses that bypass Liquibase).
     */
    private long insertPatient(Connection c, String uuid, int createdByUserId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO patient (patient_uuid, created_by) "
                        + "VALUES (?, ?) RETURNING patient_id")) {
            ps.setString(1, uuid);
            ps.setInt(2, createdByUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException(
                            "Patient INSERT returned no patient_id row for uuid=" + uuid);
                }
                return rs.getLong(1);
            }
        }
    }

    /**
     * Resolve the hard {@code patient_id} for an existing
     * {@code patient_uuid}. Returns 0 when the lookup misses — the
     * caller treats that as "legacy data without a patient row" and
     * mints one defensively to restore the FK invariant.
     */
    private long resolvePatientIdByUuid(Connection c, String uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT patient_id FROM patient WHERE patient_uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0L;
                return rs.getLong("patient_id");
            }
        }
    }

    /**
     * Write one {@code audit_log_event} row tied to the supplied
     * {@code study_subject_id}. Stays on the supplied connection so the
     * UPDATE + audit insert ride the same transaction — a rollback on
     * the UPDATE rolls back the audit row too, and vice versa.
     *
     * <p>{@code audit_table} is fixed at {@code study_subject};
     * {@code entity_name} (column name) is {@code patient_uuid};
     * {@code old_value} is blank by convention (the prior value is
     * either null or the same UUID — neither is interesting compared
     * to the {@code new_value} cross-link descriptor).
     */
    private static void writeAuditRow(Connection c, int auditTypeId, int userId,
                                      int studySubjectId, String newValue) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                        + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, auditTypeId);
            ps.setInt(2, userId);
            ps.setString(3, "study_subject");
            ps.setInt(4, studySubjectId);
            ps.setString(5, "patient_uuid");
            ps.setString(6, "");
            ps.setString(7, newValue);
            ps.executeUpdate();
        }
    }

    /** Minimal struct the endpoint needs from study_subject. */
    private static final class LinkRow {
        int id;
        int studyId;
        String label;
        int statusId;
        String patientUuid;
        /** 0 when the row's {@code patient_id} FK is NULL. */
        long patientId;
    }
}
