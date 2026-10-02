/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.audit.FailureAuditTemplate;
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
import at.ac.meduniwien.ophthalmology.libreclinica.domain.managestudy.MailNotificationType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import java.sql.ResultSet;
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
@SuppressWarnings("null")
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

        // The OID is the one StudyDAO.createStepOne generated and stored
        // (StudyOidGenerator, as in the legacy CreateStudyServlet: "S_" and
        // the first 8 letters and digits of the unique protocol id,
        // upper-cased). An update cannot change it: updateStepOne does not
        // write oc_oid. Read it back, so the response carries the OID the
        // study can be opened by.
        persisted = studyDao.findByPK(persisted.getId());
        String generatedOid = persisted.getOid();

        AuditEventDAO auditEventDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(auditEventDAO,
                AuditTypeIds.STUDY_CREATED,
                me, persisted, null,
                "Study created", "study", persisted.getId(),
                "study_id", "", String.valueOf(persisted.getId()));

        // Auto-bind the caller on the new study so they can immediately
        // build it out without an explicit grant. 2026-06-22 — sysadmin
        // creators bind as ADMIN (matches the seeded Default Study
        // binding); non-sysadmin paths fall through to COORDINATOR for
        // backward-compat (the create-study endpoint itself is sysadmin-
        // gated today, so this branch is theoretical until that gate
        // changes — kept explicit so a future relaxation doesn't silently
        // demote the seeded behaviour).
        try {
            UserAccountDAO userDao = new UserAccountDAO(dataSource);
            Role grantedRole = me.isSysAdmin() ? Role.ADMIN : Role.COORDINATOR;
            StudyUserRoleBean binding = new StudyUserRoleBean();
            binding.setStudyId(persisted.getId());
            binding.setRoleName(grantedRole.getName());
            binding.setStatus(Status.AVAILABLE);
            binding.setOwner(me);
            binding.setUserName(me.getName());
            binding.setUserAccountId(me.getId());
            userDao.createStudyUserRole(me, binding);

            EventCrfsApiController.writeAuditEvent(auditEventDAO,
                    AuditTypeIds.USER_ACCOUNT_ADMIN_ACTION,
                    me, persisted, null,
                    "Study role granted (initial) — user=" + me.getName()
                            + " role=" + grantedRole.getName(),
                    "study_user_role", 0, "role_id",
                    "", String.valueOf(grantedRole.getId()));
        } catch (Exception e) {
            LOG.warn("Failed to auto-bind creator on study {} (continuing): {}",
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
        if (!StudyAdminAuthorization.userMayEditStudy(me, target, dataSource)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing this study"));
        }
        if (!StudyAdminAuthorization.studyAcceptsWrites(target)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study is " + target.getStatus().getName().toLowerCase()
                            + " — writes are refused until it is unlocked"));
        }
        // Legacy parity (UpdateStudyServletNew "contact_email_mandatory"):
        // the login e-mail notification needs somewhere to write from.
        if (body.contactEmail() != null && body.contactEmail().trim().isEmpty()
                && MailNotificationType.ENABLED.name().equalsIgnoreCase(target.getMailNotification())) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(fieldError("contactEmail",
                            "A contact e-mail is required while login e-mail notification is enabled"))));
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
        boolean contactEmailChanged = false;
        if (body.contactEmail() != null) {
            String oldVal = target.getContactEmail();
            String newVal = body.contactEmail().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setContactEmail(newVal);
                writeStudyFieldAudit(auditDAO, me, target, "contact_email", oldVal, newVal);
                contactEmailChanged = true;
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
        if (contactEmailChanged && target.getParentStudyId() == 0) {
            copyContactEmailToSites(target, me);
        }

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
    /* GET  /api/v1/studies/{studyOid}/removal-preview                    */
    /*   (Phase E A8.1 — study lifecycle, sysadmin only)                  */
    /* ----------------------------------------------------------------- */

    /**
     * Body of {@code POST /studies/{oid}/disable} and {@code /restore}.
     * The reason is required; it lands in the lifecycle audit row's
     * {@code reason_for_change}.
     */
    @Schema(name = "StudyLifecycleRequest")
    public record StudyLifecycleRequest(String reason) {}

    /**
     * Removes a top-level study and auto-removes everything under it:
     * sites, role bindings, subjects, subject-group classes and maps,
     * event definitions, events, event CRFs, item data and datasets. The
     * same cascade as the legacy {@code RemoveStudyServlet}, in one
     * transaction; {@link StudyLifecycleCascade} lists where it differs.
     *
     * <p>Status codes: {@code 200} with the removed study, {@code 400}
     * without a reason, {@code 401}, {@code 403} for a non-sysadmin,
     * {@code 404}, {@code 409} for a site (sites have their own
     * endpoint) or a study that is already removed.
     */
    @PostMapping("/{studyOid}/disable")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> disable(@PathVariable("studyOid") String studyOid,
                                     @RequestBody(required = false) StudyLifecycleRequest body,
                                     HttpSession session) {
        return lifecycle(studyOid, body, session, Status.DELETED, "disable");
    }

    /**
     * Restores a removed study and what its removal auto-removed, like the
     * legacy {@code RestoreStudyServlet}. The study returns to the status
     * it had when it was removed. Same status codes as {@link #disable},
     * with {@code 409} for a study that is not removed.
     */
    @PostMapping("/{studyOid}/restore")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> restore(@PathVariable("studyOid") String studyOid,
                                     @RequestBody(required = false) StudyLifecycleRequest body,
                                     HttpSession session) {
        return lifecycle(studyOid, body, session, Status.AVAILABLE, "restore");
    }

    /**
     * What removing the study would take with it, counted per kind, for
     * the confirmation the SPA shows before {@link #disable}. Reads only.
     * Sysadmin only, like the removal itself.
     */
    @GetMapping("/{studyOid}/removal-preview")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyRemovalPreviewDto.class)))
    public ResponseEntity<?> removalPreview(@PathVariable("studyOid") String studyOid,
                                            HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!StudyAdminAuthorization.roleMayLifecycleStudy(me)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit study removal — sysadmin only"));
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        if (target.getParentStudyId() > 0) {
            return siteRefusal(studyOid);
        }
        try (Connection c = dataSource.getConnection()) {
            StudyLifecycleCascade.Impact impact = StudyLifecycleCascade.previewRemoval(c, target.getId());
            return ResponseEntity.ok(new StudyRemovalPreviewDto(
                    target.getOid(),
                    nullToEmpty(target.getName()),
                    impact.siteNames(),
                    impact.roleBindings(),
                    impact.subjects(),
                    impact.groupClasses(),
                    impact.eventDefinitions(),
                    impact.events(),
                    impact.eventCrfs(),
                    impact.itemData(),
                    impact.datasets()));
        } catch (SQLException e) {
            LOG.warn("Removal preview failed for study {}: {}", studyOid, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to count what the removal would take — see server log."));
        }
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

        // Cascade to the live sites only. A removed or auto-removed site
        // keeps its status and the status it recorded at removal: making
        // it available or locked here would revive it while its subjects,
        // roles and data stay auto-removed, and a later study removal and
        // restore would then bring back what was removed on purpose.
        // (StudyDAO.updateSitesStatus rewrites every site, and overwrites
        // old_status_id with the parent's.)
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study SET status_id = ?, date_updated = now(), update_id = ? "
                             + "WHERE parent_study_id = ? AND status_id NOT IN (5, 7)")) {
            ps.setInt(1, target.getId());
            ps.setInt(2, me.getId());
            ps.setInt(3, target_.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
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
                                        StudyLifecycleRequest body,
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
        boolean removal = targetStatus == Status.DELETED;
        String reason = body == null || body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty() || reason.length() > 1000) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(fieldError("reason", reason.isEmpty()
                            ? "A reason is required to " + (removal ? "remove" : "restore") + " a study"
                            : "Reason must be 1000 characters or fewer"))));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        if (target.getParentStudyId() > 0) {
            return siteRefusal(studyOid);
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

        final Status oldStatus = target.getStatus();
        final int studyId = target.getId();
        StudyLifecycleCascade.Impact impact;
        try {
            impact = FailureAuditTemplate.runOrAudit(
                    new AuditEventDAO(dataSource),
                    me.getId(),
                    "study",
                    studyId,
                    "study_" + operation,
                    MDC.get("reqId"),
                    () -> applyLifecycle(studyId, studyOid, me, removal, oldStatus, reason));
        } catch (StudyLifecycleCascade.StatusChangedException e) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study '" + studyOid + "' was " + (removal ? "removed" : "restored")
                            + " by another request; nothing was changed. Reload and check its status."));
        } catch (Exception e) {
            LOG.error("Study {} failed for oid={} by admin={}", operation, studyOid, me.getName(), e);
            return ResponseEntity.internalServerError().body(Map.of("message",
                    "Failed to " + (removal ? "remove" : "restore") + " the study; nothing was changed. "
                            + "See server log."));
        }

        StudyBean refreshed = studyDao.findByPK(studyId);

        // Legacy parity: RemoveStudyServlet / RestoreStudyServlet update
        // the session's current study in place when the change touches it.
        StudyBean sessionStudy = (StudyBean) session.getAttribute("study");
        if (sessionStudy != null && sessionStudy.getId() > 0
                && (sessionStudy.getId() == studyId || sessionStudy.getParentStudyId() == studyId)) {
            StudyBean current = sessionStudy.getId() == studyId
                    ? refreshed : studyDao.findByPK(sessionStudy.getId());
            if (current != null && current.getStatus() != null) {
                sessionStudy.setStatus(current.getStatus());
            }
        }

        LOG.info("Study {}: oid={} by admin={} sites={} roles={} subjects={} definitions={} events={} "
                        + "eventCrfs={} items={} datasets={}",
                operation, studyOid, me.getName(), impact.siteNames().size(), impact.roleBindings(),
                impact.subjects(), impact.eventDefinitions(), impact.events(), impact.eventCrfs(),
                impact.itemData(), impact.datasets());
        return ResponseEntity.ok(toIdentityDto(refreshed, studyDao));
    }

    /**
     * Runs the cascade and writes its audit row in one transaction, so a
     * study is never left half removed and never changes without its
     * audit row.
     */
    private StudyLifecycleCascade.Impact applyLifecycle(int studyId, String studyOid, UserAccountBean me,
                                                        boolean removal, Status oldStatus, String reason)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                StudyLifecycleCascade.Impact impact = removal
                        ? StudyLifecycleCascade.remove(c, studyId, me.getId())
                        : StudyLifecycleCascade.restore(c, studyId, me.getId());
                Status newStatus;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT status_id FROM study WHERE study_id = ?")) {
                    ps.setInt(1, studyId);
                    try (ResultSet rs = ps.executeQuery()) {
                        newStatus = rs.next() ? Status.get(rs.getInt(1)) : null;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                                + "user_id, audit_table, entity_id, entity_name, "
                                + "reason_for_change, old_value, new_value) "
                                + "VALUES (?, now(), ?, 'study', ?, ?, ?, ?, ?)")) {
                    ps.setInt(1, AuditTypeIds.STUDY_LIFECYCLE_CHANGED);
                    ps.setInt(2, me.getId());
                    ps.setInt(3, studyId);
                    ps.setString(4, studyOid == null ? "" : studyOid);
                    ps.setString(5, reason);
                    ps.setString(6, oldStatus == null ? "" : oldStatus.getName());
                    ps.setString(7, newStatus == null ? "" : newStatus.getName());
                    ps.executeUpdate();
                }
                c.commit();
                return impact;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    private static ResponseEntity<?> siteRefusal(String studyOid) {
        return ResponseEntity.status(409).body(Map.of("message",
                "'" + studyOid + "' is a site. Sites are removed and restored on the Sites page, "
                        + "not through the study endpoint."));
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
        contactEmailOptional(body.contactEmail(), out);
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
        contactEmailOptional(body.contactEmail(), out);
        return out;
    }

    /**
     * Blank is allowed; otherwise the legacy {@code Validator.IS_A_EMAIL}
     * rule: {@code x@y.z}, at most 254 characters.
     */
    private static void contactEmailOptional(String v, List<ValidationErrorBody.FieldError> out) {
        if (v == null) return;
        String s = v.trim();
        if (s.isEmpty()) return;
        if (s.length() > 254 || !s.matches(".+@.+\\..*")) {
            out.add(fieldError("contactEmail", "Contact e-mail must be a valid e-mail address"));
        }
    }

    /**
     * A site carries its parent's contact e-mail: the legacy
     * {@code UpdateStudyServletNew} copies it to every site on each save,
     * and {@code CreateSubStudyServlet} at creation. Only the column
     * changes, so nothing else on the site row is rewritten.
     */
    private void copyContactEmailToSites(StudyBean parent, UserAccountBean me) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study SET contact_email = ?, date_updated = now(), update_id = ? "
                             + "WHERE parent_study_id = ?")) {
            if (parent.contactEmailAbsent()) ps.setNull(1, java.sql.Types.VARCHAR);
            else ps.setString(1, parent.getContactEmail());
            ps.setInt(2, me.getId());
            ps.setInt(3, parent.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Copying the contact e-mail of study {} to its sites failed: {}",
                    parent.getOid(), e.getMessage());
        }
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
