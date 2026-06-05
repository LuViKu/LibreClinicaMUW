/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.discrepancy;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Phase E.6 {@code discrepancy-full} — outbound email notification
 * for discrepancy-note state changes.
 *
 * <p>Fires on three lifecycle events:
 * <ul>
 *   <li><strong>created</strong> — a parent note is opened against a
 *       data point; the assignee (if any) is notified.</li>
 *   <li><strong>stateChanged</strong> — a child reply transitions the
 *       parent (e.g. {@code updated → resolution-proposed}); the note's
 *       current assignee is notified.</li>
 *   <li><strong>reassigned</strong> — the assignee changed; the new
 *       assignee is notified.</li>
 * </ul>
 *
 * <p>Per the playbook acceptance criteria: {@code MailException} is
 * swallowed (the controller does NOT 5xx when SMTP is offline). The
 * legacy {@link OpenClinicaMailSender#sendEmail} wraps SMTP failures
 * in {@link OpenClinicaSystemException}; we catch + log at WARN and
 * return so the audit trail still reflects the data-side change.
 *
 * <p>Subject lines are short, English-first (per MUW i18n convention
 * the SPA labels are i18n'd, but operator emails are admin-tier
 * comms and stay in a single language to avoid Locale lookup at the
 * notification site).
 */
@Service
public class DiscrepancyEmailNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(DiscrepancyEmailNotifier.class);

    private final OpenClinicaMailSender mailSender;

    @Autowired
    public DiscrepancyEmailNotifier(@Qualifier("openClinicaMailSender") OpenClinicaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Fired when a parent note is created. Notifies the assignee
     * (when present). No-op when {@code assignee} is null or has no
     * email address.
     */
    public void notifyCreated(DiscrepancyNoteBean note, UserAccountBean assignee, StudyBean study) {
        if (assignee == null || isBlank(assignee.getEmail())) {
            return;
        }
        String subject = "[LibreClinica] New discrepancy assigned: "
                + safeStudy(study) + " · " + safeId(note);
        String body = "<p>A new discrepancy note has been opened and assigned to you.</p>"
                + "<ul>"
                + "<li>Study: " + escape(safeStudy(study)) + "</li>"
                + "<li>Note id: " + safeId(note) + "</li>"
                + "<li>Description: " + escape(note.getDescription()) + "</li>"
                + "</ul>";
        send(assignee.getEmail(), subject, body);
    }

    /**
     * Fired when a thread entry transitions the parent's status. The
     * current assignee is notified so they can react (e.g.
     * Investigator gets a state-change ping when Monitor reopens a
     * resolution-proposed note).
     */
    public void notifyStateChanged(DiscrepancyNoteBean parent, UserAccountBean assignee,
                                   String oldStatusSpa, String newStatusSpa, StudyBean study) {
        if (assignee == null || isBlank(assignee.getEmail())) {
            return;
        }
        String subject = "[LibreClinica] Discrepancy state changed: "
                + safeStudy(study) + " · " + safeId(parent);
        String body = "<p>The status of a discrepancy note has changed.</p>"
                + "<ul>"
                + "<li>Study: " + escape(safeStudy(study)) + "</li>"
                + "<li>Note id: " + safeId(parent) + "</li>"
                + "<li>Previous status: " + escape(oldStatusSpa) + "</li>"
                + "<li>New status: " + escape(newStatusSpa) + "</li>"
                + "</ul>";
        send(assignee.getEmail(), subject, body);
    }

    /**
     * Fired when the parent's assignee changes. Notifies the new
     * assignee (the old assignee is intentionally not notified to
     * keep the inbox quiet).
     */
    public void notifyReassigned(DiscrepancyNoteBean parent, UserAccountBean newAssignee, StudyBean study) {
        if (newAssignee == null || isBlank(newAssignee.getEmail())) {
            return;
        }
        String subject = "[LibreClinica] Discrepancy reassigned: "
                + safeStudy(study) + " · " + safeId(parent);
        String body = "<p>A discrepancy note has been reassigned to you.</p>"
                + "<ul>"
                + "<li>Study: " + escape(safeStudy(study)) + "</li>"
                + "<li>Note id: " + safeId(parent) + "</li>"
                + "<li>Description: " + escape(parent.getDescription()) + "</li>"
                + "</ul>";
        send(newAssignee.getEmail(), subject, body);
    }

    private void send(String to, String subject, String htmlBody) {
        try {
            mailSender.sendEmail(to, subject, htmlBody, Boolean.TRUE);
        } catch (OpenClinicaSystemException ex) {
            // Swallow per playbook AC: a discrepancy state change must
            // not 5xx when SMTP is unreachable. Log + move on.
            LOG.warn("Discrepancy email to {} failed: {}", to, ex.getMessage());
        } catch (RuntimeException ex) {
            LOG.warn("Discrepancy email to {} failed: {}", to, ex.toString());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safeStudy(StudyBean study) {
        if (study == null) return "(unknown study)";
        String oid = study.getOid();
        return (oid == null || oid.isBlank()) ? study.getName() : oid;
    }

    private static String safeId(DiscrepancyNoteBean n) {
        return n == null ? "?" : String.valueOf(n.getId());
    }

    /** Minimal HTML escape — these are admin emails so the surface is small. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
