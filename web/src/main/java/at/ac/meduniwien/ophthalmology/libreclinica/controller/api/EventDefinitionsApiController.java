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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.SourceDataVerification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Phase E A8.2 — event-definition CRUD endpoints.
 *
 * <p>Mirrors {@code DefineStudyEventServlet} +
 * {@code UpdateEventDefinitionServlet} collapsed into REST endpoints.
 * CRF assignments are NOT part of this slice — they ship in A8.3 via
 * dedicated {@code /event-definitions/{sedOid}/crfs} endpoints. The
 * SPA's flow: create the event definition first (via this controller),
 * then add CRFs via the A8.3 surface.
 *
 * <p>Authorization: same triad as study identity edits — sysadmin OR
 * director/coordinator bound to the parent study (see
 * {@link StudyAdminAuthorization#roleMayEditStudy}). The legacy
 * "events may only be added at the top-level study" guard is preserved
 * via {@code parent_study_id == 0} on the target.
 *
 * <p>{@code studyOid} in the path is always the parent study OID. The
 * controller refuses 409 when the resolved study has a non-zero
 * {@code parent_study_id} (i.e. it's a site).
 *
 * <p>Per-field diff + audit emission on PUT: one
 * {@code audit_log_event} row per changed column, mirroring A7.2 +
 * A8.1's pattern.
 */
@RestController
@RequestMapping("/api/v1/studies/{studyOid}/event-definitions")
@Tag(name = "EventDefinitions", description = "Study-level event-definition CRUD (build-study surface).")
public class EventDefinitionsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(EventDefinitionsApiController.class);

    private static final Set<String> LEGAL_TYPES = Set.of("scheduled", "unscheduled", "common");

    private final DataSource dataSource;

    @Autowired
    public EventDefinitionsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ----------------------------------------------------------------- */
    /* GET — list event definitions for the study                        */
    /* ----------------------------------------------------------------- */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = EventDefinitionDto.class)))
    public ResponseEntity<?> list(@PathVariable("studyOid") String studyOid,
                                  HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ false);
        if (guard != null) return guard;

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        ArrayList<StudyEventDefinitionBean> beans = sedDao.findAllByStudy(study);

        List<EventDefinitionDto> out = new ArrayList<>(beans.size());
        for (StudyEventDefinitionBean sed : beans) {
            out.add(toDto(sed));
        }
        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* POST — create a new event definition                               */
    /* ----------------------------------------------------------------- */

    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = EventDefinitionDto.class)))
    public ResponseEntity<?> create(@PathVariable("studyOid") String studyOid,
                                    @RequestBody(required = false) CreateEventDefinitionRequest body,
                                    HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                validateCreateShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);

        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);

        // Name-uniqueness check within the study (mirrors legacy
        // DefineStudyEventServlet's name-collision detection).
        ArrayList<StudyEventDefinitionBean> existing = sedDao.findAllByStudy(study);
        String newName = body.name().trim();
        int nextOrdinal = 0;
        for (StudyEventDefinitionBean other : existing) {
            if (other.getStatus() != null && other.getStatus().isDeleted()) continue;
            if (newName.equalsIgnoreCase(other.getName())) {
                return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                        "Validation failed",
                        List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                                "name", "Event '" + newName + "' already exists in this study"))));
            }
            if (other.getOrdinal() > nextOrdinal) nextOrdinal = other.getOrdinal();
        }

        StudyEventDefinitionBean toCreate = new StudyEventDefinitionBean();
        toCreate.setName(newName);
        toCreate.setStudyId(study.getId());
        toCreate.setType(body.type().trim());
        toCreate.setDescription(body.description() == null ? "" : body.description().trim());
        toCreate.setCategory(body.category() == null ? "" : body.category().trim());
        toCreate.setRepeating(Boolean.TRUE.equals(body.repeating()));
        toCreate.setOrdinal(nextOrdinal + 1);
        toCreate.setStatus(Status.AVAILABLE);
        toCreate.setOwner(me);
        toCreate.setCreatedDate(new java.util.Date());

        StudyEventDefinitionBean persisted = sedDao.create(toCreate);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("StudyEventDefinitionDAO.create returned no row for name={}", body.name());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist event definition"));
        }

        LOG.info("Create event definition: oid={} name={} study={} by admin={}",
                persisted.getOid(), persisted.getName(), studyOid, me.getName());

        return ResponseEntity.status(201).body(toDto(persisted));
    }

    /* ----------------------------------------------------------------- */
    /* PUT — edit an event definition                                    */
    /* ----------------------------------------------------------------- */

    @PutMapping("/{sedOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventDefinitionDto.class)))
    public ResponseEntity<?> update(@PathVariable("studyOid") String studyOid,
                                    @PathVariable("sedOid") String sedOid,
                                    @RequestBody(required = false) UpdateEventDefinitionRequest body,
                                    HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                validateUpdateShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean target = sedDao.findByOidAndStudy(sedOid, study.getId(), 0);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + sedOid + "' in study '" + studyOid + "'"));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Event definition '" + sedOid + "' is removed — restore before editing"));
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        if (body.name() != null) {
            String oldVal = target.getName();
            String newVal = body.name().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setName(newVal);
                writeEventDefFieldAudit(auditDAO, me, study, target, "name", oldVal, newVal);
            }
        }
        if (body.description() != null) {
            String oldVal = target.getDescription();
            String newVal = body.description().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setDescription(newVal);
                writeEventDefFieldAudit(auditDAO, me, study, target, "description", oldVal, newVal);
            }
        }
        if (body.category() != null) {
            String oldVal = target.getCategory();
            String newVal = body.category().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setCategory(newVal);
                writeEventDefFieldAudit(auditDAO, me, study, target, "category", oldVal, newVal);
            }
        }
        if (body.type() != null) {
            String oldVal = target.getType();
            String newVal = body.type().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setType(newVal);
                writeEventDefFieldAudit(auditDAO, me, study, target, "type", oldVal, newVal);
            }
        }
        if (body.repeating() != null) {
            boolean oldVal = target.isRepeating();
            boolean newVal = body.repeating();
            if (oldVal != newVal) {
                target.setRepeating(newVal);
                writeEventDefFieldAudit(auditDAO, me, study, target, "repeating",
                        Boolean.toString(oldVal), Boolean.toString(newVal));
            }
        }

        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        sedDao.update(target);

        LOG.info("Update event definition: oid={} study={} by admin={}",
                sedOid, studyOid, me.getName());

        return ResponseEntity.ok(toDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* POST /reorder                                                     */
    /* ----------------------------------------------------------------- */

    @PostMapping("/reorder")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = EventDefinitionDto.class)))
    public ResponseEntity<?> reorder(@PathVariable("studyOid") String studyOid,
                                     @RequestBody(required = false) ReorderEventDefinitionsRequest body,
                                     HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;
        if (body == null || body.orderedOids() == null || body.orderedOids().isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "orderedOids", "orderedOids must be a non-empty array"))));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        ArrayList<StudyEventDefinitionBean> existing = sedDao.findAllByStudy(study);

        Set<String> activeOids = new HashSet<>();
        for (StudyEventDefinitionBean sed : existing) {
            if (sed.getStatus() != null && !sed.getStatus().isDeleted()) {
                activeOids.add(sed.getOid());
            }
        }
        Set<String> submitted = new HashSet<>(body.orderedOids());
        if (submitted.size() != body.orderedOids().size()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "orderedOids must not contain duplicates"));
        }
        if (!submitted.equals(activeOids)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "orderedOids must contain exactly the OIDs of the study's currently-active event definitions"));
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        int ordinal = 1;
        for (String oid : body.orderedOids()) {
            StudyEventDefinitionBean target = sedDao.findByOidAndStudy(oid, study.getId(), 0);
            if (target == null || target.getId() == 0) continue;
            int oldOrdinal = target.getOrdinal();
            if (oldOrdinal != ordinal) {
                target.setOrdinal(ordinal);
                target.setUpdater(me);
                target.setUpdatedDate(new java.util.Date());
                sedDao.update(target);
                writeEventDefFieldAudit(auditDAO, me, study, target, "ordinal",
                        String.valueOf(oldOrdinal), String.valueOf(ordinal));
            }
            ordinal++;
        }

        LOG.info("Reorder event definitions: study={} by admin={} ({} rows)",
                studyOid, me.getName(), body.orderedOids().size());

        // Return the refreshed list so the SPA can replace its in-memory state.
        return list(studyOid, session);
    }

    /* ----------------------------------------------------------------- */
    /* POST /{sedOid}/disable                                            */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{sedOid}/disable")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventDefinitionDto.class)))
    public ResponseEntity<?> disable(@PathVariable("studyOid") String studyOid,
                                     @PathVariable("sedOid") String sedOid,
                                     HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean target = sedDao.findByOidAndStudy(sedOid, study.getId(), 0);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + sedOid + "' in study '" + studyOid + "'"));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Event definition '" + sedOid + "' is already removed"));
        }

        Status oldStatus = target.getStatus();
        target.setStatus(Status.DELETED);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        sedDao.update(target);

        // Single lifecycle row capturing the status flip. Downstream
        // event_crf / item_data cascade is owned by the existing DB
        // trigger pattern; A8.3 will revisit if assignment cleanup is
        // needed at controller level.
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("study_event_definition");
            ae.setEntityId(target.getId());
            ae.setColumnName("status_id");
            ae.setOldValue(oldStatus == null ? "" : String.valueOf(oldStatus.getId()));
            ae.setNewValue(String.valueOf(Status.DELETED.getId()));
            ae.setActionMessage("study_event_definition_disable: " + sedOid
                    + " (" + (oldStatus == null ? "?" : oldStatus.getName())
                    + " → " + Status.DELETED.getName() + ") by " + me.getName());
            new AuditEventDAO(dataSource).create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for study_event_definition_disable oid={} (continuing): {}",
                    sedOid, e.getMessage());
        }

        LOG.info("Disable event definition: oid={} study={} by admin={}",
                sedOid, studyOid, me.getName());

        return ResponseEntity.ok(toDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* GET /{sedOid}/crfs — list CRF assignments                          */
    /* POST /{sedOid}/crfs — attach CRF to event definition               */
    /* PUT /{sedOid}/crfs/{crfOid} — update assignment options            */
    /* DELETE /{sedOid}/crfs/{crfOid} — remove assignment (status DELETED)*/
    /*   (Phase E A8.3 — event-CRF assignment surface)                    */
    /* ----------------------------------------------------------------- */

    @GetMapping("/{sedOid}/crfs")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                         implementation = EventCrfAssignmentDto.class)))
    public ResponseEntity<?> listAssignments(@PathVariable("studyOid") String studyOid,
                                             @PathVariable("sedOid") String sedOid,
                                             HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ false);
        if (guard != null) return guard;

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean sed = sedDao.findByOidAndStudy(sedOid, study.getId(), 0);
        if (sed == null || sed.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + sedOid + "' in study '" + studyOid + "'"));
        }

        EventDefinitionCRFDAO assignmentDao = new EventDefinitionCRFDAO(dataSource);
        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        ArrayList<EventDefinitionCRFBean> beans = assignmentDao.findAllByDefinition(sed.getId());
        List<EventCrfAssignmentDto> out = new ArrayList<>(beans.size());
        for (EventDefinitionCRFBean a : beans) {
            out.add(toAssignmentDto(a, crfDao, versionDao));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{sedOid}/crfs")
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = EventCrfAssignmentDto.class)))
    public ResponseEntity<?> attachCrf(@PathVariable("studyOid") String studyOid,
                                       @PathVariable("sedOid") String sedOid,
                                       @RequestBody(required = false) EventCrfAssignmentRequest body,
                                       HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        // Shape validation: crfOid + defaultVersionOid both required;
        // SDV (if present) must be a known enum value.
        List<SubjectsApiController.ValidationErrorBody.FieldError> errors = new ArrayList<>();
        if (body.crfOid() == null || body.crfOid().isBlank())
            errors.add(fieldError("crfOid", "crfOid is required"));
        if (body.defaultVersionOid() == null || body.defaultVersionOid().isBlank())
            errors.add(fieldError("defaultVersionOid", "defaultVersionOid is required"));
        SourceDataVerification sdv = resolveSdv(body.sourceDataVerification(), errors);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean sed = sedDao.findByOidAndStudy(sedOid, study.getId(), 0);
        if (sed == null || sed.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + sedOid + "' in study '" + studyOid + "'"));
        }
        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(body.crfOid());
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + body.crfOid() + "'"));
        }
        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean defaultVersion = versionDao.findByOid(body.defaultVersionOid());
        if (defaultVersion == null || defaultVersion.getId() == 0
                || defaultVersion.getCrfId() != crf.getId()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version '" + body.defaultVersionOid()
                            + "' belonging to CRF '" + body.crfOid() + "'"));
        }

        EventDefinitionCRFDAO assignmentDao = new EventDefinitionCRFDAO(dataSource);
        EventDefinitionCRFBean dup = assignmentDao
                .findByStudyEventDefinitionIdAndCRFId(sed.getId(), crf.getId());
        if (dup != null && dup.getId() != 0
                && dup.getStatus() != null && !dup.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF '" + body.crfOid() + "' is already attached to event '" + sedOid + "'"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        EventDefinitionCRFBean toCreate = new EventDefinitionCRFBean();
        toCreate.setStudyEventDefinitionId(sed.getId());
        toCreate.setStudyId(study.getId());
        toCreate.setCrfId(crf.getId());
        toCreate.setDefaultVersionId(defaultVersion.getId());
        applyAssignmentFlags(toCreate, body, sdv);
        toCreate.setStatus(Status.AVAILABLE);
        toCreate.setOwner(me);
        toCreate.setCreatedDate(new java.util.Date());

        EventDefinitionCRFBean persisted = assignmentDao.create(toCreate);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("EventDefinitionCRFDAO.create returned no row for sed={} crf={}",
                    sedOid, body.crfOid());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist event-CRF assignment"));
        }

        LOG.info("Attach CRF: study={} event={} crf={} defaultVersion={} by user={}",
                studyOid, sedOid, body.crfOid(), body.defaultVersionOid(), me.getName());

        return ResponseEntity.status(201).body(toAssignmentDto(persisted, crfDao, versionDao));
    }

    @PutMapping("/{sedOid}/crfs/{crfOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfAssignmentDto.class)))
    public ResponseEntity<?> updateAssignment(@PathVariable("studyOid") String studyOid,
                                              @PathVariable("sedOid") String sedOid,
                                              @PathVariable("crfOid") String crfOid,
                                              @RequestBody(required = false) EventCrfAssignmentRequest body,
                                              HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<SubjectsApiController.ValidationErrorBody.FieldError> errors = new ArrayList<>();
        SourceDataVerification sdv = resolveSdv(body.sourceDataVerification(), errors);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean sed = sedDao.findByOidAndStudy(sedOid, study.getId(), 0);
        if (sed == null || sed.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + sedOid + "' in study '" + studyOid + "'"));
        }
        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(crfOid);
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }
        EventDefinitionCRFDAO assignmentDao = new EventDefinitionCRFDAO(dataSource);
        EventDefinitionCRFBean target = assignmentDao
                .findByStudyEventDefinitionIdAndCRFId(sed.getId(), crf.getId());
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "CRF '" + crfOid + "' is not attached to event '" + sedOid + "'"));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Assignment is removed — re-attach the CRF instead of editing"));
        }

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        if (body.defaultVersionOid() != null && !body.defaultVersionOid().isBlank()) {
            CRFVersionBean dv = versionDao.findByOid(body.defaultVersionOid());
            if (dv == null || dv.getId() == 0 || dv.getCrfId() != crf.getId()) {
                return ResponseEntity.status(404).body(Map.of("message",
                        "No CRF version '" + body.defaultVersionOid()
                                + "' belonging to CRF '" + crfOid + "'"));
            }
            target.setDefaultVersionId(dv.getId());
        }
        applyAssignmentFlags(target, body, sdv);

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        assignmentDao.update(target);

        LOG.info("Update CRF assignment: study={} event={} crf={} by user={}",
                studyOid, sedOid, crfOid, me.getName());

        return ResponseEntity.ok(toAssignmentDto(target, crfDao, versionDao));
    }

    @DeleteMapping("/{sedOid}/crfs/{crfOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfAssignmentDto.class)))
    public ResponseEntity<?> removeAssignment(@PathVariable("studyOid") String studyOid,
                                              @PathVariable("sedOid") String sedOid,
                                              @PathVariable("crfOid") String crfOid,
                                              HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean sed = sedDao.findByOidAndStudy(sedOid, study.getId(), 0);
        if (sed == null || sed.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + sedOid + "' in study '" + studyOid + "'"));
        }
        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(crfOid);
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }
        EventDefinitionCRFDAO assignmentDao = new EventDefinitionCRFDAO(dataSource);
        EventDefinitionCRFBean target = assignmentDao
                .findByStudyEventDefinitionIdAndCRFId(sed.getId(), crf.getId());
        if (target == null || target.getId() == 0 || target.getStatus() == null
                || target.getStatus().isDeleted()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No active assignment of CRF '" + crfOid + "' on event '" + sedOid + "'"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        target.setStatus(Status.DELETED);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        assignmentDao.update(target);

        LOG.info("Remove CRF assignment: study={} event={} crf={} by user={}",
                studyOid, sedOid, crfOid, me.getName());

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        return ResponseEntity.ok(toAssignmentDto(target, crfDao, versionDao));
    }

    /**
     * Mutate the assignment bean's flag/string fields from the request.
     * Used by both POST (attach) and PUT (update) — the PUT path only
     * forwards fields the caller passes, but the bean's setters require
     * primitive booleans, so we read the boxed value or fall back to
     * the current bean state.
     */
    private static void applyAssignmentFlags(EventDefinitionCRFBean target,
                                             EventCrfAssignmentRequest body,
                                             SourceDataVerification sdv) {
        if (body.required() != null) target.setRequiredCRF(body.required());
        if (body.doubleEntry() != null) target.setDoubleEntry(body.doubleEntry());
        if (body.decisionCondition() != null) target.setDecisionCondition(body.decisionCondition());
        if (body.electronicSignature() != null) target.setElectronicSignature(body.electronicSignature());
        if (body.hideCrf() != null) target.setHideCrf(body.hideCrf());
        if (sdv != null) target.setSourceDataVerification(sdv);
        if (body.participantForm() != null) target.setParticipantForm(body.participantForm());
        if (body.allowAnonymousSubmission() != null)
            target.setAllowAnonymousSubmission(body.allowAnonymousSubmission());
        if (body.submissionUrl() != null) target.setSubmissionUrl(body.submissionUrl().trim());
        // Note: offline flag is owned by a separate tag service in the
        // legacy code (EventDefinitionCrfTagService) — A8.3 follow-up
        // wires that path; for now we accept the boolean but only
        // forward it if there's a direct setter on the bean.
    }

    /**
     * Resolve the SPA's string SDV value to the legacy enum constant.
     * Adds a validation error to {@code errors} on unknown values.
     * Returns {@code null} when the body didn't include the field —
     * the caller treats that as "leave unchanged" on PUT.
     */
    private static SourceDataVerification resolveSdv(String raw,
            List<SubjectsApiController.ValidationErrorBody.FieldError> errors) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return SourceDataVerification.valueOf(raw);
        } catch (IllegalArgumentException iae) {
            errors.add(fieldError("sourceDataVerification",
                    "sourceDataVerification must be one of AllREQUIRED / PARTIALREQUIRED / NOTREQUIRED / NOTAPPLICABLE"));
            return null;
        }
    }

    private static EventCrfAssignmentDto toAssignmentDto(EventDefinitionCRFBean a,
                                                         CRFDAO crfDao,
                                                         CRFVersionDAO versionDao) {
        CRFBean crf = crfDao.findByPK(a.getCrfId());
        CRFVersionBean dv = versionDao.findByPK(a.getDefaultVersionId());
        return new EventCrfAssignmentDto(
                crf == null ? null : crf.getOid(),
                crf == null ? null : nullToEmpty(crf.getName()),
                dv == null ? null : dv.getOid(),
                dv == null ? null : nullToEmpty(dv.getName()),
                a.isRequiredCRF(),
                a.isDoubleEntry(),
                a.isDecisionCondition(),
                a.isElectronicSignature(),
                a.isHideCrf(),
                a.getSourceDataVerification() == null
                        ? "NOTAPPLICABLE" : a.getSourceDataVerification().name(),
                a.isParticipantForm(),
                a.isAllowAnonymousSubmission(),
                nullToEmpty(a.getSubmissionUrl()),
                /* offline */ false,
                a.getStatus() == null ? "" : a.getStatus().getName());
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /**
     * Shared preflight: 401 unauthenticated · 404 unknown study · 409
     * study is a site (top-level only) · 403 caller may not edit ·
     * 409 study not accepting writes (LOCKED / FROZEN / DELETED).
     *
     * <p>The {@code mutating} flag toggles the role check: read paths
     * (GET) pass for any session-authenticated user; write paths
     * require the A8.1 edit gate.
     */
    private ResponseEntity<?> preflight(HttpSession session, String studyOid, boolean mutating) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (studyOid == null || studyOid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "studyOid path variable is required"));
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        if (study == null || study.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        if (study.getParentStudyId() > 0) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Event definitions may only be managed at the top-level study (got a site)"));
        }
        if (mutating) {
            StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
            if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
                return ResponseEntity.status(403).body(Map.of("message",
                        "Your role does not permit managing event definitions on this study"));
            }
            if (!StudyAdminAuthorization.studyAcceptsWrites(study)) {
                return ResponseEntity.status(409).body(Map.of("message",
                        "Study is " + study.getStatus().getName().toLowerCase()
                                + " — writes are refused until it is unlocked"));
            }
        }
        return null;
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateCreateShape(
            CreateEventDefinitionRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) out.add(fieldError("name", "Event name is required"));
        else if (name.length() > 2000) out.add(fieldError("name", "Event name must be 2000 characters or fewer"));

        String type = body.type() == null ? "" : body.type().trim();
        if (type.isEmpty()) out.add(fieldError("type", "Event type is required"));
        else if (!LEGAL_TYPES.contains(type))
            out.add(fieldError("type", "Event type must be one of scheduled / unscheduled / common"));

        if (body.description() != null && body.description().length() > 2000)
            out.add(fieldError("description", "Description must be 2000 characters or fewer"));
        if (body.category() != null && body.category().length() > 2000)
            out.add(fieldError("category", "Category must be 2000 characters or fewer"));
        return out;
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateUpdateShape(
            UpdateEventDefinitionRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
        if (body.name() != null) {
            String s = body.name().trim();
            if (s.isEmpty()) out.add(fieldError("name", "Event name cannot be blank"));
            else if (s.length() > 2000) out.add(fieldError("name", "Event name must be 2000 characters or fewer"));
        }
        if (body.type() != null) {
            String s = body.type().trim();
            if (s.isEmpty()) out.add(fieldError("type", "Event type cannot be blank"));
            else if (!LEGAL_TYPES.contains(s))
                out.add(fieldError("type", "Event type must be one of scheduled / unscheduled / common"));
        }
        if (body.description() != null && body.description().length() > 2000)
            out.add(fieldError("description", "Description must be 2000 characters or fewer"));
        if (body.category() != null && body.category().length() > 2000)
            out.add(fieldError("category", "Category must be 2000 characters or fewer"));
        return out;
    }

    private static SubjectsApiController.ValidationErrorBody.FieldError fieldError(String field, String msg) {
        return new SubjectsApiController.ValidationErrorBody.FieldError(field, msg);
    }

    private void writeEventDefFieldAudit(AuditEventDAO auditDAO,
                                         UserAccountBean editor,
                                         StudyBean study,
                                         StudyEventDefinitionBean target,
                                         String columnName,
                                         String oldValue,
                                         String newValue) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(editor.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("study_event_definition");
            ae.setEntityId(target.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            ae.setActionMessage("event_def_update: " + (target.getOid() == null ? "?" : target.getOid())
                    + "." + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "'");
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for event_def field {}={} (continuing): {}",
                    columnName, newValue, e.getMessage());
        }
    }

    private static EventDefinitionDto toDto(StudyEventDefinitionBean sed) {
        return new EventDefinitionDto(
                sed.getOid(),
                nullToEmpty(sed.getName()),
                nullToEmpty(sed.getDescription()),
                nullToEmpty(sed.getCategory()),
                nullToEmpty(sed.getType()),
                sed.isRepeating(),
                sed.getOrdinal(),
                sed.getStatus() == null ? "" : sed.getStatus().getName());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
