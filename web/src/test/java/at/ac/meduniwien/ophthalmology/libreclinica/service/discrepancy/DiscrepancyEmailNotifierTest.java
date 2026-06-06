/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.discrepancy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Phase E.6 {@code discrepancy-full} — Mockito coverage for
 * {@link DiscrepancyEmailNotifier}. Pins the three lifecycle hooks
 * + the MailException-swallow contract.
 */
class DiscrepancyEmailNotifierTest {

    private OpenClinicaMailSender mailSender;
    private DiscrepancyEmailNotifier notifier;

    @BeforeEach
    void setUp() {
        mailSender = Mockito.mock(OpenClinicaMailSender.class);
        notifier = new DiscrepancyEmailNotifier(mailSender);
    }

    private static DiscrepancyNoteBean note(int id, String description) {
        DiscrepancyNoteBean n = new DiscrepancyNoteBean();
        n.setId(id);
        n.setDescription(description);
        return n;
    }

    private static UserAccountBean user(int id, String name, String email) {
        UserAccountBean ub = new UserAccountBean();
        ub.setId(id);
        ub.setName(name);
        ub.setEmail(email);
        return ub;
    }

    private static StudyBean study(int id, String oid, String name) {
        StudyBean s = new StudyBean();
        s.setId(id);
        s.setOid(oid);
        s.setName(name);
        return s;
    }

    /* -------------------------------------------------------------- */
    /* notifyCreated                                                  */
    /* -------------------------------------------------------------- */

    @Test
    void notifyCreated_SendsToAssigneeEmail() {
        notifier.notifyCreated(
                note(42, "Age looks low"),
                user(7, "investigator_demo", "inv@example.com"),
                study(1, "S_TEST", "Default Study"));

        verify(mailSender, times(1)).sendEmail(
                eq("inv@example.com"),
                contains("New discrepancy assigned"),
                any(String.class),
                eq(Boolean.TRUE));
    }

    @Test
    void notifyCreated_NoOpWhenAssigneeIsNull() {
        notifier.notifyCreated(note(42, "d"), null, study(1, "S", "Default"));
        verify(mailSender, never()).sendEmail(
                any(String.class), any(String.class), any(String.class), anyBoolean());
    }

    @Test
    void notifyCreated_NoOpWhenAssigneeHasNoEmail() {
        notifier.notifyCreated(
                note(42, "d"),
                user(7, "no_email_user", ""),
                study(1, "S", "Default"));
        verify(mailSender, never()).sendEmail(
                any(String.class), any(String.class), any(String.class), anyBoolean());
    }

    /* -------------------------------------------------------------- */
    /* notifyStateChanged                                             */
    /* -------------------------------------------------------------- */

    @Test
    void notifyStateChanged_SendsToCurrentAssignee() {
        notifier.notifyStateChanged(
                note(42, "d"),
                user(7, "inv", "inv@example.com"),
                "new",
                "updated",
                study(1, "S", "Default"));

        verify(mailSender, times(1)).sendEmail(
                eq("inv@example.com"),
                contains("state changed"),
                any(String.class),
                eq(Boolean.TRUE));
    }

    /* -------------------------------------------------------------- */
    /* notifyReassigned                                               */
    /* -------------------------------------------------------------- */

    @Test
    void notifyReassigned_SendsToNewAssignee() {
        notifier.notifyReassigned(
                note(42, "d"),
                user(8, "new_inv", "new@example.com"),
                study(1, "S", "Default"));

        verify(mailSender, times(1)).sendEmail(
                eq("new@example.com"),
                contains("reassigned"),
                any(String.class),
                eq(Boolean.TRUE));
    }

    /* -------------------------------------------------------------- */
    /* MailException swallow contract                                 */
    /* -------------------------------------------------------------- */

    @Test
    void mailException_IsSwallowed_NotPropagated() {
        doThrow(new OpenClinicaSystemException("smtp-offline"))
                .when(mailSender)
                .sendEmail(any(String.class), any(String.class), any(String.class), anyBoolean());

        // Per the playbook AC: state-change side effects must not 5xx
        // when SMTP is offline. The notifier swallows the exception
        // and logs at WARN.
        notifier.notifyCreated(
                note(42, "d"),
                user(7, "inv", "inv@example.com"),
                study(1, "S", "Default"));
        // No assertion — the lack of a thrown exception IS the
        // contract. verify() confirms sendEmail was attempted.
        verify(mailSender, times(1)).sendEmail(
                any(String.class), any(String.class), any(String.class), anyBoolean());
    }
}
