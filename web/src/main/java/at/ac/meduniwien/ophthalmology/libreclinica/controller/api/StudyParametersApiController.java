/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.6 study-params — GET + PUT /api/v1/studies/{oid}/parameters.
 *
 * <p>Surfaces the 18 {@code study_parameter_value} handles seeded
 * since OC 2.5 (subject-id generation, DOB collection, discrepancy
 * management, interviewer/date defaults, randomization, participant
 * portal etc.) so DMs can configure these from the SPA without
 * bouncing to the legacy {@code /CreateSubStudy} JSP.
 *
 * <h2>Auth model</h2>
 * <ul>
 *   <li>GET: any authenticated user with a session-bound
 *       {@code userBean} can read the active configuration. Site
 *       visibility is handled by {@link SiteVisibilityFilter} at the
 *       chain level; we additionally 404 on missing OID so leakage
 *       across studies is bounded.</li>
 *   <li>PUT: {@link StudyAdminAuthorization#roleMayEditStudy} — sysadmin
 *       OR a {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role#STUDYDIRECTOR}
 *       / {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role#COORDINATOR}
 *       with a current session role bound to the target study. 409 if
 *       the study is LOCKED / FROZEN / (AUTO_)DELETED (parity with
 *       the identity PUT). 400 on enum out-of-range per handle.</li>
 * </ul>
 *
 * <h2>Persistence</h2>
 * <p>Per-handle direct JDBC upsert against {@code study_parameter_value}
 * inside a single autocommit-OFF transaction. The legacy
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyParameterValueDAO#setParameterValue
 * StudyParameterValueDAO.setParameterValue} returns {@code false}
 * unconditionally (never implemented), and the create/update split
 * leaks a race window. Inlining the upsert here mirrors the
 * {@link MeApiController#emitProfileAudit} / {@link StudiesApiController#writeStudyFieldAudit}
 * pattern other E.6 PUTs use, and lets the per-handle audit row carry
 * the same Connection so a DB failure halfway through rolls back the
 * whole patch.
 *
 * <h2>Audit</h2>
 * <p>One {@code audit_log_event} row per actually-changed handle, with
 * {@code audit_log_event_type_id = 54} ({@code study_parameters_updated})
 * — seeded by {@code lc-muw-2026-06-05-audit-event-type-study-parameters.xml}
 * and routed into the "admin" variant by
 * {@link AuditApiController#variantForType}. Each row records
 * {@code entity_id = study.id}, {@code entity_name = handle},
 * {@code old_value / new_value} = the param string values.
 *
 * @see StudyParametersDto
 * @see UpdateStudyParametersRequest
 */
@RestController
@RequestMapping("/api/v1/studies/{studyOid}/parameters")
@Tag(name = "StudyParameters",
     description = "Read/write study_parameter_value handles for a study.")
public class StudyParametersApiController {

    private static final Logger LOG =
            LoggerFactory.getLogger(StudyParametersApiController.class);

    /**
     * audit_log_event_type row seeded by
     * {@code lc-muw-2026-06-05-audit-event-type-study-parameters.xml}.
     * Sibling of 51 (study_identity_updated) under the "admin" variant
     * in {@link AuditApiController#variantForType}.
     */
    private static final int AUDIT_TYPE_STUDY_PARAMETERS_UPDATED = 54;

    /**
     * Validation allow-lists per handle. {@code null} = any string
     * (only length-bounded by the {@code varchar(50)} column width).
     * Mirrors the JSP {@code editStudy.jsp} drop-down option lists plus
     * the legacy {@code CreateSubStudyServlet} validation cases. Lower
     * case + snake_case to match the persisted column values.
     *
     * <p>{@code subjectIdPrefixSuffix} historically accepts "true"/"false";
     * {@code adminForcedReasonForChange} same. Boolean-shaped fields
     * use the {@link #BOOL_VALUES} set.
     */
    private static final Set<String> BOOL_VALUES = Set.of("true", "false");
    private static final Set<String> REQUIRED_OPTIONAL_NOTUSED =
            Set.of("required", "optional", "not_used");
    private static final Set<String> REQUIRED_OPTIONAL =
            Set.of("required", "optional");
    private static final Set<String> BLANK_PREPOPULATED =
            Set.of("blank", "pre-populated");
    private static final Set<String> SUBJECT_ID_GEN =
            // Legacy values stored on disk; see CreateSubStudyServlet
            // and BuildStudyView i18n keys. The DB carries the human-
            // readable strings rather than enum codes.
            Set.of("manual", "auto non-editable", "auto editable");
    private static final Set<String> COLLECT_DOB =
            // 1 = full DOB; 2 = year only; 3 = none.
            Set.of("1", "2", "3");
    private static final Set<String> DISCREPANCY_MGMT =
            // Persisted as "true"/"false" — when "false" the SPA also
            // hides the DN affordance in CrfEntryView.
            Set.of("true", "false");
    private static final Set<String> ENABLED_DISABLED =
            Set.of("enabled", "disabled");

    private final DataSource dataSource;

    @Autowired
    public StudyParametersApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/studies/{studyOid}/parameters                          */
    /* ----------------------------------------------------------------- */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyParametersDto.class)))
    public ResponseEntity<?> get(@PathVariable("studyOid") String studyOid,
                                 HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        Map<String, String> values = loadHandles(target.getId());
        return ResponseEntity.ok(toDto(studyOid, values));
    }

    /* ----------------------------------------------------------------- */
    /* PUT /api/v1/studies/{studyOid}/parameters                          */
    /* ----------------------------------------------------------------- */

    @PutMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyParametersDto.class)))
    public ResponseEntity<?> update(@PathVariable("studyOid") String studyOid,
                                    @RequestBody(required = false) UpdateStudyParametersRequest body,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        // Shape validation runs before any DB I/O so an invalid payload
        // surfaces as 400 even when the downstream lookup would throw
        // (the existing tests cover the chaos/blank handle cases).
        List<ValidationErrorBody.FieldError> errors =
                validateUpdateShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed", errors));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }

        if (!StudyAdminAuthorization.userMayEditStudy(me, target, dataSource)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing this study's parameters"));
        }
        if (!StudyAdminAuthorization.studyAcceptsWrites(target)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study is " + target.getStatus().getName().toLowerCase()
                            + " — parameter writes are refused until it is unlocked"));
        }

        // Diff supplied handles against current persisted values inside
        // one transaction. Audit fan-out + upserts share the connection
        // so a per-handle failure rolls back the whole patch.
        Map<String, String> patch = collectSuppliedHandles(body);
        Map<String, String> current = loadHandles(target.getId());

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (Map.Entry<String, String> e : patch.entrySet()) {
                    String handle = e.getKey();
                    String newVal = e.getValue();
                    String oldVal = current.getOrDefault(handle, "");
                    if (java.util.Objects.equals(oldVal, newVal)) continue;
                    upsertHandle(c, target.getId(), handle, newVal);
                    writeAudit(c, me, target, handle, oldVal, newVal);
                    current.put(handle, newVal);
                }
                c.commit();
            } catch (SQLException ex) {
                try { c.rollback(); } catch (SQLException ignore) {}
                LOG.error("Update study parameters: rollback for oid={} ({})",
                        studyOid, ex.getMessage());
                return ResponseEntity.status(500).body(Map.of("message",
                        "Failed to persist study parameter changes"));
            }
        } catch (SQLException ex) {
            LOG.error("Update study parameters: connection failure for oid={} ({})",
                    studyOid, ex.getMessage());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist study parameter changes"));
        }

        LOG.info("Update study parameters: oid={} by user={} handles={}",
                studyOid, me.getName(), patch.size());

        return ResponseEntity.ok(toDto(studyOid, current));
    }

    /* ----------------------------------------------------------------- */
    /* Persistence helpers                                                */
    /* ----------------------------------------------------------------- */

    /**
     * Load all 18 handles for {@code studyId} from
     * {@code study_parameter_value}, falling back to
     * {@code study_parameter.default_value} for any handle without a
     * row. The fallback keeps the SPA + JSP paths in sync the next
     * time a default is bumped via Liquibase rather than mirroring it
     * in the SPA defaults.
     */
    private Map<String, String> loadHandles(int studyId) {
        Map<String, String> defaults = new HashMap<>();
        Map<String, String> values = new HashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement defs = c.prepareStatement(
                     "SELECT handle, default_value FROM study_parameter")) {
            try (ResultSet rs = defs.executeQuery()) {
                while (rs.next()) {
                    String h = rs.getString(1);
                    String dv = rs.getString(2);
                    defaults.put(h, dv == null ? "" : dv);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT parameter, value FROM study_parameter_value WHERE study_id = ?")) {
                ps.setInt(1, studyId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String h = rs.getString(1);
                        String v = rs.getString(2);
                        values.put(h, v == null ? "" : v);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("Load study parameters for study {} failed (returning defaults): {}",
                    studyId, e.getMessage());
        }
        // Merge: values override defaults.
        Map<String, String> out = new HashMap<>(defaults);
        out.putAll(values);
        return out;
    }

    /**
     * Direct upsert mirroring the legacy
     * {@code StudyParameterValueDAO.create} / {@code update} split, but
     * single-statement so it can participate in a wider transaction.
     * Uses an "UPDATE then INSERT if rowCount=0" guard rather than
     * ON CONFLICT — keeps Postgres + Oracle parity since the legacy
     * DAO ships dual XML query files.
     */
    private void upsertHandle(Connection c, int studyId, String handle, String value)
            throws SQLException {
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE study_parameter_value SET value = ? "
                        + "WHERE study_id = ? AND parameter = ?")) {
            upd.setString(1, value);
            upd.setInt(2, studyId);
            upd.setString(3, handle);
            int rows = upd.executeUpdate();
            if (rows == 0) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO study_parameter_value (study_id, value, parameter) "
                                + "VALUES (?, ?, ?)")) {
                    ins.setInt(1, studyId);
                    ins.setString(2, value);
                    ins.setString(3, handle);
                    ins.executeUpdate();
                }
            }
        }
    }

    /**
     * Emit one audit_log_event row per actually-changed handle. Shares
     * the supplied {@link Connection} so a downstream rollback also
     * rolls back the audit rows (preferable for the SPA's read path:
     * either both the value and the audit row land, or neither).
     */
    private void writeAudit(Connection c, UserAccountBean editor, StudyBean target,
                            String handle, String oldVal, String newVal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                        + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), ?, 'study_parameter_value', ?, ?, ?, ?)")) {
            ps.setInt(1, AUDIT_TYPE_STUDY_PARAMETERS_UPDATED);
            ps.setInt(2, editor.getId());
            ps.setInt(3, target.getId());
            ps.setString(4, handle);
            ps.setString(5, oldVal == null ? "" : oldVal);
            ps.setString(6, newVal == null ? "" : newVal);
            ps.executeUpdate();
        }
    }

    /* ----------------------------------------------------------------- */
    /* Wire ↔ map plumbing                                                */
    /* ----------------------------------------------------------------- */

    private static StudyParametersDto toDto(String studyOid, Map<String, String> values) {
        return new StudyParametersDto(
                studyOid,
                values.getOrDefault("subjectIdGeneration", "manual"),
                values.getOrDefault("subjectIdPrefixSuffix", "true"),
                values.getOrDefault("subjectPersonIdRequired", "required"),
                values.getOrDefault("personIdShownOnCRF", "false"),
                values.getOrDefault("collectDob", "1"),
                values.getOrDefault("genderRequired", "true"),
                values.getOrDefault("eventLocationRequired", "not_used"),
                values.getOrDefault("discrepancyManagement", "true"),
                values.getOrDefault("interviewerNameRequired", "not_used"),
                values.getOrDefault("interviewerNameDefault", "blank"),
                values.getOrDefault("interviewerNameEditable", "true"),
                values.getOrDefault("interviewDateRequired", "not_used"),
                values.getOrDefault("interviewDateDefault", "blank"),
                values.getOrDefault("interviewDateEditable", "true"),
                values.getOrDefault("secondaryLabelViewable", "false"),
                values.getOrDefault("adminForcedReasonForChange", "true"),
                values.getOrDefault("participantPortal", "disabled"),
                values.getOrDefault("randomization", "disabled"));
    }

    /**
     * Collect into a LinkedHashMap so audit rows land in a deterministic
     * order matching the DTO field declaration (improves the Audit Log
     * view's per-transaction grouping when several handles flip at once).
     */
    private static Map<String, String> collectSuppliedHandles(UpdateStudyParametersRequest body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body.subjectIdGeneration() != null)     out.put("subjectIdGeneration",     body.subjectIdGeneration());
        if (body.subjectIdPrefixSuffix() != null)   out.put("subjectIdPrefixSuffix",   body.subjectIdPrefixSuffix());
        if (body.subjectPersonIdRequired() != null) out.put("subjectPersonIdRequired", body.subjectPersonIdRequired());
        if (body.personIdShownOnCRF() != null)      out.put("personIdShownOnCRF",      body.personIdShownOnCRF());
        if (body.collectDob() != null)              out.put("collectDob",              body.collectDob());
        if (body.genderRequired() != null)          out.put("genderRequired",          body.genderRequired());
        if (body.eventLocationRequired() != null)   out.put("eventLocationRequired",   body.eventLocationRequired());
        if (body.discrepancyManagement() != null)   out.put("discrepancyManagement",   body.discrepancyManagement());
        if (body.interviewerNameRequired() != null) out.put("interviewerNameRequired", body.interviewerNameRequired());
        if (body.interviewerNameDefault() != null)  out.put("interviewerNameDefault",  body.interviewerNameDefault());
        if (body.interviewerNameEditable() != null) out.put("interviewerNameEditable", body.interviewerNameEditable());
        if (body.interviewDateRequired() != null)   out.put("interviewDateRequired",   body.interviewDateRequired());
        if (body.interviewDateDefault() != null)    out.put("interviewDateDefault",    body.interviewDateDefault());
        if (body.interviewDateEditable() != null)   out.put("interviewDateEditable",   body.interviewDateEditable());
        if (body.secondaryLabelViewable() != null)  out.put("secondaryLabelViewable",  body.secondaryLabelViewable());
        if (body.adminForcedReasonForChange() != null) out.put("adminForcedReasonForChange", body.adminForcedReasonForChange());
        if (body.participantPortal() != null)       out.put("participantPortal",       body.participantPortal());
        if (body.randomization() != null)           out.put("randomization",           body.randomization());
        return out;
    }

    /* ----------------------------------------------------------------- */
    /* Validation                                                         */
    /* ----------------------------------------------------------------- */

    private static List<ValidationErrorBody.FieldError> validateUpdateShape(
            UpdateStudyParametersRequest body) {
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
        // Allow-list each handle. Reject empty strings (use null to
        // leave unchanged). The catalogue follows the legacy JSP and
        // CreateSubStudyServlet validation cases.
        checkEnum(body.subjectIdGeneration(),     "subjectIdGeneration",     SUBJECT_ID_GEN, out);
        checkEnum(body.subjectIdPrefixSuffix(),   "subjectIdPrefixSuffix",   BOOL_VALUES, out);
        checkEnum(body.subjectPersonIdRequired(), "subjectPersonIdRequired", REQUIRED_OPTIONAL_NOTUSED, out);
        checkEnum(body.personIdShownOnCRF(),      "personIdShownOnCRF",      BOOL_VALUES, out);
        checkEnum(body.collectDob(),              "collectDob",              COLLECT_DOB, out);
        checkEnum(body.genderRequired(),          "genderRequired",          BOOL_VALUES, out);
        checkEnum(body.eventLocationRequired(),   "eventLocationRequired",   REQUIRED_OPTIONAL_NOTUSED, out);
        checkEnum(body.discrepancyManagement(),   "discrepancyManagement",   DISCREPANCY_MGMT, out);
        checkEnum(body.interviewerNameRequired(), "interviewerNameRequired", REQUIRED_OPTIONAL_NOTUSED, out);
        checkEnum(body.interviewerNameDefault(),  "interviewerNameDefault",  BLANK_PREPOPULATED, out);
        checkEnum(body.interviewerNameEditable(), "interviewerNameEditable", BOOL_VALUES, out);
        checkEnum(body.interviewDateRequired(),   "interviewDateRequired",   REQUIRED_OPTIONAL_NOTUSED, out);
        checkEnum(body.interviewDateDefault(),    "interviewDateDefault",    BLANK_PREPOPULATED, out);
        checkEnum(body.interviewDateEditable(),   "interviewDateEditable",   BOOL_VALUES, out);
        checkEnum(body.secondaryLabelViewable(),  "secondaryLabelViewable",  BOOL_VALUES, out);
        checkEnum(body.adminForcedReasonForChange(), "adminForcedReasonForChange", BOOL_VALUES, out);
        checkEnum(body.participantPortal(),       "participantPortal",       ENABLED_DISABLED, out);
        checkEnum(body.randomization(),           "randomization",           ENABLED_DISABLED, out);
        return out;
    }

    private static void checkEnum(String v, String field, Set<String> allowed,
                                  List<ValidationErrorBody.FieldError> out) {
        if (v == null) return;
        String trimmed = v.trim();
        if (trimmed.isEmpty()) {
            out.add(new ValidationErrorBody.FieldError(
                    field, field + " cannot be blank (use null to leave unchanged)"));
            return;
        }
        if (!allowed.contains(trimmed)) {
            out.add(new ValidationErrorBody.FieldError(
                    field, field + " must be one of " + allowed));
        }
    }

    /** Reserved for future date-fence checks; currently unused. */
    @SuppressWarnings("unused")
    private static Date utcNow() {
        return new Date();
    }
}
