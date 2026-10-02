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
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Removing an event CRF: the soft removal legacy
 * {@code RemoveEventCRFServlet} performs, for data entered on the wrong
 * subject or visit. The CRF is taken out of the subject's data, not deleted;
 * {@code POST /api/v1/eventCrfs/{id}/restore} ({@link EventCrfsApiController})
 * is the inverse.
 *
 * <p>As in legacy:
 * <ul>
 *   <li>the event CRF becomes removed ({@link Status#DELETED}): removed on
 *       its own, so restoring its visit or subject does not bring it back;</li>
 *   <li>each of its values not removed on its own becomes auto-removed; the
 *       values themselves are kept ({@link ItemDataStatusCascade});</li>
 *   <li>the open discrepancy-note threads on those values are closed, each
 *       with legacy's closing reply.</li>
 * </ul>
 * Beyond legacy, the removal requires a reason and records it in the audit
 * trail ({@link AuditTypeIds#EVENT_CRF_REMOVED}); legacy writes no audit row
 * for it and the {@code event_crf} trigger records none. It closes every
 * open thread on a value, where legacy closes only the last one it finds,
 * and it refuses a signed or locked CRF or visit and a locked subject, as
 * the SPA's other writes to a CRF do.
 *
 * <p>The role check is this controller's own, legacy
 * {@code RemoveEventCRFServlet#mayProceed} ({@link EventCrfRemoveAuthorization}).
 */
@RestController
@RequestMapping("/api/v1/eventCrfs")
@Tag(name = "Event CRFs")
public class EventCrfRemovalApiController {

    private static final Logger LOG = LoggerFactory.getLogger(EventCrfRemovalApiController.class);

    /** The reply legacy {@code RemoveEventCRFServlet} closes each thread on a removed value with. */
    static final String CLOSING_NOTE = "The item has been removed, this Discrepancy Note has been Closed.";

    /** The reason is kept in {@code audit_log_event.reason_for_change}, 1000 characters. */
    static final int REASON_MAX_LENGTH = 1000;

    /**
     * Discrepancy-note audit types, written as {@code DiscrepancyApiController}
     * writes them when a reply changes a thread's status.
     */
    private static final int AUDIT_TYPE_DN_THREAD_APPENDED = 72;
    private static final int AUDIT_TYPE_DN_STATUS_CHANGED = 73;

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public EventCrfRemovalApiController(@Qualifier("dataSource") DataSource dataSource,
                                        SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    /** Body of {@code POST /api/v1/eventCrfs/{id}/remove}. */
    @Schema(name = "RemoveEventCrfRequest")
    public record RemoveEventCrfRequest(
            @Schema(description = "Why the CRF is removed; required, at most 1000 characters.")
            String reason) { }

    /**
     * What removing the CRF would take out, for the confirm dialog. Refused
     * as the removal itself would be, so the dialog can say why before a
     * reason is typed.
     */
    @GetMapping("/{id:[0-9]+}/removal-impact")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfRemovalImpactDto.class)))
    public ResponseEntity<?> removalImpact(@PathVariable("id") int eventCrfId, HttpSession session) {
        Target target = resolve(eventCrfId, session);
        if (target.refusal() != null) return target.refusal();

        // The values remove() marks auto-removed that hold a value, and the
        // open threads it closes on them. Status 5 is removed; resolution
        // statuses 4 and 5 are closed and not applicable.
        String sql = """
                SELECT
                  (SELECT COUNT(*) FROM item_data id
                    WHERE id.event_crf_id = ? AND id.status_id IS DISTINCT FROM 5
                      AND btrim(COALESCE(id.value, '')) <> ''),
                  (SELECT COUNT(DISTINCT dn.discrepancy_note_id) FROM discrepancy_note dn
                     JOIN dn_item_data_map m ON m.discrepancy_note_id = dn.discrepancy_note_id
                     JOIN item_data id ON id.item_data_id = m.item_data_id
                    WHERE id.event_crf_id = ? AND id.status_id IS DISTINCT FROM 5
                      AND COALESCE(dn.parent_dn_id, 0) = 0
                      AND dn.resolution_status_id NOT IN (4, 5))
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            ps.setInt(2, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return ResponseEntity.ok(new EventCrfRemovalImpactDto(rs.getInt(1), rs.getInt(2)));
            }
        } catch (SQLException e) {
            LOG.error("Failed to count the removal impact of event_crf id={}: {}", eventCrfId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to count what removing the CRF would remove; see the server log."));
        }
    }

    /**
     * Removes the CRF. The status changes and the audit row with the reason
     * are one transaction; the threads are closed after it, through the
     * legacy note DAO.
     *
     * <ul>
     *   <li>{@code 204} — removed.</li>
     *   <li>{@code 400} — no reason, or one longer than 1000 characters.</li>
     *   <li>{@code 401}, {@code 400}, {@code 404}, {@code 403}, {@code 409}
     *       — refused as {@link #resolve} says.</li>
     * </ul>
     */
    @PostMapping("/{id:[0-9]+}/remove")
    @ApiResponse(responseCode = "204", description = "The CRF is removed.")
    public ResponseEntity<?> remove(@PathVariable("id") int eventCrfId,
                                    @RequestBody(required = false) RemoveEventCrfRequest body,
                                    HttpSession session) {
        Target target = resolve(eventCrfId, session);
        if (target.refusal() != null) return target.refusal();

        String reason = body == null || body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "A reason is required to remove a CRF."));
        }
        if (reason.length() > REASON_MAX_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "The reason is longer than " + REASON_MAX_LENGTH + " characters."));
        }

        UserAccountBean user = target.user();
        EventCRFBean eventCrf = target.eventCrf();
        List<Integer> valueIds;
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                if (!markRemoved(c, eventCrf.getId(), user.getId())) {
                    c.rollback();
                    return ResponseEntity.status(409).body(Map.of("message",
                            "event_crf " + eventCrfId + " changed meanwhile; reload the visit"));
                }
                valueIds = ItemDataStatusCascade.autoRemove(c, eventCrf.getId(), user.getId());
                writeRemovalAudit(c, user, eventCrf, reason);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            LOG.error("Failed to remove event_crf id={}: {}", eventCrfId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "The CRF could not be removed; nothing was changed. See the server log."));
        }

        int closed = closeOpenThreads(valueIds, user);
        LOG.info("event_crf remove: id={} subject={} values={} threads closed={} by user={}",
                eventCrf.getId(), target.subject().getLabel(), valueIds.size(), closed, user.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * Marks the CRF removed, unless it is removed, locked or signed by now,
     * recording the status it had, so that the restore gives back a
     * completed CRF completed ({@link EventDataStatusCascade#removeEventCrf}).
     * Removed, not auto-removed: it was removed on its own, so restoring its
     * visit or subject, which brings back what was auto-removed with them,
     * leaves it removed. {@code EventCRFDAO.update} is not used: it rewrites
     * every column of the row.
     */
    private static boolean markRemoved(Connection c, int eventCrfId, int userId) throws SQLException {
        return EventDataStatusCascade.removeEventCrf(c, eventCrfId, userId);
    }

    /**
     * The removal's audit row: the CRF's status before and after, as the
     * {@code event_crf} trigger writes a status change (the audit view shows
     * them as "Available" and "Removed"), with the reason and the visit.
     */
    private static void writeRemovalAudit(Connection c, UserAccountBean user, EventCRFBean eventCrf,
                                          String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, "
                        + "entity_id, entity_name, old_value, new_value, reason_for_change, study_event_id) "
                        + "VALUES (?, now(), ?, 'event_crf', ?, 'Status', ?, ?, ?, ?)")) {
            ps.setInt(1, AuditTypeIds.EVENT_CRF_REMOVED);
            ps.setInt(2, user.getId());
            ps.setInt(3, eventCrf.getId());
            ps.setString(4, String.valueOf(eventCrf.getStatus().getId()));
            ps.setString(5, String.valueOf(Status.DELETED.getId()));
            ps.setString(6, reason);
            ps.setInt(7, eventCrf.getStudyEventId());
            ps.executeUpdate();
        }
    }

    /**
     * Closes the open threads on the removed values as legacy does: a
     * closing reply by the remover, mapped to the value, then the thread
     * itself closed. Legacy closes only the last thread it finds on a value;
     * this closes each open one, and leaves a thread already closed or not
     * applicable as it is.
     *
     * @return the number of threads closed
     */
    private int closeOpenThreads(List<Integer> valueIds, UserAccountBean user) {
        DiscrepancyNoteDAO notes = new DiscrepancyNoteDAO(dataSource);
        int closed = 0;
        for (int valueId : valueIds) {
            for (DiscrepancyNoteBean thread : notes.findExistingNotesForItemData(valueId)) {
                if (thread.getParentDnId() != 0) continue;
                int before = thread.getResolutionStatusId();
                if (before == ResolutionStatus.CLOSED.getId()
                        || before == ResolutionStatus.NOT_APPLICABLE.getId()) continue;

                DiscrepancyNoteBean reply = new DiscrepancyNoteBean();
                reply.setParentDnId(thread.getId());
                reply.setDiscrepancyNoteTypeId(thread.getDiscrepancyNoteTypeId());
                reply.setResolutionStatusId(ResolutionStatus.CLOSED.getId());
                reply.setStudyId(thread.getStudyId());
                reply.setAssignedUserId(user.getId());
                reply.setOwner(user);
                reply.setEntityType(DiscrepancyNoteBean.ITEM_DATA);
                reply.setEntityId(valueId);
                reply.setColumn("value");
                reply.setDescription(CLOSING_NOTE);
                notes.create(reply);
                if (!notes.isQuerySuccessful() || reply.getId() == 0) {
                    LOG.warn("Could not close discrepancy note thread {} on removed value {}",
                            thread.getId(), valueId);
                    continue;
                }
                notes.createMapping(reply);
                thread.setResolutionStatusId(ResolutionStatus.CLOSED.getId());
                notes.update(thread);
                writeNoteAudit(AUDIT_TYPE_DN_THREAD_APPENDED, user, thread, "", "");
                writeNoteAudit(AUDIT_TYPE_DN_STATUS_CHANGED, user, thread, spaNoteStatus(before), "closed");
                closed++;
            }
        }
        return closed;
    }

    /**
     * A discrepancy-note audit row as {@code DiscrepancyApiController}
     * writes it. A failure is logged, not raised: the note has changed.
     */
    private void writeNoteAudit(int auditTypeId, UserAccountBean user, DiscrepancyNoteBean thread,
                                String oldValue, String newValue) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'discrepancy_note', ?, ?, ?, ?)")) {
            ps.setInt(1, auditTypeId);
            ps.setInt(2, user.getId());
            ps.setInt(3, thread.getId());
            ps.setString(4, thread.getDescription() == null ? "" : thread.getDescription());
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write audit_log_event row for discrepancy_note id={} type={}: {}",
                    thread.getId(), auditTypeId, e.getMessage());
        }
    }

    /** A resolution status as the SPA names it ({@code DiscrepancyApiController.statusToSpa}). */
    private static String spaNoteStatus(int resolutionStatusId) {
        return switch (resolutionStatusId) {
            case 2 -> "updated";
            case 3 -> "resolution-proposed";
            case 4 -> "closed";
            case 5 -> "not-applicable";
            default -> "new";
        };
    }

    /**
     * The CRF a request names, once everything a removal needs holds;
     * otherwise the refusal. In order:
     * <ol>
     *   <li>{@code 401} — not signed in; {@code 400} — no active study.</li>
     *   <li>{@code 404} — no such event CRF.</li>
     *   <li>{@code 403} — its subject is in a study the caller cannot see.</li>
     *   <li>{@code 403} — the caller's role may not remove a CRF.</li>
     *   <li>{@code 409} — the study is locked or frozen (legacy
     *       {@code checkStudyLocked} and {@code checkStudyFrozen}), or the
     *       subject is locked.</li>
     *   <li>{@code 409} — the CRF is removed already, or signed or locked,
     *       or its visit is signed or locked.</li>
     * </ol>
     */
    private Target resolve(int eventCrfId, HttpSession session) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() == 0) return Target.refused(401, "Not authenticated");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return Target.refused(400, "No active study bound to the session.");
        }

        EventCRFBean eventCrf = new EventCRFDAO(dataSource).findByPK(eventCrfId);
        if (eventCrf == null || eventCrf.getId() == 0) {
            return Target.refused(404, "No event_crf with id " + eventCrfId);
        }
        StudySubjectBean subject = new StudySubjectDAO(dataSource).findByPK(eventCrf.getStudySubjectId());
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(user, currentStudy, role);
        if (subject == null || subject.getId() == 0 || !visible.contains(subject.getStudyId())) {
            return Target.refused(403, "event_crf " + eventCrfId + " belongs to a different study");
        }
        int roleId = role != null && role.getRole() != null ? role.getRole().getId() : 0;
        if (!EventCrfRemoveAuthorization.roleMayRemove(user, roleId)) {
            return Target.refused(403, "Your role does not permit removing a CRF");
        }

        StudyBean study = new StudyDAO(dataSource).findByPK(currentStudy.getId());
        if (!StudyAdminAuthorization.studyAcceptsWrites(study)) {
            return Target.refused(409, "The study is locked or frozen; no CRF can be removed");
        }
        ResponseEntity<?> subjectLocked = SubjectLockGuard.refuseIfLocked(subject, "removing a CRF");
        if (subjectLocked != null) return new Target(subjectLocked, null, null, null);

        Status status = eventCrf.getStatus();
        if (Status.DELETED.equals(status) || Status.AUTO_DELETED.equals(status)) {
            return Target.refused(409, "event_crf " + eventCrfId + " is already removed");
        }
        if (Status.SIGNED.equals(status) || Status.LOCKED.equals(status)) {
            return Target.refused(409, "event_crf " + eventCrfId
                    + " is signed or locked; unlock it before removing it");
        }
        StudyEventBean visit = new StudyEventDAO(dataSource).findByPK(eventCrf.getStudyEventId());
        SubjectEventStatus visitStatus = visit == null ? null : visit.getSubjectEventStatus();
        if (SubjectEventStatus.SIGNED.equals(visitStatus) || SubjectEventStatus.LOCKED.equals(visitStatus)) {
            return Target.refused(409, "The visit is " + visitStatus.getName()
                    + "; un-sign or unlock it before removing a CRF");
        }
        return new Target(null, user, eventCrf, subject);
    }

    /** {@link #resolve}'s answer: a refusal, or what the request is about. */
    private record Target(ResponseEntity<?> refusal, UserAccountBean user, EventCRFBean eventCrf,
                          StudySubjectBean subject) {
        static Target refused(int status, String message) {
            return new Target(ResponseEntity.status(status).body(Map.of("message", message)), null, null, null);
        }
    }
}
