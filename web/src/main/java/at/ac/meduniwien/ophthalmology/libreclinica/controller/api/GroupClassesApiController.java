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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.GroupClassType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupDAO;

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
 * Phase E A8.6 — subject group class CRUD ("Subject groups" task tile).
 *
 * <p>Mirrors {@code CreateSubjectGroupClassServlet} +
 * {@code UpdateSubjectGroupClassServlet} +
 * {@code RemoveSubjectGroupClassServlet} +
 * {@code RestoreSubjectGroupClassServlet} collapsed into REST
 * endpoints. The parent table is {@code study_group_class} (Arm /
 * Family / Demographic / Other taxonomy); each row owns a list of
 * {@code study_group} children that subjects can be classified into.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /studies/{studyOid}/group-classes} — list active +
 *       removed classes with their {@code study_group} children
 *       inlined</li>
 *   <li>{@code POST /…/group-classes} — create class + initial groups</li>
 *   <li>{@code PUT /…/group-classes/{groupClassId}} — edit identity,
 *       optionally replace the child-group set</li>
 *   <li>{@code POST /…/group-classes/{groupClassId}/disable} — soft-
 *       delete the class (the children stay AVAILABLE in the row
 *       but become unreachable when the parent is DELETED)</li>
 *   <li>{@code POST /…/group-classes/{groupClassId}/restore} —
 *       inverse</li>
 * </ul>
 *
 * <p><b>Identifier choice</b>: legacy {@code study_group_class} has
 * no OID column. The SPA references rows by numeric id — the rest of
 * the SPA's surfaces use OIDs, but this table never had one. Adding
 * a synthetic OID at projection time was considered but rejected:
 * it leaks abstraction and parses are fragile. The numeric id
 * convention is explicit and matches the audit table column.
 */
@RestController
@RequestMapping("/api/v1/studies/{studyOid}/group-classes")
@Tag(name = "GroupClasses", description = "Subject group class CRUD (Arms, families, demographic strata).")
public class GroupClassesApiController {

    private static final Logger LOG = LoggerFactory.getLogger(GroupClassesApiController.class);

    private static final Set<String> LEGAL_TYPES = Set.of("Arm", "Family", "Demographic", "Other");
    private static final Set<String> LEGAL_ASSIGNMENT = Set.of("REQUIRED", "OPTIONAL");

    private final DataSource dataSource;

    @Autowired
    public GroupClassesApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ----------------------------------------------------------------- */
    /* GET — list group classes for the study                            */
    /* ----------------------------------------------------------------- */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = GroupClassDto.class)))
    public ResponseEntity<?> list(@PathVariable("studyOid") String studyOid,
                                  HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ false);
        if (guard != null) return guard;

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);

        StudyGroupClassDAO sgcDao = new StudyGroupClassDAO(dataSource);
        StudyGroupDAO sgDao = new StudyGroupDAO(dataSource);
        ArrayList<StudyGroupClassBean> classes = sgcDao.findAllByStudy(study);

        List<GroupClassDto> out = new ArrayList<>(classes.size());
        for (StudyGroupClassBean gc : classes) {
            out.add(toDto(gc, sgDao));
        }
        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* POST — create a new group class with initial children             */
    /* ----------------------------------------------------------------- */

    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = GroupClassDto.class)))
    public ResponseEntity<?> create(@PathVariable("studyOid") String studyOid,
                                    @RequestBody(required = false) CreateGroupClassRequest body,
                                    HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError("body", "missing"))));
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
        StudyGroupClassDAO sgcDao = new StudyGroupClassDAO(dataSource);
        StudyGroupDAO sgDao = new StudyGroupDAO(dataSource);

        // Name uniqueness within the study (active rows only — legacy
        // didn't enforce this but we do; collisions cause real
        // confusion when subjects are classified).
        ArrayList<StudyGroupClassBean> existing = sgcDao.findAllByStudy(study);
        String newName = body.name().trim();
        for (StudyGroupClassBean other : existing) {
            if (other.getStatus() != null && other.getStatus().isDeleted()) continue;
            if (newName.equalsIgnoreCase(other.getName())) {
                return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                        "Validation failed",
                        List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                                "name", "Group class '" + newName + "' already exists in this study"))));
            }
        }

        StudyGroupClassBean toCreate = new StudyGroupClassBean();
        toCreate.setName(newName);
        toCreate.setStudyId(study.getId());
        toCreate.setGroupClassTypeId(resolveGroupClassType(body.groupClassType()).getId());
        toCreate.setSubjectAssignment(body.subjectAssignment().trim());
        toCreate.setStatus(Status.AVAILABLE);
        toCreate.setOwner(me);
        toCreate.setCreatedDate(new java.util.Date());

        StudyGroupClassBean persisted = sgcDao.create(toCreate);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("StudyGroupClassDAO.create returned no row for name={} study={}",
                    body.name(), studyOid);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist group class"));
        }

        // Insert initial child groups.
        if (body.groups() != null) {
            for (CreateGroupClassRequest.GroupInput g : body.groups()) {
                if (g == null || g.name() == null || g.name().isBlank()) continue;
                StudyGroupBean sg = new StudyGroupBean();
                sg.setStudyGroupClassId(persisted.getId());
                sg.setName(g.name().trim());
                sg.setDescription(g.description() == null ? "" : g.description().trim());
                sg.setStatus(Status.AVAILABLE);
                sg.setOwner(me);
                sg.setCreatedDate(new java.util.Date());
                sgDao.create(sg);
            }
        }

        LOG.info("Create group class: id={} name={} study={} by user={}",
                persisted.getId(), persisted.getName(), studyOid, me.getName());

        return ResponseEntity.status(201).body(toDto(persisted, sgDao));
    }

    /* ----------------------------------------------------------------- */
    /* PUT — edit identity + optionally replace child groups             */
    /* ----------------------------------------------------------------- */

    @PutMapping("/{groupClassId}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = GroupClassDto.class)))
    public ResponseEntity<?> update(@PathVariable("studyOid") String studyOid,
                                    @PathVariable("groupClassId") int groupClassId,
                                    @RequestBody(required = false) UpdateGroupClassRequest body,
                                    HttpSession session) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError("body", "missing"))));
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
        StudyGroupClassDAO sgcDao = new StudyGroupClassDAO(dataSource);
        StudyGroupDAO sgDao = new StudyGroupDAO(dataSource);

        StudyGroupClassBean target = resolveGroupClass(sgcDao, study, groupClassId);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No group class with id " + groupClassId + " in study '" + studyOid + "'"));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Group class " + groupClassId + " is removed — restore before editing"));
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        if (body.name() != null) {
            String oldVal = target.getName();
            String newVal = body.name().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setName(newVal);
                writeAudit(auditDAO, me, study, target, "name", oldVal, newVal);
            }
        }
        if (body.groupClassType() != null) {
            int newTypeId = resolveGroupClassType(body.groupClassType()).getId();
            int oldTypeId = target.getGroupClassTypeId();
            if (oldTypeId != newTypeId) {
                target.setGroupClassTypeId(newTypeId);
                writeAudit(auditDAO, me, study, target, "group_class_type_id",
                        String.valueOf(oldTypeId), String.valueOf(newTypeId));
            }
        }
        if (body.subjectAssignment() != null) {
            String oldVal = target.getSubjectAssignment();
            String newVal = body.subjectAssignment().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setSubjectAssignment(newVal);
                writeAudit(auditDAO, me, study, target, "subject_assignment", oldVal, newVal);
            }
        }

        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        sgcDao.update(target);

        // Replace child-group set if the caller passed one. The legacy
        // form lets operators add new groups + edit existing names;
        // we extend that with implicit soft-delete-on-omission (the
        // legacy form has explicit Remove buttons, which the SPA
        // accomplishes by simply omitting the group from the request).
        if (body.groups() != null) {
            reconcileGroups(target.getId(), body.groups(), sgDao, me);
        }

        LOG.info("Update group class: id={} study={} by user={}",
                groupClassId, studyOid, me.getName());

        return ResponseEntity.ok(toDto(target, sgDao));
    }

    /**
     * Reconcile the child-group list:
     * - existing rows matched by id → update name / description (with
     *   audit on each changed field)
     * - new entries (id null or 0) → insert
     * - existing rows absent from the request → soft-delete (status
     *   flip to DELETED)
     */
    private void reconcileGroups(int groupClassId,
                                 List<UpdateGroupClassRequest.GroupEntry> requested,
                                 StudyGroupDAO sgDao,
                                 UserAccountBean me) {
        StudyGroupClassBean parent = new StudyGroupClassBean();
        parent.setId(groupClassId);
        ArrayList<StudyGroupBean> existing = sgDao.findAllByGroupClass(parent);

        Map<Integer, StudyGroupBean> existingById = new HashMap<>();
        for (StudyGroupBean sg : existing) {
            if (sg.getStatus() != null && !sg.getStatus().isDeleted()) {
                existingById.put(sg.getId(), sg);
            }
        }

        Set<Integer> seen = new HashSet<>();
        for (UpdateGroupClassRequest.GroupEntry entry : requested) {
            if (entry == null) continue;
            String name = entry.name() == null ? "" : entry.name().trim();
            if (name.isEmpty()) continue;

            if (entry.id() == null || entry.id() == 0) {
                // Insert.
                StudyGroupBean sg = new StudyGroupBean();
                sg.setStudyGroupClassId(groupClassId);
                sg.setName(name);
                sg.setDescription(entry.description() == null ? "" : entry.description().trim());
                sg.setStatus(Status.AVAILABLE);
                sg.setOwner(me);
                sg.setCreatedDate(new java.util.Date());
                sgDao.create(sg);
            } else {
                StudyGroupBean current = existingById.get(entry.id());
                if (current == null) continue;
                seen.add(entry.id());
                boolean dirty = false;
                if (!java.util.Objects.equals(nullToEmpty(current.getName()), name)) {
                    current.setName(name);
                    dirty = true;
                }
                String newDesc = entry.description() == null ? "" : entry.description().trim();
                if (!java.util.Objects.equals(nullToEmpty(current.getDescription()), newDesc)) {
                    current.setDescription(newDesc);
                    dirty = true;
                }
                if (dirty) {
                    current.setUpdater(me);
                    current.setUpdatedDate(new java.util.Date());
                    sgDao.update(current);
                }
            }
        }

        // Soft-delete rows the caller omitted from the request.
        for (StudyGroupBean current : existingById.values()) {
            if (!seen.contains(current.getId())) {
                current.setStatus(Status.DELETED);
                current.setUpdater(me);
                current.setUpdatedDate(new java.util.Date());
                sgDao.update(current);
            }
        }
    }

    /* ----------------------------------------------------------------- */
    /* POST disable / restore                                            */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{groupClassId}/disable")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = GroupClassDto.class)))
    public ResponseEntity<?> disable(@PathVariable("studyOid") String studyOid,
                                     @PathVariable("groupClassId") int groupClassId,
                                     HttpSession session) {
        return lifecycle(studyOid, groupClassId, session, Status.DELETED, "disable");
    }

    @PostMapping("/{groupClassId}/restore")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = GroupClassDto.class)))
    public ResponseEntity<?> restore(@PathVariable("studyOid") String studyOid,
                                     @PathVariable("groupClassId") int groupClassId,
                                     HttpSession session) {
        return lifecycle(studyOid, groupClassId, session, Status.AVAILABLE, "restore");
    }

    private ResponseEntity<?> lifecycle(String studyOid, int groupClassId, HttpSession session,
                                        Status target, String operation) {
        ResponseEntity<?> guard = preflight(session, studyOid, /* mutating */ true);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        StudyGroupClassDAO sgcDao = new StudyGroupClassDAO(dataSource);
        StudyGroupClassBean gc = resolveGroupClass(sgcDao, study, groupClassId);
        if (gc == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No group class with id " + groupClassId + " in study '" + studyOid + "'"));
        }
        if (gc.getStatus() != null && gc.getStatus().equals(target)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Group class is already " + target.getName().toLowerCase()));
        }
        if (target == Status.AVAILABLE
                && gc.getStatus() != Status.DELETED
                && gc.getStatus() != Status.AUTO_DELETED) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Group class is not disabled — nothing to restore"));
        }

        Status oldStatus = gc.getStatus();
        gc.setStatus(target);
        gc.setUpdater(me);
        gc.setUpdatedDate(new java.util.Date());
        sgcDao.update(gc);

        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("study_group_class");
            ae.setEntityId(gc.getId());
            ae.setColumnName("status_id");
            ae.setOldValue(oldStatus == null ? "" : String.valueOf(oldStatus.getId()));
            ae.setNewValue(String.valueOf(target.getId()));
            ae.setActionMessage("group_class_" + operation + ": id=" + gc.getId()
                    + " name='" + (gc.getName() == null ? "" : gc.getName())
                    + "' (" + (oldStatus == null ? "?" : oldStatus.getName())
                    + " → " + target.getName() + ") by " + me.getName());
            new AuditEventDAO(dataSource).create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for group_class_{} id={} (continuing): {}",
                    operation, gc.getId(), e.getMessage());
        }

        LOG.info("Group class {}: studyOid={} id={} by user={}",
                operation, studyOid, gc.getId(), me.getName());

        StudyGroupDAO sgDao = new StudyGroupDAO(dataSource);
        return ResponseEntity.ok(toDto(gc, sgDao));
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /**
     * Shared 401 / 404 (study) / 409 (study is a site) / 403 / 409
     * (study not accepting writes) preflight.
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
                    "Group classes may only be managed at the top-level study (got a site)"));
        }
        if (mutating) {
            StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
            if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
                return ResponseEntity.status(403).body(Map.of("message",
                        "Your role does not permit managing group classes on this study"));
            }
            if (!StudyAdminAuthorization.studyAcceptsWrites(study)) {
                return ResponseEntity.status(409).body(Map.of("message",
                        "Study is " + study.getStatus().getName().toLowerCase()
                                + " — writes are refused until it is unlocked"));
            }
        }
        return null;
    }

    private static StudyGroupClassBean resolveGroupClass(StudyGroupClassDAO sgcDao,
                                                         StudyBean study,
                                                         int groupClassId) {
        ArrayList<StudyGroupClassBean> all = sgcDao.findAllByStudy(study);
        for (StudyGroupClassBean gc : all) {
            if (gc.getId() == groupClassId) return gc;
        }
        return null;
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateCreateShape(
            CreateGroupClassRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) out.add(fe("name", "Group class name is required"));
        else if (name.length() > 30) out.add(fe("name", "Group class name must be 30 characters or fewer"));

        String type = body.groupClassType() == null ? "" : body.groupClassType().trim();
        if (type.isEmpty()) out.add(fe("groupClassType", "Group class type is required"));
        else if (!LEGAL_TYPES.contains(type))
            out.add(fe("groupClassType", "Type must be one of Arm / Family / Demographic / Other"));

        String assignment = body.subjectAssignment() == null ? "" : body.subjectAssignment().trim();
        if (assignment.isEmpty()) out.add(fe("subjectAssignment", "subjectAssignment is required"));
        else if (!LEGAL_ASSIGNMENT.contains(assignment))
            out.add(fe("subjectAssignment", "subjectAssignment must be REQUIRED or OPTIONAL"));

        return out;
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateUpdateShape(
            UpdateGroupClassRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
        if (body.name() != null) {
            String s = body.name().trim();
            if (s.isEmpty()) out.add(fe("name", "Group class name cannot be blank"));
            else if (s.length() > 30) out.add(fe("name", "Group class name must be 30 characters or fewer"));
        }
        if (body.groupClassType() != null) {
            String s = body.groupClassType().trim();
            if (s.isEmpty()) out.add(fe("groupClassType", "Group class type cannot be blank"));
            else if (!LEGAL_TYPES.contains(s))
                out.add(fe("groupClassType", "Type must be one of Arm / Family / Demographic / Other"));
        }
        if (body.subjectAssignment() != null) {
            String s = body.subjectAssignment().trim();
            if (s.isEmpty()) out.add(fe("subjectAssignment", "subjectAssignment cannot be blank"));
            else if (!LEGAL_ASSIGNMENT.contains(s))
                out.add(fe("subjectAssignment", "subjectAssignment must be REQUIRED or OPTIONAL"));
        }
        return out;
    }

    private static SubjectsApiController.ValidationErrorBody.FieldError fe(String field, String msg) {
        return new SubjectsApiController.ValidationErrorBody.FieldError(field, msg);
    }

    /** Map the SPA's display name back to the legacy {@link GroupClassType} constant. */
    private static GroupClassType resolveGroupClassType(String raw) {
        return switch (raw.trim()) {
            case "Arm"         -> GroupClassType.ARM;
            case "Family"      -> GroupClassType.FAMILY;
            case "Demographic" -> GroupClassType.DEMOGRAPHIC;
            case "Other"       -> GroupClassType.OTHER;
            default -> GroupClassType.OTHER;
        };
    }

    /** Reverse lookup for projection. */
    private static String groupClassTypeName(int id) {
        if (id == GroupClassType.ARM.getId())         return "Arm";
        if (id == GroupClassType.FAMILY.getId())      return "Family";
        if (id == GroupClassType.DEMOGRAPHIC.getId()) return "Demographic";
        if (id == GroupClassType.OTHER.getId())       return "Other";
        return "Other";
    }

    private void writeAudit(AuditEventDAO auditDAO,
                            UserAccountBean me,
                            StudyBean study,
                            StudyGroupClassBean target,
                            String columnName,
                            String oldVal,
                            String newVal) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("study_group_class");
            ae.setEntityId(target.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldVal == null ? "" : oldVal);
            ae.setNewValue(newVal == null ? "" : newVal);
            ae.setActionMessage("group_class_update: id=" + target.getId()
                    + "." + columnName
                    + " '" + (oldVal == null ? "" : oldVal) + "' → '"
                    + (newVal == null ? "" : newVal) + "'");
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for group_class field {}={} (continuing): {}",
                    columnName, newVal, e.getMessage());
        }
    }

    private static GroupClassDto toDto(StudyGroupClassBean gc, StudyGroupDAO sgDao) {
        ArrayList<StudyGroupBean> groups = sgDao.findAllByGroupClass(gc);
        List<GroupClassDto.GroupDto> groupDtos = new ArrayList<>(groups.size());
        for (StudyGroupBean sg : groups) {
            groupDtos.add(new GroupClassDto.GroupDto(
                    sg.getId(),
                    nullToEmpty(sg.getName()),
                    nullToEmpty(sg.getDescription()),
                    sg.getStatus() == null ? "" : sg.getStatus().getName()));
        }
        return new GroupClassDto(
                gc.getId(),
                nullToEmpty(gc.getName()),
                groupClassTypeName(gc.getGroupClassTypeId()),
                nullToEmpty(gc.getSubjectAssignment()),
                gc.getStatus() == null ? "" : gc.getStatus().getName(),
                groupDtos);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
