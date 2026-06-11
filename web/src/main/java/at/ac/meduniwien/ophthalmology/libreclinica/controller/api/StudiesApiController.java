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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Phase E.4 M1 — list studies the current user has a role on.
 *
 * <p>{@code GET /pages/api/v1/studies}. Powers the SPA study-picker
 * shown after login when the user has no active study bound yet
 * (or wants to switch). Returns one row per (study × user-role)
 * pairing — for the seeded {@code root} account in the demo dataset
 * that's the Default Study with both an {@code admin} and a
 * {@code director} role grant. The SPA dedupes to one entry per
 * study, surfacing the highest-precedence role.
 *
 * <p>Each entry includes the role label translated into the SPA's
 * 5-value {@code UserRole} union so the picker can colour-code by
 * role chip without needing a second lookup.
 */
@RestController
@RequestMapping("/api/v1/studies")
@Tag(name = "Studies", description = "User's available studies.")
public class StudiesApiController {

    private static final Logger LOG = LoggerFactory.getLogger(StudiesApiController.class);

    private final DataSource dataSource;

    @Autowired
    public StudiesApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = StudyOptionDto.class)))
    public ResponseEntity<?> list(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudyDAO studyDAO = new StudyDAO(dataSource);
        UserAccountDAO userDAO = new UserAccountDAO(dataSource);

        ArrayList<StudyBean> allStudies = studyDAO.findAll();
        // findStudyByUser has a hardcoded `role_name != 'admin'` filter
        // in its SQL — legacy holdover from when 'admin' was treated as
        // a cross-study role rather than a per-study binding. For the
        // SPA's "where am I assigned?" question we want every binding,
        // including admin ones — that's how root surfaces the Default
        // Study + every other study they have a binding on for the
        // switch-active-study card. Use findAllRolesByUserName instead
        // (no role filter) and join study identity ourselves.
        ArrayList<StudyUserRoleBean> grants = userDAO.findAllRolesByUserName(ub.getName());

        // Index studies by id for parent-name lookup, and by id for role join.
        Map<Integer, StudyBean> studyById = new HashMap<>();
        for (StudyBean s : allStudies) studyById.put(s.getId(), s);

        int activeStudyId = ub.getActiveStudyId();
        List<StudyOptionDto> out = new ArrayList<>();
        // Phase E.6 (2026-06-03): de-dup by study_id when the user has
        // more than one active binding on the same study (the latent
        // updateRole-touches-all-rows bug surfaces those duplicates).
        // Keep the highest-priority projected role per study.
        Map<Integer, StudyOptionDto> bestByStudy = new java.util.LinkedHashMap<>();
        for (StudyUserRoleBean r : grants) {
            // Skip inactive bindings — only Status.AVAILABLE counts.
            if (r.getStatus() == null
                    || r.getStatus().getId() != at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.AVAILABLE.getId()) {
                continue;
            }
            StudyBean s = studyById.get(r.getStudyId());
            if (s == null) continue;
            boolean isSite = s.getParentStudyId() > 0;
            StudyBean parent = isSite ? studyById.get(s.getParentStudyId()) : null;
            String spaRole = r.getRole() == null ? "Investigator"
                    : RoleMapper.toSpaRole(r.getRole().getName());
            StudyOptionDto candidate = new StudyOptionDto(
                    s.getOid(),
                    s.getName(),
                    // Phase E.6 follow-up 2026-06-10 — institutional protocol
                    // short-code for the SPA's subject-ID prefix prefill.
                    s.getIdentifier(),
                    parent == null ? null : parent.getOid(),
                    parent == null ? null : parent.getName(),
                    spaRole,
                    isSite,
                    s.getId() == activeStudyId
            );
            StudyOptionDto existing = bestByStudy.get(s.getId());
            if (existing == null || studyRolePriority(spaRole) > studyRolePriority(existing.role())) {
                bestByStudy.put(s.getId(), candidate);
            }
        }
        out.addAll(bestByStudy.values());
        return ResponseEntity.ok(out);
    }

    /** Phase E.6 — same priority order as UsersApiController.rolePriority. */
    private static int studyRolePriority(String spaRole) {
        return switch (spaRole) {
            case "Administrator" -> 5;
            case "Data Manager"  -> 4;
            case "Monitor"       -> 3;
            case "CRC"           -> 2;
            case "Investigator"  -> 1;
            default              -> 0;
        };
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/studies  (Phase E A8.1 — create top-level study)     */
    /* ----------------------------------------------------------------- */

    /**
     * Provision a new top-level study.
     *
     * <p>Mirrors {@code CreateStudyServlet} collapsed into a single
     * flat request. Sysadmin-only. Server-generates the OID via the
     * {@code S_<UNIQUE_PROTOCOL_ID>} convention (legacy parity — the
     * spreadsheet seeds use this shape).
     *
     * <p>Side-effect: the caller is auto-bound as
     * {@link Role#COORDINATOR} on the new study (mirrors
     * {@code CreateStudyServlet:455–477}), so they can immediately
     * build out the study without an explicit role grant.
     *
     * <p>Status codes: {@code 201} success, {@code 400} validation
     * (incl. uniqueProtocolId collision), {@code 401} anonymous,
     * {@code 403} non-sysadmin.
     */
    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> create(@RequestBody(required = false) CreateStudyRequest body,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!StudyAdminAuthorization.roleMayCreateStudy(me)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit creating studies — sysadmin only"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        // Shape-level validation (no DAO calls).
        List<ValidationErrorBody.FieldError> errors =
                validateCreateStudyShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed", errors));
        }

        // DAO-bound validation: uniqueness.
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean uidCollision = studyDao.findByUniqueIdentifier(body.uniqueProtocolId().trim());
        if (uidCollision != null && uidCollision.getId() != 0) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
                            "uniqueProtocolId",
                            "Unique protocol id '" + body.uniqueProtocolId()
                                    + "' is already taken"))));
        }
        StudyBean nameCollision = studyDao.findByName(body.name().trim());
        if (nameCollision != null && nameCollision.getId() != 0) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
                            "name", "Study name '" + body.name() + "' is already taken"))));
        }

        StudyBean toCreate = new StudyBean();
        toCreate.setName(body.name().trim());
        toCreate.setIdentifier(body.uniqueProtocolId().trim());
        toCreate.setSummary(body.briefSummary().trim());
        toCreate.setPrincipalInvestigator(body.principalInvestigator().trim());
        toCreate.setSponsor(body.sponsor().trim());
        if (body.officialTitle() != null) toCreate.setOfficialTitle(body.officialTitle().trim());
        if (body.secondaryProtocolId() != null) toCreate.setSecondaryIdentifier(body.secondaryProtocolId().trim());
        if (body.collaborators() != null) toCreate.setCollaborators(body.collaborators().trim());
        if (body.protocolDescription() != null) toCreate.setProtocolDescription(body.protocolDescription().trim());
        if (body.contactEmail() != null) toCreate.setContactEmail(body.contactEmail().trim());
        if (body.protocolType() != null) toCreate.setProtocolType(body.protocolType().trim());
        if (body.phase() != null) toCreate.setPhase(body.phase().trim());
        toCreate.setStatus(Status.PENDING);
        toCreate.setOwner(me);
        toCreate.setParentStudyId(0);

        StudyBean persisted = studyDao.create(toCreate);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("StudyDAO.create returned no row for name={}", body.name());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist new study"));
        }

        // Legacy convention: OID = "S_<uniqueProtocolId>" (matches the
        // seed-data shape used by every demo / fixture study).
        String generatedOid = "S_" + body.uniqueProtocolId().trim().toUpperCase();
        persisted.setOid(generatedOid);
        // StudyDAO.updateStepOne dereferences oldStatus on every update
        // (NPE otherwise). We're not changing the status here — just
        // back-filling the generated OID — so mirror the current value.
        persisted.setOldStatus(persisted.getStatus());
        studyDao.update(persisted);

        AuditEventDAO auditEventDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(auditEventDAO, me, persisted, null,
                "Study created", "study", persisted.getId(),
                "study_id", "", String.valueOf(persisted.getId()));

        // Auto-bind the caller as COORDINATOR on the new study — they
        // can immediately build out the study without an explicit grant.
        try {
            UserAccountDAO userDao = new UserAccountDAO(dataSource);
            StudyUserRoleBean binding = new StudyUserRoleBean();
            binding.setStudyId(persisted.getId());
            binding.setRoleName(Role.COORDINATOR.getName());
            binding.setStatus(Status.AVAILABLE);
            binding.setOwner(me);
            binding.setUserName(me.getName());
            binding.setUserAccountId(me.getId());
            userDao.createStudyUserRole(me, binding);

            EventCrfsApiController.writeAuditEvent(auditEventDAO, me, persisted, null,
                    "Study role granted (initial) — user=" + me.getName()
                            + " role=" + Role.COORDINATOR.getName(),
                    "study_user_role", 0, "role_id",
                    "", String.valueOf(Role.COORDINATOR.getId()));
        } catch (Exception e) {
            LOG.warn("Failed to auto-bind creator as COORDINATOR on study {} (continuing): {}",
                    persisted.getOid(), e.getMessage());
        }

        LOG.info("Create study: oid={} name={} by admin={}",
                generatedOid, body.name(), me.getName());

        return ResponseEntity.status(201).body(toIdentityDto(persisted, studyDao));
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/studies/{studyOid}  (Phase E.6 — single-study read)    */
    /* ----------------------------------------------------------------- */

    /**
     * Returns the full {@link StudyIdentityDto} for a study so the SPA
     * can pre-populate the edit form with every protocol field — not
     * just the name. Without this the edit view only had the dashboard's
     * studyName and operators sending blank fields lost everything but
     * the name (the PUT treats blank-after-trim as "leave unchanged").
     */
    @GetMapping("/{studyOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
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
        return ResponseEntity.ok(toIdentityDto(target, studyDao));
    }

    /* ----------------------------------------------------------------- */
    /* PUT /api/v1/studies/{studyOid}  (Phase E A8.1 — edit identity)    */
    /* ----------------------------------------------------------------- */

    @PutMapping("/{studyOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> update(@PathVariable("studyOid") String studyOid,
                                    @RequestBody(required = false) UpdateStudyRequest body,
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

        List<ValidationErrorBody.FieldError> errors =
                validateUpdateStudyShape(body);
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
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, target)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing this study"));
        }
        if (!StudyAdminAuthorization.studyAcceptsWrites(target)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study is " + target.getStatus().getName().toLowerCase()
                            + " — writes are refused until it is unlocked"));
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        if (body.name() != null) {
            String oldVal = target.getName();
            String newVal = body.name().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setName(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "name", oldVal, newVal);
            }
        }
        if (body.briefSummary() != null) {
            String oldVal = target.getSummary();
            String newVal = body.briefSummary().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setSummary(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "summary", oldVal, newVal);
            }
        }
        if (body.principalInvestigator() != null) {
            String oldVal = target.getPrincipalInvestigator();
            String newVal = body.principalInvestigator().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setPrincipalInvestigator(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "principal_investigator", oldVal, newVal);
            }
        }
        if (body.sponsor() != null) {
            String oldVal = target.getSponsor();
            String newVal = body.sponsor().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setSponsor(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "sponsor", oldVal, newVal);
            }
        }
        if (body.officialTitle() != null) {
            String oldVal = target.getOfficialTitle();
            String newVal = body.officialTitle().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setOfficialTitle(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "official_title", oldVal, newVal);
            }
        }
        if (body.secondaryProtocolId() != null) {
            String oldVal = target.getSecondaryIdentifier();
            String newVal = body.secondaryProtocolId().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setSecondaryIdentifier(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "secondary_identifier", oldVal, newVal);
            }
        }
        if (body.collaborators() != null) {
            String oldVal = target.getCollaborators();
            String newVal = body.collaborators().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setCollaborators(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "collaborators", oldVal, newVal);
            }
        }
        if (body.protocolDescription() != null) {
            String oldVal = target.getProtocolDescription();
            String newVal = body.protocolDescription().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setProtocolDescription(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "protocol_description", oldVal, newVal);
            }
        }
        if (body.protocolType() != null) {
            String oldVal = target.getProtocolType();
            String newVal = body.protocolType().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setProtocolType(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "protocol_type", oldVal, newVal);
            }
        }
        if (body.phase() != null) {
            String oldVal = target.getPhase();
            String newVal = body.phase().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setPhase(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "phase", oldVal, newVal);
            }
        }

        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        studyDao.update(target);

        // Phase E.6 (2026-06-03): the session attribute "study" is a
        // StudyBean captured at login (by SecureController +
        // OpenClinicaUsernamePasswordAuthenticationFilter). MeApiController
        // reads its name + oid from THAT bean, so the SPA's top-bar
        // breadcrumb stays stale across an identity edit even after the
        // SPA's auth.bootstrap() re-fetches /me — /me itself was still
        // returning the snapshot. Refresh the attribute with the
        // freshly-mutated bean when the edit targeted the currently
        // active study so the next /me sees the new identity.
        StudyBean sessionStudy = (StudyBean) session.getAttribute("study");
        if (sessionStudy != null && sessionStudy.getId() == target.getId()) {
            session.setAttribute("study", target);
        }

        LOG.info("Update study: oid={} by admin={}", studyOid, me.getName());

        return ResponseEntity.ok(toIdentityDto(target, studyDao));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/studies/{studyOid}/disable                            */
    /* POST /api/v1/studies/{studyOid}/restore                            */
    /*   (Phase E A8.1 — study lifecycle, sysadmin only)                  */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{studyOid}/disable")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> disable(@PathVariable("studyOid") String studyOid,
                                     HttpSession session) {
        return lifecycle(studyOid, session, Status.DELETED, "disable");
    }

    @PostMapping("/{studyOid}/restore")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> restore(@PathVariable("studyOid") String studyOid,
                                     HttpSession session) {
        return lifecycle(studyOid, session, Status.AVAILABLE, "restore");
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/studies/{studyOid}/status                            */
    /*   (Phase E A8.5 — operational status lifecycle)                   */
    /* ----------------------------------------------------------------- */

    /**
     * Move a study through the operational state machine. Distinct
     * from A8.1's disable/restore — those soft-delete the row;
     * this endpoint handles the AVAILABLE / PENDING / LOCKED /
     * FROZEN cluster.
     *
     * <p>Transition matrix (legal {@code target} per current state):
     * <pre>
     *   PENDING   → AVAILABLE
     *   AVAILABLE → LOCKED, FROZEN, PENDING
     *   LOCKED    → AVAILABLE, FROZEN
     *   FROZEN    → AVAILABLE, LOCKED
     * </pre>
     *
     * <p>{@code reason} is required for AVAILABLE→LOCKED and
     * AVAILABLE→FROZEN (GCP audit-of-record requirement). The
     * reason is captured in the {@code action_message} on the audit
     * row.
     *
     * <p>Cascade: legal transitions also propagate to child sites
     * via {@code StudyDAO.updateSitesStatus} (mirrors legacy
     * RemoveStudyServlet's cascade pattern, applied to the
     * status-only path).
     *
     * <p>Sysadmin-only — same MUW interpretation as A8.1's lifecycle.
     */
    @PostMapping("/{studyOid}/status")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> setStatus(@PathVariable("studyOid") String studyOid,
                                       @RequestBody(required = false) SetStudyStatusRequest body,
                                       HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!StudyAdminAuthorization.roleMayLifecycleStudy(me)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit study status transitions — sysadmin only"));
        }
        if (body == null || body.targetStatus() == null || body.targetStatus().isBlank()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
                            "targetStatus", "targetStatus is required"))));
        }

        Status target;
        try {
            target = resolveTargetStatus(body.targetStatus());
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
                            "targetStatus",
                            "targetStatus must be one of AVAILABLE / PENDING / LOCKED / FROZEN "
                                    + "(use /disable + /restore for the removed state)"))));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target_ = studyDao.findByOid(studyOid);
        if (target_ == null || target_.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        Status currentStatus = target_.getStatus();
        if (currentStatus == null) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study '" + studyOid + "' has no current status — refuse transition"));
        }
        if (currentStatus.equals(target)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study '" + studyOid + "' is already " + target.getName().toLowerCase()));
        }
        if (!isLegalTransition(currentStatus, target)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Illegal transition: " + currentStatus.getName()
                            + " → " + target.getName()
                            + ". Use /disable to remove, /restore to undelete."));
        }

        // GCP-sensitive transitions require a reason. The reason lands
        // in the audit log so the operator's intent stays attached to
        // the status flip.
        boolean reasonRequired = currentStatus.equals(Status.AVAILABLE)
                && (target.equals(Status.LOCKED) || target.equals(Status.FROZEN));
        if (reasonRequired && (body.reason() == null || body.reason().isBlank())) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
                            "reason",
                            "reason is required for AVAILABLE → " + target.getName() + " transitions"))));
        }

        target_.setStatus(target);
        target_.setUpdater(me);
        target_.setUpdatedDate(new java.util.Date());
        studyDao.updateStudyStatus(target_);

        // Cascade to child sites — same pattern A8.1 uses.
        try {
            studyDao.updateSitesStatus(target_);
        } catch (Exception e) {
            LOG.warn("Cascade status to sites of study {} failed (continuing): {}",
                    studyOid, e.getMessage());
        }

        // Audit-table unification (slice C, 2026-06-12): direct INSERT
        // into audit_log_event with type STUDY_STATUS_CHANGED. Carries
        // the caller-supplied reasonForChange (the only lifecycle event
        // that does — disable / restore have no operator-supplied
        // rationale beyond the status flip itself).
        String reason = body.reason() == null ? "" : body.reason().trim();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, "
                             + "reason_for_change, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study', ?, ?, ?, ?, ?)")) {
            ps.setInt(1, AuditTypeIds.STUDY_STATUS_CHANGED);
            ps.setInt(2, me.getId());
            ps.setInt(3, target_.getId());
            ps.setString(4, studyOid == null ? "" : studyOid);
            ps.setString(5, reason);
            ps.setString(6, currentStatus.getName());
            ps.setString(7, target.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Audit write failed for study_status_change oid={} (continuing): {}",
                    studyOid, e.getMessage());
        }

        LOG.info("Status transition: oid={} {} → {} by user={} reason='{}'",
                studyOid, currentStatus.getName(), target.getName(), me.getName(),
                body.reason() == null ? "" : body.reason());

        return ResponseEntity.ok(toIdentityDto(target_, studyDao));
    }

    /**
     * String → Status enum for the four lifecycle states A8.5 supports.
     * DELETED + RESET + the rest are explicitly excluded.
     */
    private static Status resolveTargetStatus(String raw) {
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (trimmed) {
            case "AVAILABLE" -> Status.AVAILABLE;
            case "PENDING"   -> Status.PENDING;
            case "LOCKED"    -> Status.LOCKED;
            case "FROZEN"    -> Status.FROZEN;
            default -> throw new IllegalArgumentException("Unsupported targetStatus: " + raw);
        };
    }

    /**
     * @return {@code true} when {@code current → target} is a legal
     *         operational transition per A8.5's matrix. Returns
     *         {@code false} for any combination not enumerated.
     */
    private static boolean isLegalTransition(Status current, Status target) {
        if (current.equals(Status.PENDING)) {
            return target.equals(Status.AVAILABLE);
        }
        if (current.equals(Status.AVAILABLE)) {
            return target.equals(Status.LOCKED)
                    || target.equals(Status.FROZEN)
                    || target.equals(Status.PENDING);
        }
        if (current.equals(Status.LOCKED)) {
            return target.equals(Status.AVAILABLE) || target.equals(Status.FROZEN);
        }
        if (current.equals(Status.FROZEN)) {
            return target.equals(Status.AVAILABLE) || target.equals(Status.LOCKED);
        }
        return false;
    }

    private ResponseEntity<?> lifecycle(String studyOid,
                                        HttpSession session,
                                        Status targetStatus,
                                        String operation) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!StudyAdminAuthorization.roleMayLifecycleStudy(me)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit study " + operation + " — sysadmin only"));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        if (target.getStatus() != null && target.getStatus().equals(targetStatus)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study '" + studyOid + "' is already " + targetStatus.getName().toLowerCase()));
        }
        // Restore is only meaningful for currently-disabled studies.
        if (targetStatus == Status.AVAILABLE
                && target.getStatus() != Status.DELETED
                && target.getStatus() != Status.AUTO_DELETED) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study '" + studyOid + "' is not disabled — nothing to restore"));
        }

        Status oldStatus = target.getStatus();
        target.setStatus(targetStatus);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        studyDao.updateStudyStatus(target);

        // Cascade to child sites — mirrors legacy RemoveStudyServlet /
        // RestoreStudyServlet which call updateSitesStatus.
        try {
            studyDao.updateSitesStatus(target);
        } catch (Exception e) {
            LOG.warn("Cascade {} to sites of study {} failed (continuing): {}",
                    operation, studyOid, e.getMessage());
        }

        // One audit row per lifecycle transition.
        writeLifecycleAudit(AuditTypeIds.STUDY_LIFECYCLE_CHANGED, me,
                target.getId(), studyOid, oldStatus, targetStatus, "study_" + operation);

        LOG.info("Study {}: oid={} by admin={}", operation, studyOid, me.getName());
        return ResponseEntity.ok(toIdentityDto(target, studyDao));
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    private static List<ValidationErrorBody.FieldError> validateCreateStudyShape(
            CreateStudyRequest body) {
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
        requireNonBlank(body.name(), "name", 100, "Study name", out);
        requireNonBlank(body.uniqueProtocolId(), "uniqueProtocolId", 30,
                "Unique protocol id", out);
        if (body.uniqueProtocolId() != null
                && !body.uniqueProtocolId().trim().isEmpty()
                && !body.uniqueProtocolId().trim().matches("[A-Za-z0-9_-]+")) {
            out.add(fieldError("uniqueProtocolId",
                    "Unique protocol id may contain only letters, digits, underscores, and dashes"));
        }
        requireNonBlank(body.briefSummary(), "briefSummary", 255, "Brief summary", out);
        requireNonBlank(body.principalInvestigator(), "principalInvestigator", 255,
                "Principal investigator", out);
        requireNonBlank(body.sponsor(), "sponsor", 255, "Sponsor", out);
        maxLengthOptional(body.officialTitle(), "officialTitle", 255, "Official title", out);
        maxLengthOptional(body.secondaryProtocolId(), "secondaryProtocolId", 255,
                "Secondary protocol id", out);
        maxLengthOptional(body.collaborators(), "collaborators", 1000, "Collaborators", out);
        maxLengthOptional(body.protocolDescription(), "protocolDescription", 1000,
                "Protocol description", out);
        return out;
    }

    private static List<ValidationErrorBody.FieldError> validateUpdateStudyShape(
            UpdateStudyRequest body) {
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
        if (body.name() != null) {
            String s = body.name().trim();
            if (s.isEmpty()) out.add(fieldError("name", "Study name cannot be blank"));
            else if (s.length() > 100) out.add(fieldError("name", "Study name must be 100 characters or fewer"));
        }
        if (body.briefSummary() != null) {
            String s = body.briefSummary().trim();
            if (s.isEmpty()) out.add(fieldError("briefSummary", "Brief summary cannot be blank"));
            else if (s.length() > 255) out.add(fieldError("briefSummary", "Brief summary must be 255 characters or fewer"));
        }
        if (body.principalInvestigator() != null) {
            String s = body.principalInvestigator().trim();
            if (s.isEmpty()) out.add(fieldError("principalInvestigator", "Principal investigator cannot be blank"));
            else if (s.length() > 255) out.add(fieldError("principalInvestigator", "Principal investigator must be 255 characters or fewer"));
        }
        if (body.sponsor() != null) {
            String s = body.sponsor().trim();
            if (s.isEmpty()) out.add(fieldError("sponsor", "Sponsor cannot be blank"));
            else if (s.length() > 255) out.add(fieldError("sponsor", "Sponsor must be 255 characters or fewer"));
        }
        maxLengthOptional(body.officialTitle(), "officialTitle", 255, "Official title", out);
        maxLengthOptional(body.secondaryProtocolId(), "secondaryProtocolId", 255,
                "Secondary protocol id", out);
        maxLengthOptional(body.collaborators(), "collaborators", 1000, "Collaborators", out);
        maxLengthOptional(body.protocolDescription(), "protocolDescription", 1000,
                "Protocol description", out);
        return out;
    }

    private static void requireNonBlank(String v, String field, int max, String label,
            List<ValidationErrorBody.FieldError> out) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) out.add(fieldError(field, label + " is required"));
        else if (s.length() > max) out.add(fieldError(field, label + " must be " + max + " characters or fewer"));
    }

    private static void maxLengthOptional(String v, String field, int max, String label,
            List<ValidationErrorBody.FieldError> out) {
        if (v == null) return;
        String s = v.trim();
        if (s.length() > max) out.add(fieldError(field, label + " must be " + max + " characters or fewer"));
    }

    private static ValidationErrorBody.FieldError fieldError(String field, String msg) {
        return new ValidationErrorBody.FieldError(field, msg);
    }

    /**
     * audit_log_event_type row for study-identity edits — id seeded by
     * {@code lc-muw-2026-06-03-audit-event-type-study-identity.xml}.
     * Mapped to the "admin" variant in {@code AuditApiController.
     * variantForType}.
     */
    private static final int AUDIT_TYPE_STUDY_IDENTITY_UPDATED = 51;

    /**
     * Emit one {@code audit_log_event} row per identity field that
     * actually changed. Skipped when old/new are equal.
     *
     * <p>Direct JDBC — same pattern as
     * {@link MeApiController#emitProfileAudit}. The legacy
     * {@code AuditEventDAO.create} writes to the {@code audit_event}
     * table (not {@code audit_log_event}) and drops
     * {@code audit_log_event_type_id / old_value / new_value /
     * entity_name}, so events written via that path never surfaced in
     * the SPA Audit Log view.
     */
    private void writeStudyFieldAudit(@SuppressWarnings("unused") AuditEventDAO auditDAO,
                                      UserAccountBean editor,
                                      StudyBean target,
                                      String columnName,
                                      String oldValue,
                                      String newValue) {
        String oldVal = oldValue == null ? "" : oldValue;
        String newVal = newValue == null ? "" : newValue;
        if (oldVal.equals(newVal)) return;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study', ?, ?, ?, ?)")) {
            ps.setInt(1, AUDIT_TYPE_STUDY_IDENTITY_UPDATED);
            ps.setInt(2, editor.getId());
            ps.setInt(3, target.getId());
            ps.setString(4, columnName);
            ps.setString(5, oldVal);
            ps.setString(6, newVal);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Audit write failed for study {} field {} (continuing): {}",
                    target.getOid(), columnName, e.getMessage());
        }
    }

    /**
     * Direct INSERT into {@code audit_log_event} for the study lifecycle
     * (disable / restore) status flip. Audit-table unification (slice C,
     * 2026-06-12) — the legacy {@code AuditEventDAO.create} path wrote
     * to {@code audit_event} (invisible to the SPA Audit Log view); this
     * writer targets the unified surface with type
     * {@link AuditTypeIds#STUDY_LIFECYCLE_CHANGED}. The
     * {@code actionPrefix} argument is no longer persisted but is kept
     * in the signature for symmetry with the other lifecycle writers.
     */
    private void writeLifecycleAudit(int auditTypeId,
                                     UserAccountBean me,
                                     int entityId,
                                     String oid,
                                     Status oldStatus,
                                     Status newStatus,
                                     @SuppressWarnings("unused") String actionPrefix) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study', ?, ?, ?, ?)")) {
            ps.setInt(1, auditTypeId);
            ps.setInt(2, me.getId());
            ps.setInt(3, entityId);
            ps.setString(4, oid == null ? "" : oid);
            ps.setString(5, oldStatus == null ? "" : oldStatus.getName());
            ps.setString(6, newStatus == null ? "" : newStatus.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Audit write failed for study lifecycle oid={} (continuing): {}",
                    oid, e.getMessage());
        }
    }

    private StudyIdentityDto toIdentityDto(StudyBean s, StudyDAO studyDao) {
        StudyBean parent = s.getParentStudyId() > 0
                ? studyDao.findByPK(s.getParentStudyId()) : null;
        return new StudyIdentityDto(
                s.getOid(),
                nullToEmpty(s.getName()),
                nullToEmpty(s.getIdentifier()),
                nullToEmpty(s.getSummary()),
                nullToEmpty(s.getPrincipalInvestigator()),
                nullToEmpty(s.getSponsor()),
                nullToEmpty(s.getOfficialTitle()),
                nullToEmpty(s.getSecondaryIdentifier()),
                nullToEmpty(s.getCollaborators()),
                nullToEmpty(s.getProtocolDescription()),
                nullToEmpty(s.getContactEmail()),
                nullToEmpty(s.getProtocolType()),
                nullToEmpty(s.getPhase()),
                s.getStatus() == null ? "" : s.getStatus().getName(),
                parent == null ? null : parent.getOid(),
                parent == null ? null : parent.getName(),
                s.getDatePlannedStart() == null ? null
                        : java.time.Instant.ofEpochMilli(s.getDatePlannedStart().getTime())
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate().toString());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
