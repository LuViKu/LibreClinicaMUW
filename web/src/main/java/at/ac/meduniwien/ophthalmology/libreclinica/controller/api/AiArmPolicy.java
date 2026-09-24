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
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

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
 * <p><strong>P3.5 — the names are per study now.</strong> A trial randomises
 * on groups it named itself, and a second AI study would otherwise have to
 * adopt this one's vocabulary. {@link #armNamesFor} reads
 * {@code study_setting}; the constants below are the fallback, so a study
 * that has said nothing is blinded exactly as before. That fallback is not
 * laziness — silently changing which group is hidden is the worst behaviour
 * change this codebase could make by accident.
 */
public final class AiArmPolicy {

    /**
     * The two states, and the default group names.
     *
     * <p>P3.5 — these serve double duty. A study may call its groups anything
     * ({@code ai.arm.shownGroup} / {@code ai.arm.hiddenGroup}); the lookups
     * below translate whatever it uses into one of these two tokens, so every
     * caller compares against a fixed vocabulary and none of them has to know
     * what a particular trial named its arms. A study that has configured
     * nothing uses these as its names too, which is why the translation is
     * invisible on an instance that never sets them.
     */
    public static final String ARM_SHOWN = "AI_SHOWN";
    public static final String ARM_HIDDEN = "AI_HIDDEN";

    private AiArmPolicy() {}

    /**
     * The group names a study randomises AI visibility on.
     *
     * @return {shown, hidden} — the study's own, or the platform defaults when
     *         it has not said. Never null, and never empty: a blank setting
     *         falls back rather than disabling the gate, because a study that
     *         mis-types a group name must not thereby unblind itself.
     */
    public static String[] armNamesFor(Connection c, int studyId) {
        String shown = setting(c, studyId, "ai.arm.shownGroup");
        String hidden = setting(c, studyId, "ai.arm.hiddenGroup");
        return new String[] {
                shown == null || shown.isBlank() ? ARM_SHOWN : shown.trim(),
                hidden == null || hidden.isBlank() ? ARM_HIDDEN : hidden.trim(),
        };
    }

    /** A study's setting, its parent's, or null. Failure reads as "unset". */
    private static String setting(Connection c, int studyId, String key) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.value FROM study_setting s "
                        + " WHERE s.setting_key = ? "
                        + "   AND s.study_id IN (?, COALESCE((SELECT parent_study_id FROM study "
                        + "                                    WHERE study_id = ?), -1)) "
                        + " ORDER BY CASE WHEN s.study_id = ? THEN 0 ELSE 1 END LIMIT 1")) {
            ps.setString(1, key);
            ps.setInt(2, studyId);
            ps.setInt(3, studyId);
            ps.setInt(4, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException lookupFailed) {
            // Falling back to the constants keeps the gate closed, which is the
            // safe direction for a blinding rule.
            return null;
        }
    }

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
        // P3.5 — matched against the names this subject's own study uses. The
        // sub-select resolves them inline rather than in a second round trip,
        // and COALESCEs to the platform defaults so a study that has said
        // nothing behaves exactly as before.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + canonicalCase("sg.name", "ss.study_id") + " "
                        + "  FROM subject_group_map sgm "
                        + "  JOIN study_group sg ON sg.study_group_id = sgm.study_group_id "
                        + "  JOIN study_subject ss ON ss.study_subject_id = sgm.study_subject_id "
                        + " WHERE sgm.study_subject_id = ? AND sgm.status_id = 1 "
                        + "   AND UPPER(sg.name) IN (UPPER(" + armNameSql("shown", "ss.study_id") + "), "
                        + "                          UPPER(" + armNameSql("hidden", "ss.study_id") + ")) "
                        + "   AND " + blindingOnSql("ss.study_id")
                        + " LIMIT 1")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * SQL for "the name this study calls one of its arms", defaulting to the
     * platform constant.
     *
     * <p>Inlined rather than parameterised because it is composed into three
     * different queries at different parameter positions; the only values
     * interpolated are this class's own constants and a column name it also
     * owns, never anything a caller supplies.
     */
    private static String armNameSql(String which, String studyIdExpr) {
        String key = "shown".equals(which) ? "ai.arm.shownGroup" : "ai.arm.hiddenGroup";
        String fallback = "shown".equals(which) ? ARM_SHOWN : ARM_HIDDEN;
        return settingSql(key, studyIdExpr, fallback);
    }

    /**
     * A study setting resolved inline — the site's own row, else the parent
     * study's, else the fallback. The same two-level lookup
     * {@code StudySettingService} performs, minus the {@code core.*} property
     * step, which SQL cannot see; for the keys read here that step has never
     * been configured on any instance and the code default is what applies.
     */
    private static String settingSql(String key, String studyIdExpr, String fallback) {
        return "COALESCE((SELECT st.value FROM study_setting st "
                + "         WHERE st.setting_key = '" + key + "' "
                + "           AND st.study_id IN (" + studyIdExpr + ", "
                + "                 COALESCE((SELECT parent_study_id FROM study "
                + "                            WHERE study_id = " + studyIdExpr + "), -1)) "
                + "         ORDER BY CASE WHEN st.study_id = " + studyIdExpr + " THEN 0 ELSE 1 END "
                + "         LIMIT 1), '" + fallback + "')";
    }

    /**
     * True unless the study has switched blinding off
     * ({@code ai.blinding.enabled = false}, 2026-09-24). Appended to the arm
     * lookups so an unblinded study resolves to <em>no arm</em> for every
     * subject — and no arm is the one answer {@link #maskAiFor} never masks.
     * Doing it here rather than at the seven masking call sites keeps the
     * decision in one place, next to the arm vocabulary it belongs with.
     */
    private static String blindingOnSql(String studyIdExpr) {
        return "LOWER(" + settingSql(StudySettingService.AI_BLINDING_ENABLED, studyIdExpr, "true")
                + ") <> 'false'";
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
                "SELECT " + canonicalCase("sg.name", "ss.study_id") + " "
                        + "  FROM study_event ev "
                        + "  JOIN subject_group_map sgm "
                        + "    ON sgm.study_subject_id = ev.study_subject_id AND sgm.status_id = 1 "
                        + "  JOIN study_group sg ON sg.study_group_id = sgm.study_group_id "
                        + "  JOIN study_subject ss ON ss.study_subject_id = ev.study_subject_id "
                        + " WHERE UPPER(sg.name) IN (UPPER(" + armNameSql("shown", "ss.study_id") + "), "
                        + "                          UPPER(" + armNameSql("hidden", "ss.study_id") + ")) "
                        + "   AND " + blindingOnSql("ss.study_id")
                        + "   AND " + subject + " LIMIT 1")) {
            ps.setInt(1, studyEventId > 0 ? studyEventId : eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * SQL mapping whatever a study calls a group onto one of the two tokens.
     *
     * <p>This is the whole point of P3.5's arm handling: the vocabulary is
     * translated once, at the boundary, so the seven masking call sites keep
     * comparing against {@link #ARM_HIDDEN} and none of them can be wrong for
     * a study that renamed its groups. Doing it the other way — handing the
     * raw name outward and expecting every caller to resolve it — is how a
     * renamed group silently unblinds a trial.
     */
    private static String canonicalCase(String nameExpr, String studyIdExpr) {
        return "CASE WHEN UPPER(" + nameExpr + ") = UPPER("
                + armNameSql("hidden", studyIdExpr) + ") THEN '" + ARM_HIDDEN + "' "
                + "     ELSE '" + ARM_SHOWN + "' END";
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
                        + "   AND UPPER(sg.name) IN (UPPER(" + armNameSql("shown", "sgc.study_id") + "), "
                        + "                          UPPER(" + armNameSql("hidden", "sgc.study_id") + ")) "
                        + " LIMIT 1")) {
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
