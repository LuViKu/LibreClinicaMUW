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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectGroupMapBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectGroupMapDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase E.6 subject-lifecycle — service that reconciles the SPA's
 * "desired final state" group-assignment list against the existing
 * {@code subject_group_map} rows for a study subject.
 *
 * <p><strong>Audit decision (reviewer flag):</strong> the legacy
 * {@code subject_group_map} table has had DB-level audit triggers
 * since migration 3.2 — every status flip and insert is recorded by
 * the trigger into {@code audit_log_event}. The legacy
 * {@code RemoveSubjectGroupMapServlet} ALSO emits an audit row from
 * Java. We follow the Phase E.6 audit-of-record convention: rely on
 * the DB trigger, do NOT emit a second Java-side audit row. The SPA
 * surfaces audit history from the trigger-owned table; double-audit
 * would split history across two write-paths and confuse the
 * reviewer-facing audit drilldown.
 *
 * <p>Reconciliation algorithm:
 * <ol>
 *   <li>Load the existing ACTIVE {@code subject_group_map} rows.</li>
 *   <li>For each desired assignment:
 *     <ul>
 *       <li>If the (groupClassId, groupId) pair already exists active,
 *           keep it untouched.</li>
 *       <li>If the same groupClassId exists with a different groupId,
 *           soft-delete the old row + insert a new row (no
 *           UPDATE-in-place — the legacy code never did this either,
 *           because audit trail readability suffers).</li>
 *       <li>Otherwise insert a new row.</li>
 *     </ul>
 *   </li>
 *   <li>Any existing active rows whose groupClassId is not in the
 *       desired set get soft-deleted (status_id → 5 / DELETED).</li>
 * </ol>
 *
 * <p>REQUIRED group classes (per the parent class's
 * {@code subject_assignment} field) must be present in the desired
 * list with a concrete {@code groupId}; missing or null-groupId is
 * a 400 from the caller. OPTIONAL classes may carry null
 * {@code groupId} for the "not picked" branch.
 *
 * <p>Validation errors are reported as a list of
 * {@link ValidationErrorBody.FieldError} so the
 * caller can short-circuit with a 400 without persisting anything.
 */
final class SubjectGroupAssignmentService {

    private static final Logger LOG = LoggerFactory.getLogger(SubjectGroupAssignmentService.class);

    private final DataSource dataSource;

    SubjectGroupAssignmentService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /**
     * Validate the desired assignments against the study's group-class
     * surface. Returns a non-empty list of field errors when any rule
     * is broken; an empty list means the caller can proceed.
     *
     * <p>Rules:
     * <ul>
     *   <li>Every {@code groupClassId} must belong to the given study
     *       AND be currently AVAILABLE.</li>
     *   <li>If a {@code groupId} is supplied, it must belong to the
     *       referenced class AND be currently AVAILABLE.</li>
     *   <li>REQUIRED classes must carry a non-null {@code groupId}.</li>
     *   <li>Every REQUIRED class active on the study must be present
     *       in the desired list (otherwise the call would leave the
     *       subject in an under-assigned state).</li>
     *   <li>No duplicate {@code groupClassId} entries.</li>
     * </ul>
     */
    List<ValidationErrorBody.FieldError> validate(
            StudyBean study,
            List<UpdateSubjectGroupsRequest.Assignment> assignments) {
        List<ValidationErrorBody.FieldError> errors = new ArrayList<>();
        if (study == null || study.getId() == 0) {
            errors.add(new ValidationErrorBody.FieldError(
                    "study", "No active study bound"));
            return errors;
        }
        List<UpdateSubjectGroupsRequest.Assignment> desired = assignments == null
                ? Collections.emptyList()
                : assignments;

        // Duplicate-class check.
        Set<Integer> seenClassIds = new HashSet<>();
        for (UpdateSubjectGroupsRequest.Assignment a : desired) {
            if (a == null) continue;
            if (!seenClassIds.add(a.groupClassId())) {
                errors.add(new ValidationErrorBody.FieldError(
                        "assignments[" + a.groupClassId() + "]",
                        "Duplicate group class in the request"));
            }
        }

        StudyGroupClassDAO sgcDao = new StudyGroupClassDAO(dataSource);
        StudyGroupDAO sgDao = new StudyGroupDAO(dataSource);
        ArrayList<StudyGroupClassBean> all = sgcDao.findAllByStudy(study);
        Map<Integer, StudyGroupClassBean> classById = new HashMap<>();
        for (StudyGroupClassBean gc : all) classById.put(gc.getId(), gc);

        for (UpdateSubjectGroupsRequest.Assignment a : desired) {
            if (a == null) continue;
            StudyGroupClassBean gc = classById.get(a.groupClassId());
            if (gc == null) {
                errors.add(new ValidationErrorBody.FieldError(
                        "assignments[" + a.groupClassId() + "].groupClassId",
                        "No active group class with id " + a.groupClassId() + " in this study"));
                continue;
            }
            if (gc.getStatus() == null || !Status.AVAILABLE.equals(gc.getStatus())) {
                errors.add(new ValidationErrorBody.FieldError(
                        "assignments[" + a.groupClassId() + "].groupClassId",
                        "Group class " + a.groupClassId() + " is not available"));
                continue;
            }
            boolean required = "REQUIRED".equalsIgnoreCase(gc.getSubjectAssignment());
            if (required && a.groupId() == null) {
                errors.add(new ValidationErrorBody.FieldError(
                        "assignments[" + a.groupClassId() + "].groupId",
                        "Group class '" + gc.getName() + "' is REQUIRED — pick a group"));
            }
            if (a.groupId() != null) {
                StudyGroupBean sg = sgDao.findByPK(a.groupId().intValue());
                if (sg == null || sg.getId() == 0
                        || sg.getStudyGroupClassId() != gc.getId()
                        || sg.getStatus() == null
                        || !Status.AVAILABLE.equals(sg.getStatus())) {
                    errors.add(new ValidationErrorBody.FieldError(
                            "assignments[" + a.groupClassId() + "].groupId",
                            "Group " + a.groupId() + " not found in class " + gc.getId()));
                }
            }
        }

        // Every REQUIRED class on the study must be covered.
        for (StudyGroupClassBean gc : all) {
            if (gc.getStatus() == null || !Status.AVAILABLE.equals(gc.getStatus())) continue;
            if (!"REQUIRED".equalsIgnoreCase(gc.getSubjectAssignment())) continue;
            boolean present = false;
            for (UpdateSubjectGroupsRequest.Assignment a : desired) {
                if (a != null && a.groupClassId() == gc.getId()) { present = true; break; }
            }
            if (!present) {
                errors.add(new ValidationErrorBody.FieldError(
                        "assignments[" + gc.getId() + "]",
                        "REQUIRED group class '" + gc.getName() + "' must be assigned"));
            }
        }

        return errors;
    }

    /**
     * Apply the validated reconciliation to {@code subject_group_map}.
     * Returns the number of rows touched (inserts + soft-deletes).
     */
    int reconcile(int studySubjectId,
                  UserAccountBean actor,
                  List<UpdateSubjectGroupsRequest.Assignment> desired,
                  StudyGroupClassDAO sgcDao) {
        Objects.requireNonNull(actor, "actor");
        SubjectGroupMapDAO sgmDao = new SubjectGroupMapDAO(dataSource);
        ArrayList<SubjectGroupMapBean> existing = sgmDao.findAllByStudySubject(studySubjectId);
        if (existing == null) existing = new ArrayList<>();

        // Active existing rows keyed by classId.
        Map<Integer, SubjectGroupMapBean> activeByClass = new HashMap<>();
        for (SubjectGroupMapBean sgm : existing) {
            if (sgm.getStatus() != null && Status.AVAILABLE.equals(sgm.getStatus())) {
                activeByClass.put(sgm.getStudyGroupClassId(), sgm);
            }
        }

        int touched = 0;
        Set<Integer> desiredClassIds = new HashSet<>();
        List<UpdateSubjectGroupsRequest.Assignment> safeDesired = desired == null
                ? Collections.emptyList()
                : desired;
        for (UpdateSubjectGroupsRequest.Assignment a : safeDesired) {
            if (a == null) continue;
            desiredClassIds.add(a.groupClassId());
            SubjectGroupMapBean existingRow = activeByClass.get(a.groupClassId());
            int targetGroupId = a.groupId() == null ? 0 : a.groupId().intValue();
            if (existingRow != null && existingRow.getStudyGroupId() == targetGroupId) {
                continue;  // Already in the desired state — no-op.
            }
            if (existingRow != null) {
                softDelete(existingRow, sgmDao, actor);
                touched++;
            }
            // Insert the new row (concrete or null group).
            SubjectGroupMapBean fresh = new SubjectGroupMapBean();
            fresh.setStudySubjectId(studySubjectId);
            fresh.setStudyGroupClassId(a.groupClassId());
            // SubjectGroupMapDAO#create writes through getStudyGroupId(); zero
            // is the legacy sentinel for "no concrete group picked", which
            // we use here for the OPTIONAL not-now branch. The DAO column
            // accepts NULL — see the inline insert path below for the
            // null-friendly variant.
            fresh.setStudyGroupId(targetGroupId);
            fresh.setStatus(Status.AVAILABLE);
            fresh.setOwner(actor);
            fresh.setCreatedDate(new Date());
            fresh.setNotes("");
            if (targetGroupId == 0) {
                insertNullGroup(fresh, actor);
            } else {
                sgmDao.create(fresh);
            }
            touched++;
        }

        // Soft-delete every existing-active row whose class is no longer
        // in the desired set.
        for (Map.Entry<Integer, SubjectGroupMapBean> e : activeByClass.entrySet()) {
            if (!desiredClassIds.contains(e.getKey())) {
                softDelete(e.getValue(), sgmDao, actor);
                touched++;
            }
        }

        return touched;
    }

    private void softDelete(SubjectGroupMapBean row, SubjectGroupMapDAO sgmDao, UserAccountBean actor) {
        row.setStatus(Status.DELETED);
        row.setUpdater(actor);
        row.setUpdatedDate(new Date());
        sgmDao.update(row);
    }

    /**
     * Insert a {@code subject_group_map} row with a NULL
     * {@code study_group_id}. The legacy DAO's create() writes zero
     * instead of null for the missing group id; for OPTIONAL not-now
     * rows we want a true NULL so the LEFT JOIN in the matrix-side
     * batch query returns the expected row.
     */
    private void insertNullGroup(SubjectGroupMapBean row, UserAccountBean actor) {
        String sql = "INSERT INTO subject_group_map "
                + "(study_group_class_id, study_subject_id, study_group_id, "
                + " status_id, owner_id, date_created, notes) "
                + "VALUES (?, ?, NULL, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, row.getStudyGroupClassId());
            ps.setInt(2, row.getStudySubjectId());
            ps.setInt(3, row.getStatus().getId());
            ps.setInt(4, actor.getId());
            ps.setTimestamp(5, new java.sql.Timestamp(new Date().getTime()));
            ps.setString(6, row.getNotes() == null ? "" : row.getNotes());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Failed to insert OPTIONAL not-now subject_group_map row for ss={} class={}",
                    row.getStudySubjectId(), row.getStudyGroupClassId(), e);
            throw new RuntimeException("subject_group_map insert failed", e);
        }
    }
}
