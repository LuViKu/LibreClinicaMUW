/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;

/**
 * The resolution statuses the legacy note page lets each role give a
 * discrepancy-note thread, so that {@link CreateOneDiscrepancyNoteServlet} and
 * {@link CreateDiscrepancyNoteServlet} can refuse a request the page would not
 * have produced. The page is {@link ViewDiscrepancyNoteServlet}, which picks
 * the status lists by role, with {@code viewDiscrepancyNote.jsp} and
 * {@code discrepancyNote.jsp}, which show the boxes and the choices. The
 * single-note popup ({@code addDiscrepancyNote.jsp}) offers new threads the
 * same choices and no reply box.
 *
 * <p>A reply sets its thread's status. Investigators and research assistants
 * may set "updated" or "resolution proposed", and cannot reply to a closed
 * thread. A monitor may set "new", "updated" or "closed". A coordinator or
 * director may set any of these four. No one replies to a thread that is "not
 * applicable", or to a note that is itself a reply, and a reply keeps its
 * thread's note type.
 *
 * <p>A new thread starts as the page offers by note type:
 * <ul>
 * <li>a failed validation check starts "new" or "resolution proposed", for
 * every role but the monitor;</li>
 * <li>a query starts "new", "updated" or "closed" for a monitor, and in any of
 * those or "resolution proposed" for a coordinator or director. Investigators
 * and research assistants are not offered queries;</li>
 * <li>annotations and reasons for change are always "not applicable", and are
 * offered to every role but the monitor.</li>
 * </ul>
 */
final class DiscrepancyNoteStatusRule {

    private DiscrepancyNoteStatusRule() {
    }

    /**
     * Whether a request names a status and type the page offers {@code role}:
     * a reply when it names a thread, otherwise a new thread. A new thread
     * without a type is left to the form's validation: a browser sends no type
     * when the chosen one is disabled (an annotation in a frozen study).
     *
     * @param thread    the note named as the thread's parent, null for a new thread
     * @param typeGiven whether the request carries a note type at all
     */
    static boolean offers(Role role, DiscrepancyNoteBean thread, boolean typeGiven, int typeId, int statusId) {
        if (thread != null) {
            return mayReply(role, thread, typeId, statusId);
        }
        return !typeGiven || mayStart(role, typeId, statusId);
    }

    /**
     * Whether {@code role} may reply to {@code thread} with a note of
     * {@code typeId} that sets the thread to {@code statusId}.
     *
     * @param thread the note named as the thread's parent; one with id 0 when it
     *               was not found
     */
    static boolean mayReply(Role role, DiscrepancyNoteBean thread, int typeId, int statusId) {
        // The page puts a reply box under a thread's parent note only, never
        // under one of its replies, and sends the thread's type with it.
        if (thread == null || thread.getId() <= 0 || thread.getParentDnId() > 0
                || typeId != thread.getDiscrepancyNoteTypeId()) {
            return false;
        }
        ResolutionStatus threadStatus = ResolutionStatus.get(thread.getResolutionStatusId());
        if (threadStatus.getId() <= 0 || threadStatus.equals(ResolutionStatus.NOT_APPLICABLE)
                || (threadStatus.equals(ResolutionStatus.CLOSED) && entersData(role))) {
            return false;
        }
        if (setsNotApplicable(typeId)) {
            // The reply turns the thread "not applicable" whatever was chosen.
            return !replyStatuses(role).isEmpty();
        }
        return replyStatuses(role).contains(ResolutionStatus.get(statusId));
    }

    /** Whether {@code role} may start a thread with a note of {@code typeId} in {@code statusId}. */
    static boolean mayStart(Role role, int typeId, int statusId) {
        DiscrepancyNoteType type = DiscrepancyNoteType.get(typeId);
        if (setsNotApplicable(typeId)) {
            // Saved "not applicable" whatever was chosen.
            return entersData(role) || manages(role);
        }
        if (type.equals(DiscrepancyNoteType.FAILEDVAL) && (entersData(role) || manages(role))) {
            return List.of(ResolutionStatus.OPEN, ResolutionStatus.RESOLVED).contains(ResolutionStatus.get(statusId));
        }
        if (type.equals(DiscrepancyNoteType.QUERY) && (Role.MONITOR.equals(role) || manages(role))) {
            return replyStatuses(role).contains(ResolutionStatus.get(statusId));
        }
        return false;
    }

    /** The statuses the reply box offers {@code role}: the page's "resolutionStatuses". */
    static List<ResolutionStatus> replyStatuses(Role role) {
        if (entersData(role)) {
            return List.of(ResolutionStatus.UPDATED, ResolutionStatus.RESOLVED);
        }
        if (Role.MONITOR.equals(role)) {
            return List.of(ResolutionStatus.OPEN, ResolutionStatus.UPDATED, ResolutionStatus.CLOSED);
        }
        if (manages(role)) {
            return List.of(ResolutionStatus.OPEN, ResolutionStatus.UPDATED, ResolutionStatus.RESOLVED, ResolutionStatus.CLOSED);
        }
        return List.of();
    }

    /** Annotations and reasons for change are saved "not applicable". */
    private static boolean setsNotApplicable(int typeId) {
        return typeId == DiscrepancyNoteType.ANNOTATION.getId() || typeId == DiscrepancyNoteType.REASON_FOR_CHANGE.getId();
    }

    /** Investigators and research assistants: the roles the page offers the fewest statuses. */
    private static boolean entersData(Role role) {
        return Role.INVESTIGATOR.equals(role) || Role.RESEARCHASSISTANT.equals(role) || Role.RESEARCHASSISTANT2.equals(role);
    }

    private static boolean manages(Role role) {
        return Role.COORDINATOR.equals(role) || Role.STUDYDIRECTOR.equals(role);
    }
}
