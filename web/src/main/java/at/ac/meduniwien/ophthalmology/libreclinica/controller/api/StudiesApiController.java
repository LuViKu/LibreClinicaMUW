/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
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
        ArrayList<StudyUserRoleBean> grants = userDAO.findStudyByUser(ub.getName(), allStudies);

        // Index studies by id for parent-name lookup, and by id for role join.
        Map<Integer, StudyBean> studyById = new HashMap<>();
        for (StudyBean s : allStudies) studyById.put(s.getId(), s);

        int activeStudyId = ub.getActiveStudyId();
        List<StudyOptionDto> out = new ArrayList<>();
        for (StudyUserRoleBean r : grants) {
            StudyBean s = studyById.get(r.getStudyId());
            if (s == null) continue;
            boolean isSite = s.getParentStudyId() > 0;
            StudyBean parent = isSite ? studyById.get(s.getParentStudyId()) : null;
            out.add(new StudyOptionDto(
                    s.getOid(),
                    s.getName(),
                    parent == null ? null : parent.getOid(),
                    parent == null ? null : parent.getName(),
                    r.getRole() == null ? "Investigator"
                            : RoleMapper.toSpaRole(r.getRole().getName()),
                    isSite,
                    s.getId() == activeStudyId
            ));
        }
        return ResponseEntity.ok(out);
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
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        // Shape-level validation (no DAO calls).
        List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                validateCreateStudyShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        // DAO-bound validation: uniqueness.
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean uidCollision = studyDao.findByUniqueIdentifier(body.uniqueProtocolId().trim());
        if (uidCollision != null && uidCollision.getId() != 0) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "uniqueProtocolId",
                            "Unique protocol id '" + body.uniqueProtocolId()
                                    + "' is already taken"))));
        }
        StudyBean nameCollision = studyDao.findByName(body.name().trim());
        if (nameCollision != null && nameCollision.getId() != 0) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
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
        studyDao.update(persisted);

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
        } catch (Exception e) {
            LOG.warn("Failed to auto-bind creator as COORDINATOR on study {} (continuing): {}",
                    persisted.getOid(), e.getMessage());
        }

        LOG.info("Create study: oid={} name={} by admin={}",
                generatedOid, body.name(), me.getName());

        return ResponseEntity.status(201).body(toIdentityDto(persisted, studyDao));
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
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                validateUpdateStudyShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
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
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(target.getId());
            ae.setStudyName(target.getName() == null ? "" : target.getName());
            ae.setAuditTable("study");
            ae.setEntityId(target.getId());
            ae.setColumnName("status_id");
            ae.setOldValue(oldStatus == null ? "" : String.valueOf(oldStatus.getId()));
            ae.setNewValue(String.valueOf(targetStatus.getId()));
            ae.setActionMessage("study_" + operation + ": " + studyOid
                    + " (" + (oldStatus == null ? "?" : oldStatus.getName())
                    + " → " + targetStatus.getName() + ") by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for study_{} oid={} (continuing): {}",
                    operation, studyOid, e.getMessage());
        }

        LOG.info("Study {}: oid={} by admin={}", operation, studyOid, me.getName());
        return ResponseEntity.ok(toIdentityDto(target, studyDao));
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateCreateStudyShape(
            CreateStudyRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
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

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateUpdateStudyShape(
            UpdateStudyRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
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
            List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) out.add(fieldError(field, label + " is required"));
        else if (s.length() > max) out.add(fieldError(field, label + " must be " + max + " characters or fewer"));
    }

    private static void maxLengthOptional(String v, String field, int max, String label,
            List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        if (v == null) return;
        String s = v.trim();
        if (s.length() > max) out.add(fieldError(field, label + " must be " + max + " characters or fewer"));
    }

    private static SubjectsApiController.ValidationErrorBody.FieldError fieldError(String field, String msg) {
        return new SubjectsApiController.ValidationErrorBody.FieldError(field, msg);
    }

    private void writeStudyFieldAudit(AuditEventDAO auditDAO,
                                      UserAccountBean editor,
                                      StudyBean target,
                                      String columnName,
                                      String oldValue,
                                      String newValue) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(editor.getId());
            ae.setStudyId(target.getId());
            ae.setStudyName(target.getName() == null ? "" : target.getName());
            ae.setAuditTable("study");
            ae.setEntityId(target.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            ae.setActionMessage("study_identity_update: " + (target.getOid() == null ? "?" : target.getOid())
                    + "." + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "'");
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for study field {}={} (continuing): {}",
                    columnName, newValue, e.getMessage());
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
                parent == null ? null : parent.getName());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
