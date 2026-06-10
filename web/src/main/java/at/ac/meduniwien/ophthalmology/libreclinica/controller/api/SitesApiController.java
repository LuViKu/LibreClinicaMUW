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
 * Phase E A8.4 — site / multi-center setup.
 *
 * <p>Mirrors {@code CreateSubStudyServlet} collapsed into REST
 * endpoints. A site is structurally a {@link StudyBean} with
 * {@code parent_study_id > 0}; the legacy DAO surface handles both
 * shapes interchangeably (the {@code parentStudyId} column is the
 * sole discriminator).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /studies/{parentOid}/sites} — list sites
 *       (excludes removed unless {@code includeRemoved=true})</li>
 *   <li>{@code POST   /studies/{parentOid}/sites} — create a site</li>
 *   <li>{@code PUT    /studies/{parentOid}/sites/{siteOid}} — edit
 *       identity / facility fields</li>
 *   <li>{@code POST   /studies/{parentOid}/sites/{siteOid}/disable}
 *       — soft-delete</li>
 *   <li>{@code POST   /studies/{parentOid}/sites/{siteOid}/restore}
 *       — restore from soft-delete</li>
 * </ul>
 *
 * <p>Authorization: sysadmin OR director/coordinator bound to the
 * parent study (mirrors {@code CreateSubStudyServlet:73-86}). Site
 * edits also accept role bindings on the parent (handled by
 * {@link StudyAdminAuthorization#roleMayEditStudy}).
 *
 * <p>Site-level role legality (from A7): when
 * {@code initialPrincipalInvestigatorUserId} is set in the create
 * body, the user is auto-bound as {@link Role#INVESTIGATOR} on the
 * new site — INVESTIGATOR is legal on sites per
 * {@code UserAdminAuthorization.roleAssignmentIsLegal}.
 * COORDINATOR / STUDYDIRECTOR auto-binding is explicitly NOT
 * supported here — those roles must be granted at the parent study
 * via the A7.5 surface.
 */
@RestController
@RequestMapping("/api/v1/studies/{parentOid}/sites")
@Tag(name = "Sites", description = "Multi-center site setup (sub-studies under a parent).")
public class SitesApiController {

    private static final Logger LOG = LoggerFactory.getLogger(SitesApiController.class);

    private final DataSource dataSource;

    @Autowired
    public SitesApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ----------------------------------------------------------------- */
    /* GET — list sites of parent                                        */
    /* ----------------------------------------------------------------- */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> list(@PathVariable("parentOid") String parentOid,
                                  HttpSession session) {
        ResponseEntity<?> preflight = preflight(session, parentOid, /* mutating */ false);
        if (preflight != null) return preflight;

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean parent = studyDao.findByOid(parentOid);
        ArrayList<StudyBean> sites = studyDao.findAllByParent(parent.getId());

        List<StudyIdentityDto> out = new ArrayList<>(sites.size());
        for (StudyBean site : sites) {
            out.add(toIdentityDto(site, parent));
        }
        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* POST — create a new site                                          */
    /* ----------------------------------------------------------------- */

    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> create(@PathVariable("parentOid") String parentOid,
                                    @RequestBody(required = false) CreateSiteRequest body,
                                    HttpSession session) {
        ResponseEntity<?> preflight = preflight(session, parentOid, /* mutating */ true);
        if (preflight != null) return preflight;
        if (body == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError("body", "missing"))));
        }

        List<ValidationErrorBody.FieldError> errors =
                validateCreateShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed", errors));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean parent = studyDao.findByOid(parentOid);

        // Uniqueness checks (both against the whole study tree, not
        // just the parent's sites — uniqueProtocolId and name must be
        // globally unique).
        StudyBean uidCollision = studyDao.findByUniqueIdentifier(body.uniqueProtocolId().trim());
        if (uidCollision != null && uidCollision.getId() != 0) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
                            "uniqueProtocolId",
                            "Unique protocol id '" + body.uniqueProtocolId() + "' is already taken"))));
        }
        StudyBean nameCollision = studyDao.findByName(body.name().trim());
        if (nameCollision != null && nameCollision.getId() != 0) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
                            "name", "Study/site name '" + body.name() + "' is already taken"))));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean toCreate = new StudyBean();
        toCreate.setName(body.name().trim());
        toCreate.setIdentifier(body.uniqueProtocolId().trim());
        toCreate.setSummary(body.briefSummary() == null ? "" : body.briefSummary().trim());
        toCreate.setPrincipalInvestigator(body.principalInvestigator().trim());
        toCreate.setSponsor(parent.getSponsor() == null ? "" : parent.getSponsor());
        toCreate.setProtocolType(parent.getProtocolType() == null ? "" : parent.getProtocolType());
        toCreate.setPhase(parent.getPhase() == null ? "" : parent.getPhase());
        if (body.facilityName() != null) toCreate.setFacilityName(body.facilityName().trim());
        if (body.facilityCity() != null) toCreate.setFacilityCity(body.facilityCity().trim());
        if (body.facilityState() != null) toCreate.setFacilityState(body.facilityState().trim());
        if (body.facilityZip() != null) toCreate.setFacilityZip(body.facilityZip().trim());
        if (body.facilityCountry() != null) toCreate.setFacilityCountry(body.facilityCountry().trim());
        if (body.facilityContactName() != null) toCreate.setFacilityContactName(body.facilityContactName().trim());
        if (body.facilityContactDegree() != null) toCreate.setFacilityContactDegree(body.facilityContactDegree().trim());
        if (body.facilityContactPhone() != null) toCreate.setFacilityContactPhone(body.facilityContactPhone().trim());
        if (body.facilityContactEmail() != null) toCreate.setFacilityContactEmail(body.facilityContactEmail().trim());
        toCreate.setStatus(Status.PENDING);
        toCreate.setOwner(me);
        toCreate.setParentStudyId(parent.getId());

        StudyBean persisted = studyDao.create(toCreate);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("StudyDAO.create returned no row for site name={} parentOid={}",
                    body.name(), parentOid);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist new site"));
        }

        String generatedOid = "S_" + body.uniqueProtocolId().trim().toUpperCase();
        persisted.setOid(generatedOid);
        studyDao.update(persisted);

        // Optional auto-bind of the initial Principal Investigator on
        // the new site. INVESTIGATOR is legal on sites (per A7's
        // roleAssignmentIsLegal). Skipping when no userId was passed.
        if (body.initialPrincipalInvestigatorUserId() != null
                && body.initialPrincipalInvestigatorUserId() > 0) {
            try {
                UserAccountDAO userDao = new UserAccountDAO(dataSource);
                UserAccountBean piUser = (UserAccountBean)
                        userDao.findByPK(body.initialPrincipalInvestigatorUserId());
                if (piUser != null && piUser.getId() != 0) {
                    StudyUserRoleBean binding = new StudyUserRoleBean();
                    binding.setStudyId(persisted.getId());
                    binding.setRoleName(Role.INVESTIGATOR.getName());
                    binding.setStatus(Status.AVAILABLE);
                    binding.setOwner(me);
                    binding.setUserName(piUser.getName());
                    binding.setUserAccountId(piUser.getId());
                    userDao.createStudyUserRole(piUser, binding);
                } else {
                    LOG.warn("initialPrincipalInvestigatorUserId={} not found — skipping auto-bind for site {}",
                            body.initialPrincipalInvestigatorUserId(), generatedOid);
                }
            } catch (Exception e) {
                LOG.warn("Failed to auto-bind PI on site {} (continuing): {}",
                        generatedOid, e.getMessage());
            }
        }

        LOG.info("Create site: parentOid={} siteOid={} name={} by user={}",
                parentOid, generatedOid, body.name(), me.getName());

        return ResponseEntity.status(201).body(toIdentityDto(persisted, parent));
    }

    /* ----------------------------------------------------------------- */
    /* PUT — edit site identity                                          */
    /* ----------------------------------------------------------------- */

    @PutMapping("/{siteOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> update(@PathVariable("parentOid") String parentOid,
                                    @PathVariable("siteOid") String siteOid,
                                    @RequestBody(required = false) UpdateSiteRequest body,
                                    HttpSession session) {
        ResponseEntity<?> preflight = preflight(session, parentOid, /* mutating */ true);
        if (preflight != null) return preflight;
        if (body == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError("body", "missing"))));
        }

        List<ValidationErrorBody.FieldError> errors = validateUpdateShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed", errors));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean parent = studyDao.findByOid(parentOid);
        StudyBean site = studyDao.findByOid(siteOid);
        if (site == null || site.getId() == 0 || site.getParentStudyId() != parent.getId()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No site with oid '" + siteOid + "' under parent '" + parentOid + "'"));
        }
        if (!StudyAdminAuthorization.studyAcceptsWrites(site)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Site is " + site.getStatus().getName().toLowerCase()
                            + " — writes are refused until it is unlocked"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        if (body.name() != null) diffString(site::getName, site::setName, body.name(), "name", auditDAO, me, site);
        if (body.briefSummary() != null) diffString(site::getSummary, site::setSummary, body.briefSummary(), "summary", auditDAO, me, site);
        if (body.principalInvestigator() != null) diffString(site::getPrincipalInvestigator, site::setPrincipalInvestigator, body.principalInvestigator(), "principal_investigator", auditDAO, me, site);
        if (body.facilityName() != null) diffString(site::getFacilityName, site::setFacilityName, body.facilityName(), "facility_name", auditDAO, me, site);
        if (body.facilityCity() != null) diffString(site::getFacilityCity, site::setFacilityCity, body.facilityCity(), "facility_city", auditDAO, me, site);
        if (body.facilityState() != null) diffString(site::getFacilityState, site::setFacilityState, body.facilityState(), "facility_state", auditDAO, me, site);
        if (body.facilityZip() != null) diffString(site::getFacilityZip, site::setFacilityZip, body.facilityZip(), "facility_zip", auditDAO, me, site);
        if (body.facilityCountry() != null) diffString(site::getFacilityCountry, site::setFacilityCountry, body.facilityCountry(), "facility_country", auditDAO, me, site);
        if (body.facilityContactName() != null) diffString(site::getFacilityContactName, site::setFacilityContactName, body.facilityContactName(), "facility_contact_name", auditDAO, me, site);
        if (body.facilityContactDegree() != null) diffString(site::getFacilityContactDegree, site::setFacilityContactDegree, body.facilityContactDegree(), "facility_contact_degree", auditDAO, me, site);
        if (body.facilityContactPhone() != null) diffString(site::getFacilityContactPhone, site::setFacilityContactPhone, body.facilityContactPhone(), "facility_contact_phone", auditDAO, me, site);
        if (body.facilityContactEmail() != null) diffString(site::getFacilityContactEmail, site::setFacilityContactEmail, body.facilityContactEmail(), "facility_contact_email", auditDAO, me, site);

        site.setUpdater(me);
        site.setUpdatedDate(new java.util.Date());
        studyDao.update(site);

        LOG.info("Update site: parentOid={} siteOid={} by user={}", parentOid, siteOid, me.getName());

        return ResponseEntity.ok(toIdentityDto(site, parent));
    }

    /* ----------------------------------------------------------------- */
    /* POST disable / POST restore                                       */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{siteOid}/disable")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> disable(@PathVariable("parentOid") String parentOid,
                                     @PathVariable("siteOid") String siteOid,
                                     HttpSession session) {
        return lifecycle(parentOid, siteOid, session, Status.DELETED, "disable");
    }

    @PostMapping("/{siteOid}/restore")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyIdentityDto.class)))
    public ResponseEntity<?> restore(@PathVariable("parentOid") String parentOid,
                                     @PathVariable("siteOid") String siteOid,
                                     HttpSession session) {
        return lifecycle(parentOid, siteOid, session, Status.AVAILABLE, "restore");
    }

    private ResponseEntity<?> lifecycle(String parentOid, String siteOid, HttpSession session,
                                        Status target, String operation) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!StudyAdminAuthorization.roleMayLifecycleStudy(me)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit site " + operation + " — sysadmin only"));
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean parent = studyDao.findByOid(parentOid);
        if (parent == null || parent.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No parent study with oid '" + parentOid + "'"));
        }
        StudyBean site = studyDao.findByOid(siteOid);
        if (site == null || site.getId() == 0 || site.getParentStudyId() != parent.getId()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No site with oid '" + siteOid + "' under parent '" + parentOid + "'"));
        }
        if (site.getStatus() != null && site.getStatus().equals(target)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Site is already " + target.getName().toLowerCase()));
        }
        if (target == Status.AVAILABLE
                && site.getStatus() != Status.DELETED
                && site.getStatus() != Status.AUTO_DELETED) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Site is not disabled — nothing to restore"));
        }

        Status oldStatus = site.getStatus();
        site.setStatus(target);
        site.setUpdater(me);
        site.setUpdatedDate(new java.util.Date());
        studyDao.updateStudyStatus(site);

        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(site.getId());
            ae.setStudyName(site.getName() == null ? "" : site.getName());
            ae.setAuditTable("study");
            ae.setEntityId(site.getId());
            ae.setColumnName("status_id");
            ae.setOldValue(oldStatus == null ? "" : String.valueOf(oldStatus.getId()));
            ae.setNewValue(String.valueOf(target.getId()));
            ae.setActionMessage("site_" + operation + ": " + siteOid
                    + " (" + (oldStatus == null ? "?" : oldStatus.getName())
                    + " → " + target.getName() + ") by " + me.getName());
            new AuditEventDAO(dataSource).create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for site_{} oid={} (continuing): {}",
                    operation, siteOid, e.getMessage());
        }

        LOG.info("Site {}: parentOid={} siteOid={} by user={}",
                operation, parentOid, siteOid, me.getName());

        return ResponseEntity.ok(toIdentityDto(site, parent));
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /** Shared 401 / 404 (parent) / 409 (parent is itself a site) / 403 / 409 (writes) preflight. */
    private ResponseEntity<?> preflight(HttpSession session, String parentOid, boolean mutating) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (parentOid == null || parentOid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "parentOid path variable is required"));
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean parent = studyDao.findByOid(parentOid);
        if (parent == null || parent.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + parentOid + "'"));
        }
        if (parent.getParentStudyId() > 0) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Sites may only be created under a top-level study (got a site)"));
        }
        if (mutating) {
            StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
            if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, parent)) {
                return ResponseEntity.status(403).body(Map.of("message",
                        "Your role does not permit managing sites under this study"));
            }
            if (!StudyAdminAuthorization.studyAcceptsWrites(parent)) {
                return ResponseEntity.status(409).body(Map.of("message",
                        "Parent study is " + parent.getStatus().getName().toLowerCase()
                                + " — writes are refused until it is unlocked"));
            }
        }
        return null;
    }

    private static List<ValidationErrorBody.FieldError> validateCreateShape(
            CreateSiteRequest body) {
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
        requireNonBlank(body.name(), "name", 100, "Site name", out);
        requireNonBlank(body.uniqueProtocolId(), "uniqueProtocolId", 30, "Unique protocol id", out);
        if (body.uniqueProtocolId() != null && !body.uniqueProtocolId().trim().isEmpty()
                && !body.uniqueProtocolId().trim().matches("[A-Za-z0-9_-]+")) {
            out.add(fe("uniqueProtocolId",
                    "Unique protocol id may contain only letters, digits, underscores, and dashes"));
        }
        requireNonBlank(body.principalInvestigator(), "principalInvestigator", 255,
                "Principal investigator", out);
        if (body.briefSummary() != null && body.briefSummary().length() > 255) {
            out.add(fe("briefSummary", "Brief summary must be 255 characters or fewer"));
        }
        return out;
    }

    private static List<ValidationErrorBody.FieldError> validateUpdateShape(
            UpdateSiteRequest body) {
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
        if (body.name() != null) {
            String s = body.name().trim();
            if (s.isEmpty()) out.add(fe("name", "Site name cannot be blank"));
            else if (s.length() > 100) out.add(fe("name", "Site name must be 100 characters or fewer"));
        }
        if (body.briefSummary() != null && body.briefSummary().length() > 255) {
            out.add(fe("briefSummary", "Brief summary must be 255 characters or fewer"));
        }
        if (body.principalInvestigator() != null) {
            String s = body.principalInvestigator().trim();
            if (s.isEmpty()) out.add(fe("principalInvestigator", "Principal investigator cannot be blank"));
            else if (s.length() > 255) out.add(fe("principalInvestigator", "Principal investigator must be 255 characters or fewer"));
        }
        return out;
    }

    private static void requireNonBlank(String v, String field, int max, String label,
            List<ValidationErrorBody.FieldError> out) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) out.add(fe(field, label + " is required"));
        else if (s.length() > max) out.add(fe(field, label + " must be " + max + " characters or fewer"));
    }

    private static ValidationErrorBody.FieldError fe(String field, String msg) {
        return new ValidationErrorBody.FieldError(field, msg);
    }

    /**
     * Per-field diff helper — reads the current value via {@code getter},
     * compares against the trimmed new value, applies the setter if
     * different, and writes one audit row.
     */
    private void diffString(java.util.function.Supplier<String> getter,
                            java.util.function.Consumer<String> setter,
                            String newValue,
                            String columnName,
                            AuditEventDAO auditDAO,
                            UserAccountBean me,
                            StudyBean site) {
        String oldVal = getter.get();
        String trimmed = newValue.trim();
        if (java.util.Objects.equals(nullToEmpty(oldVal), trimmed)) return;
        setter.accept(trimmed);
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(site.getId());
            ae.setStudyName(site.getName() == null ? "" : site.getName());
            ae.setAuditTable("study");
            ae.setEntityId(site.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldVal == null ? "" : oldVal);
            ae.setNewValue(trimmed);
            ae.setActionMessage("site_update: " + (site.getOid() == null ? "?" : site.getOid())
                    + "." + columnName + " '" + (oldVal == null ? "" : oldVal) + "' → '" + trimmed + "'");
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for site field {}={} (continuing): {}",
                    columnName, trimmed, e.getMessage());
        }
    }

    private static StudyIdentityDto toIdentityDto(StudyBean site, StudyBean parent) {
        return new StudyIdentityDto(
                site.getOid(),
                nullToEmpty(site.getName()),
                nullToEmpty(site.getIdentifier()),
                nullToEmpty(site.getSummary()),
                nullToEmpty(site.getPrincipalInvestigator()),
                nullToEmpty(site.getSponsor()),
                nullToEmpty(site.getOfficialTitle()),
                nullToEmpty(site.getSecondaryIdentifier()),
                nullToEmpty(site.getCollaborators()),
                nullToEmpty(site.getProtocolDescription()),
                nullToEmpty(site.getContactEmail()),
                nullToEmpty(site.getProtocolType()),
                nullToEmpty(site.getPhase()),
                site.getStatus() == null ? "" : site.getStatus().getName(),
                parent == null ? null : parent.getOid(),
                parent == null ? null : parent.getName(),
                site.getDatePlannedStart() == null ? null
                        : java.time.Instant.ofEpochMilli(site.getDatePlannedStart().getTime())
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate().toString());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
