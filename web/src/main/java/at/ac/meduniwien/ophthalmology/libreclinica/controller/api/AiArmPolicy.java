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

import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;

/**
 * P3.0 — the trial-blinding rule, in one place.
 *
 * <p>The nAMD study randomises subjects into an arm that sees AI output and one
 * that does not. Two controllers act on that: the retinal controller decides
 * who may <em>see</em> AI results, and {@code SubjectsApiController} decides who
 * may <em>change</em> a subject's arm. Both spelled the two group names as
 * string literals in their own SQL, in four places.
 *
 * <p>Blinding is the kind of rule that is wrong quietly. A missed check does
 * not throw — it shows a treating physician the AI's answer before they record
 * their own, and the trial finds out at analysis. So the rule gets one
 * implementation and one set of names, and the P3.9 guard-rail test can then
 * ban those literals from shared controllers outright.
 *
 * <p><strong>This is the enforcement point.</strong> The SPA also hides AI
 * panels by arm, but that is defence in depth — it is bypassable with a pasted
 * URL or curl. Anything the server will not send cannot be un-hidden.
 *
 * <p>P3.5 moves the two names into {@code study_setting} so a study declares
 * its own arms. Keeping them here is what makes that a one-file change.
 */
public final class AiArmPolicy {

    /** The arm that sees AI output. */
    public static final String ARM_SHOWN = "AI_SHOWN";

    /** The arm that must not. */
    public static final String ARM_HIDDEN = "AI_HIDDEN";

    /** Both, for the {@code IN (...)} clauses that look up the arm. */
    private static final String ARM_NAMES_SQL = "('" + ARM_SHOWN + "', '" + ARM_HIDDEN + "')";

    private AiArmPolicy() {}

    /* ------------------------------------------------------------------ */
    /* Who is blinded                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Treating-clinician roles, which must not see AI output for a subject in
     * the hidden arm: the Investigator (the treating physician) and the Study
     * Coordinator, who is clinical-facing and inherits Investigator in the role
     * hierarchy.
     *
     * <p>Data Manager, Monitor and Administrator keep full access — they are
     * not the ones making the treatment decision the trial is measuring.
     */
    public static boolean isTreatingRole(HttpSession session) {
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (currentRole == null || currentRole.getRole() == null) return false;
        Role r = currentRole.getRole();
        return r.equals(Role.INVESTIGATOR) || r.equals(Role.COORDINATOR);
    }

    /**
     * Withhold AI output when the subject is in the hidden arm and the
     * requester is a treating clinician.
     *
     * <p>Applied to the job DTO (AI fields stripped), {@code /segmentation}, AI
     * {@code /artifacts}, {@code /compare-previous}, the per-subject list,
     * retinal-trends and crt-timeline. The raw scan companions (bscan.dcm,
     * fundus.png, geometry.json) are deliberately <em>not</em> masked: the
     * physician still sees the unannotated scan, which is the clinical
     * requirement — blinding hides the AI's reading, not the patient's eye.
     *
     * <p>Masking triggers only on an explicitly resolved hidden arm. A null arm
     * — a non-arm study, or a subject not yet randomised — is not masked;
     * randomisation happens at enrolment, before any scan exists, so a
     * job-owning subject in an arm study is already assigned by the time this
     * is reached.
     */
    public static boolean maskAiFor(String subjectArm, HttpSession session) {
        return ARM_HIDDEN.equals(subjectArm) && isTreatingRole(session);
    }

    /* ------------------------------------------------------------------ */
    /* Which arm a subject is in                                           */
    /* ------------------------------------------------------------------ */

    /**
     * The arm of a subject, or null when the subject is in neither — which is
     * the ordinary case for every study that does not randomise on AI.
     *
     * <p>For the subject-scoped endpoints (per-subject job list,
     * retinal-trends, crt-timeline) that have no job or event context.
     */
    public static String armForSubject(Connection c, int studySubjectId) throws SQLException {
        if (studySubjectId <= 0) return null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sg.name FROM subject_group_map sgm "
                        + "  JOIN study_group sg ON sg.study_group_id = sgm.study_group_id "
                        + " WHERE sgm.study_subject_id = ? AND sgm.status_id = 1 "
                        + "   AND UPPER(sg.name) IN " + ARM_NAMES_SQL + " LIMIT 1")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? upper(rs.getString(1)) : null;
            }
        }
    }

    /**
     * The arm behind a visit or a CRF instance, whichever the caller has.
     *
     * <p>Retinal jobs from the public OCT portal attach by
     * {@code study_event_id} and leave {@code event_crf_id} null, so a lookup
     * that joined only through {@code event_crf} missed every one of them and
     * reported "no arm" — which reads as "not blinded". Accept either key.
     */
    public static String armForEvent(Connection c, int eventCrfId, int studyEventId) throws SQLException {
        if (studyEventId <= 0 && eventCrfId <= 0) return null;
        String subject = studyEventId > 0
                ? "ev.study_event_id = ? "
                : "ev.study_event_id IN (SELECT study_event_id FROM event_crf WHERE event_crf_id = ?) ";
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sg.name "
                        + "  FROM study_event ev "
                        + "  JOIN subject_group_map sgm "
                        + "    ON sgm.study_subject_id = ev.study_subject_id AND sgm.status_id = 1 "
                        + "  JOIN study_group sg ON sg.study_group_id = sgm.study_group_id "
                        + " WHERE UPPER(sg.name) IN " + ARM_NAMES_SQL
                        + "   AND " + subject + " LIMIT 1")) {
            ps.setInt(1, studyEventId > 0 ? studyEventId : eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? upper(rs.getString(1)) : null;
            }
        }
    }

    /**
     * The group class carrying the arms for a study, or null when the study has
     * none — which is how a caller tells "not an arm study" from "unassigned".
     */
    public static Integer armGroupClassId(Connection c, int studyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sg.study_group_class_id "
                        + "  FROM study_group sg "
                        + "  JOIN study_group_class sgc "
                        + "    ON sgc.study_group_class_id = sg.study_group_class_id "
                        + " WHERE sgc.study_id = ? "
                        + "   AND UPPER(sg.name) IN " + ARM_NAMES_SQL + " LIMIT 1")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.valueOf(rs.getInt(1)) : null;
            }
        }
    }

    /**
     * Group names are compared case-insensitively in SQL to absorb
     * institutional capitalisation, so the value handed back is normalised too
     * — {@link #maskAiFor} matches exactly, and "ai_hidden" reaching it
     * unnormalised would read as "not blinded".
     */
    private static String upper(String name) {
        return name == null ? null : name.toUpperCase(java.util.Locale.ROOT);
    }
}
