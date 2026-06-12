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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
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
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<ValidationErrorBody.FieldError> errors =
                validateCreateShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
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
                return ResponseEntity.badRequest().body(new ValidationErrorBody(
                        "Validation failed",
                        List.of(new ValidationErrorBody.FieldError(
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
        // Phase E.6 (2026-06-05): the legacy DAO writes the
        // randomized OID into the oc_oid column inside the INSERT but
        // never calls setOid() on the bean it returns, so persisted.
        // getOid() is null and the SPA gets oid:null in the DTO —
        // breaking any subsequent attach-CRF / disable / update call
        // that uses the OID in the URL. Re-hydrate from the DB so the
        // DTO carries the actual OID.
        StudyEventDefinitionBean rehydrated =
                (StudyEventDefinitionBean) sedDao.findByPK(persisted.getId());
        if (rehydrated != null && rehydrated.getId() != 0) {
            persisted = rehydrated;
        }

        writeEventDefCreatedAudit(me, persisted);

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
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<ValidationErrorBody.FieldError> errors =
                validateUpdateShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
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

        if (body.name() != null) {
            String oldVal = target.getName();
            String newVal = body.name().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setName(newVal);
                writeEventDefFieldAudit(AuditTypeIds.EVENT_DEFINITION_FIELD_UPDATED, me, target, "name", oldVal, newVal);
            }
        }
        if (body.description() != null) {
            String oldVal = target.getDescription();
            String newVal = body.description().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setDescription(newVal);
                writeEventDefFieldAudit(AuditTypeIds.EVENT_DEFINITION_FIELD_UPDATED, me, target, "description", oldVal, newVal);
            }
        }
        if (body.category() != null) {
            String oldVal = target.getCategory();
            String newVal = body.category().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setCategory(newVal);
                writeEventDefFieldAudit(AuditTypeIds.EVENT_DEFINITION_FIELD_UPDATED, me, target, "category", oldVal, newVal);
            }
        }
        if (body.type() != null) {
            String oldVal = target.getType();
            String newVal = body.type().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setType(newVal);
                writeEventDefFieldAudit(AuditTypeIds.EVENT_DEFINITION_FIELD_UPDATED, me, target, "type", oldVal, newVal);
            }
        }
        if (body.repeating() != null) {
            boolean oldVal = target.isRepeating();
            boolean newVal = body.repeating();
            if (oldVal != newVal) {
                target.setRepeating(newVal);
                writeEventDefFieldAudit(AuditTypeIds.EVENT_DEFINITION_FIELD_UPDATED, me, target, "repeating",
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
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
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
                writeEventDefFieldAudit(AuditTypeIds.EVENT_DEFINITION_FIELD_UPDATED, me, target, "ordinal",
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
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study_event_definition', ?, ?, ?, ?)")) {
            ps.setInt(1, AuditTypeIds.EVENT_DEFINITION_DISABLED);
            ps.setInt(2, me.getId());
            ps.setInt(3, target.getId());
            ps.setString(4, sedOid == null ? "" : sedOid);
            ps.setString(5, oldStatus == null ? "" : oldStatus.getName());
            ps.setString(6, Status.DELETED.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Audit write failed for study_event_definition_disable oid={} (continuing): {}",
                    sedOid, e.getMessage());
        }

        LOG.info("Disable event definition: oid={} study={} by admin={}",
                sedOid, studyOid, me.getName());

        return ResponseEntity.ok(toDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* POST /{sedOid}/restore                                            */
    /*   Phase E.6 — restore a removed (DELETED) event definition and    */
    /*   cascade-restore AUTO_DELETED child rows. Mirrors the legacy     */
    /*   RestoreEventDefinitionServlet (managestudy package).            */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{sedOid}/restore")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventDefinitionDto.class)))
    public ResponseEntity<?> restore(@PathVariable("studyOid") String studyOid,
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
        // Legacy parity (RestoreEventDefinitionServlet:106): only DELETED
        // (removed) event definitions are restorable. AUTO_DELETED is a
        // cascade-state owned by the parent's removal, not a top-level
        // restorable state.
        if (target.getStatus() == null || target.getStatus() != Status.DELETED) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Event definition '" + sedOid + "' is not in a removed state — cannot restore"));
        }

        Status oldStatus = target.getStatus();
        java.util.Date now = new java.util.Date();
        target.setStatus(Status.AVAILABLE);
        target.setUpdater(me);
        target.setUpdatedDate(now);
        sedDao.update(target);

        // Cascade restore: only AUTO_DELETED children flip back. Rows
        // that were explicitly REMOVED by an operator stay removed —
        // matches RestoreEventDefinitionServlet:130/154/163.
        int restoredEdcCount = 0;
        int restoredEventCount = 0;
        int restoredEventCrfCount = 0;
        int restoredItemDataCount = 0;
        EventDefinitionCRFDAO edcDao = new EventDefinitionCRFDAO(dataSource);
        StudyEventDAO eventDao = new StudyEventDAO(dataSource);
        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);

        ArrayList<EventDefinitionCRFBean> edcs = edcDao.findAllByDefinition(target.getId());
        for (EventDefinitionCRFBean edc : edcs) {
            if (edc.getStatus() != null && edc.getStatus().equals(Status.AUTO_DELETED)) {
                edc.setStatus(Status.AVAILABLE);
                edc.setUpdater(me);
                edc.setUpdatedDate(now);
                edcDao.update(edc);
                restoredEdcCount++;
            }
        }

        ArrayList<StudyEventBean> events = eventDao.findAllByDefinition(target.getId());
        for (StudyEventBean event : events) {
            if (event.getStatus() != null && event.getStatus().equals(Status.AUTO_DELETED)) {
                event.setStatus(Status.AVAILABLE);
                event.setUpdater(me);
                event.setUpdatedDate(now);
                eventDao.update(event);
                restoredEventCount++;

                ArrayList<EventCRFBean> eventCrfs = eventCrfDao.findAllByStudyEvent(event);
                for (EventCRFBean eventCrf : eventCrfs) {
                    if (eventCrf.getStatus() != null && eventCrf.getStatus().equals(Status.AUTO_DELETED)) {
                        eventCrf.setStatus(Status.AVAILABLE);
                        eventCrf.setUpdater(me);
                        eventCrf.setUpdatedDate(now);
                        eventCrfDao.update(eventCrf);
                        restoredEventCrfCount++;

                        ArrayList<ItemDataBean> itemDatas = itemDataDao.findAllByEventCRFId(eventCrf.getId());
                        for (ItemDataBean item : itemDatas) {
                            if (item.getStatus() != null && item.getStatus().equals(Status.AUTO_DELETED)) {
                                item.setStatus(Status.AVAILABLE);
                                item.setUpdater(me);
                                item.setUpdatedDate(now);
                                itemDataDao.update(item);
                                restoredItemDataCount++;
                            }
                        }
                    }
                }
            }
        }

        writeLifecycleAudit(AuditTypeIds.EVENT_DEFINITION_LIFECYCLE_CHANGED, me, study, target,
                oldStatus, Status.AVAILABLE, "study_event_definition_restore");

        LOG.info("Restore event definition: oid={} study={} by admin={} (edc={} ev={} eventCrf={} item={})",
                sedOid, studyOid, me.getName(),
                restoredEdcCount, restoredEventCount, restoredEventCrfCount, restoredItemDataCount);

        return ResponseEntity.ok(toDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* POST /{sedOid}/lock                                               */
    /*   Phase E.6 — lock an event definition (sysadmin only). Cascades  */
    /*   LOCKED into child event_definition_crf + study_event +          */
    /*   event_crf + item_data rows. Mirrors the legacy LOCKED-status    */
    /*   handling in StudyEventDefinitionServlet patterns; LibreClinica  */
    /*   never shipped a dedicated LockStudyEventDefinitionServlet (the  */
    /*   SED-lock action lived inside list-rendering controllers).       */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{sedOid}/lock")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventDefinitionDto.class)))
    public ResponseEntity<?> lock(@PathVariable("studyOid") String studyOid,
                                  @PathVariable("sedOid") String sedOid,
                                  HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (!me.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Only system administrators may lock or unlock event definitions"));
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean target = sedDao.findByOidAndStudy(sedOid, study.getId(), 0);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + sedOid + "' in study '" + studyOid + "'"));
        }
        if (target.getStatus() == null
                || target.getStatus().isDeleted()
                || target.getStatus().isLocked()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Event definition '" + sedOid + "' is in state '"
                            + (target.getStatus() == null ? "?" : target.getStatus().getName())
                            + "' — cannot lock"));
        }

        Status oldStatus = target.getStatus();
        int affected = cascadeStatus(target, Status.LOCKED, me, /* onlyFlipAvailable */ true);

        writeLifecycleAudit(AuditTypeIds.EVENT_DEFINITION_LIFECYCLE_CHANGED, me, study, target,
                oldStatus, Status.LOCKED, "study_event_definition_lock");

        LOG.info("Lock event definition: oid={} study={} by admin={} (child rows touched={})",
                sedOid, studyOid, me.getName(), affected);

        return ResponseEntity.ok(toDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* POST /{sedOid}/unlock                                             */
    /*   Phase E.6 — unlock a LOCKED event definition (sysadmin only).   */
    /*   Mirrors UnlockEventDefinitionServlet's blanket AVAILABLE flip   */
    /*   across all children regardless of prior state.                  */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{sedOid}/unlock")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventDefinitionDto.class)))
    public ResponseEntity<?> unlock(@PathVariable("studyOid") String studyOid,
                                    @PathVariable("sedOid") String sedOid,
                                    HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (!me.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Only system administrators may lock or unlock event definitions"));
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean target = sedDao.findByOidAndStudy(sedOid, study.getId(), 0);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + sedOid + "' in study '" + studyOid + "'"));
        }
        if (target.getStatus() == null || !target.getStatus().isLocked()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Event definition '" + sedOid + "' is not locked — cannot unlock"));
        }

        Status oldStatus = target.getStatus();
        int affected = cascadeStatus(target, Status.AVAILABLE, me, /* onlyFlipAvailable */ false);

        writeLifecycleAudit(AuditTypeIds.EVENT_DEFINITION_LIFECYCLE_CHANGED, me, study, target,
                oldStatus, Status.AVAILABLE, "study_event_definition_unlock");

        LOG.info("Unlock event definition: oid={} study={} by admin={} (child rows touched={})",
                sedOid, studyOid, me.getName(), affected);

        return ResponseEntity.ok(toDto(target));
    }

    /**
     * Apply {@code newStatus} to the event definition itself plus its
     * cascade of {@code event_definition_crf}, {@code study_event},
     * {@code event_crf}, and {@code item_data} children.
     *
     * <p>When {@code onlyFlipAvailable} is true (lock cascade) only
     * AVAILABLE child rows are touched — removed / auto-removed / locked
     * children are left in place to preserve forensic history. When
     * false (unlock cascade) every child row is flipped to AVAILABLE to
     * mirror the legacy {@code UnlockEventDefinitionServlet} blanket
     * restore semantics.
     *
     * @return number of child rows that were updated.
     */
    private int cascadeStatus(StudyEventDefinitionBean target,
                              Status newStatus,
                              UserAccountBean me,
                              boolean onlyFlipAvailable) {
        java.util.Date now = new java.util.Date();
        target.setStatus(newStatus);
        target.setUpdater(me);
        target.setUpdatedDate(now);
        new StudyEventDefinitionDAO(dataSource).update(target);

        int touched = 0;
        EventDefinitionCRFDAO edcDao = new EventDefinitionCRFDAO(dataSource);
        StudyEventDAO eventDao = new StudyEventDAO(dataSource);
        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);

        ArrayList<EventDefinitionCRFBean> edcs = edcDao.findAllByDefinition(target.getId());
        for (EventDefinitionCRFBean edc : edcs) {
            if (!shouldFlip(edc.getStatus(), onlyFlipAvailable)) continue;
            edc.setStatus(newStatus);
            edc.setUpdater(me);
            edc.setUpdatedDate(now);
            edcDao.update(edc);
            touched++;
        }

        ArrayList<StudyEventBean> events = eventDao.findAllByDefinition(target.getId());
        for (StudyEventBean event : events) {
            if (!shouldFlip(event.getStatus(), onlyFlipAvailable)) continue;
            event.setStatus(newStatus);
            event.setUpdater(me);
            event.setUpdatedDate(now);
            eventDao.update(event);
            touched++;

            ArrayList<EventCRFBean> eventCrfs = eventCrfDao.findAllByStudyEvent(event);
            for (EventCRFBean eventCrf : eventCrfs) {
                if (!shouldFlip(eventCrf.getStatus(), onlyFlipAvailable)) continue;
                eventCrf.setStatus(newStatus);
                eventCrf.setUpdater(me);
                eventCrf.setUpdatedDate(now);
                eventCrfDao.update(eventCrf);
                touched++;

                ArrayList<ItemDataBean> itemDatas = itemDataDao.findAllByEventCRFId(eventCrf.getId());
                for (ItemDataBean item : itemDatas) {
                    if (!shouldFlip(item.getStatus(), onlyFlipAvailable)) continue;
                    item.setStatus(newStatus);
                    item.setUpdater(me);
                    item.setUpdatedDate(now);
                    itemDataDao.update(item);
                    touched++;
                }
            }
        }
        return touched;
    }

    private static boolean shouldFlip(Status current, boolean onlyFlipAvailable) {
        if (current == null) return false;
        if (current.isDeleted()) return false; // removed / auto-removed: never touch
        if (onlyFlipAvailable) {
            return current.equals(Status.AVAILABLE);
        }
        return true;
    }

    /**
     * Write a single lifecycle audit row capturing the SED status flip.
     * Per-child rows are intentionally NOT emitted — the legacy servlets
     * write zero child-level audit rows for restore / lock / unlock,
     * and the SPA's audit-log view groups by parent entity. If review
     * demands per-row coverage, lift this to a future revision via the
     * existing {@code writeEventDefFieldAudit} helper.
     *
     * <p>Audit-table unification (slice C, 2026-06-12) — direct INSERT
     * into {@code audit_log_event}. The {@code actionPrefix} argument
     * is no longer persisted (the {@code auditTypeId} encodes the
     * operation type) but is retained in the signature for symmetry
     * with the other lifecycle writers across the unified surface.
     */
    private void writeLifecycleAudit(int auditTypeId,
                                     UserAccountBean editor,
                                     @SuppressWarnings("unused") StudyBean study,
                                     StudyEventDefinitionBean target,
                                     Status oldStatus,
                                     Status newStatus,
                                     @SuppressWarnings("unused") String actionPrefix) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study_event_definition', ?, ?, ?, ?)")) {
            ps.setInt(1, auditTypeId);
            ps.setInt(2, editor.getId());
            ps.setInt(3, target.getId());
            ps.setString(4, target.getOid() == null ? "" : target.getOid());
            ps.setString(5, oldStatus == null ? "" : oldStatus.getName());
            ps.setString(6, newStatus == null ? "" : newStatus.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Audit write failed for event_definition lifecycle oid={} (continuing): {}",
                    target.getOid(), e.getMessage());
        }
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
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        // Shape validation: crfOid + defaultVersionOid both required;
        // SDV (if present) must be a known enum value.
        List<ValidationErrorBody.FieldError> errors = new ArrayList<>();
        if (body.crfOid() == null || body.crfOid().isBlank())
            errors.add(fieldError("crfOid", "crfOid is required"));
        if (body.defaultVersionOid() == null || body.defaultVersionOid().isBlank())
            errors.add(fieldError("defaultVersionOid", "defaultVersionOid is required"));
        SourceDataVerification sdv = resolveSdv(body.sourceDataVerification(), errors);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
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
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<ValidationErrorBody.FieldError> errors = new ArrayList<>();
        SourceDataVerification sdv = resolveSdv(body.sourceDataVerification(), errors);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
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
            List<ValidationErrorBody.FieldError> errors) {
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
            // Phase E.6 multi-role auth (2026-06-12): walk ALL of the
            // caller's active bindings instead of the single session-
            // attribute role. A user with both Investigator (data
            // entry) AND STUDYDIRECTOR (study config) bindings on the
            // same study would otherwise see a 403 whenever the
            // session attribute happened to land on Investigator —
            // non-deterministic per MeApiController#setActiveStudy.
            if (!StudyAdminAuthorization.userMayEditStudy(me, study, dataSource)) {
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

    private static List<ValidationErrorBody.FieldError> validateCreateShape(
            CreateEventDefinitionRequest body) {
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
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

    private static List<ValidationErrorBody.FieldError> validateUpdateShape(
            UpdateEventDefinitionRequest body) {
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
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

    private static ValidationErrorBody.FieldError fieldError(String field, String msg) {
        return new ValidationErrorBody.FieldError(field, msg);
    }

    private static final int AUDIT_TYPE_EVENT_DEFINITION_CREATED = 70;

    /**
     * Direct INSERT into audit_log_event for EventDefinition create —
     * audit_log_event_type_id 70 seeded by
     * lc-muw-2026-06-11-audit-event-types-gap-coverage.xml.
     *
     * <p>Bypasses the legacy AuditEventDAO.create path (writes to
     * audit_event, invisible to the SPA Audit Log view). Edit + disable
     * paths still use the legacy helper — out of scope for this gap
     * closure; see audit-coverage doc for the planned unification.
     */
    private void writeEventDefCreatedAudit(UserAccountBean creator,
                                           StudyEventDefinitionBean persisted) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study_event_definition', ?, ?, '', '')")) {
            ps.setInt(1, AUDIT_TYPE_EVENT_DEFINITION_CREATED);
            ps.setInt(2, creator.getId());
            ps.setInt(3, persisted.getId());
            ps.setString(4, persisted.getName() == null ? "" : persisted.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Audit write failed for study_event_definition {} (continuing): {}",
                    persisted.getId(), e.getMessage());
        }
    }

    /**
     * Direct INSERT into {@code audit_log_event} for an event-definition
     * field change. Audit-table unification (slice C, 2026-06-12) — the
     * legacy {@code AuditEventDAO.create} path wrote to
     * {@code audit_event} (invisible to the SPA Audit Log view); this
     * writer targets the unified surface with type
     * {@link AuditTypeIds#EVENT_DEFINITION_FIELD_UPDATED}.
     */
    private void writeEventDefFieldAudit(int auditTypeId,
                                         UserAccountBean editor,
                                         StudyEventDefinitionBean target,
                                         String columnName,
                                         String oldValue,
                                         String newValue) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study_event_definition', ?, ?, ?, ?)")) {
            ps.setInt(1, auditTypeId);
            ps.setInt(2, editor.getId());
            ps.setInt(3, target.getId());
            ps.setString(4, columnName);
            ps.setString(5, oldValue == null ? "" : oldValue);
            ps.setString(6, newValue == null ? "" : newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
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
