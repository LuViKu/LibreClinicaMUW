/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;

/**
 * The role matrix for the SPA's clinical-data write APIs, in one place.
 *
 * <p>Every endpoint under {@code controller/api} that creates or changes
 * clinical data, or its status, checks the caller's role on the active
 * study. The role is the session's {@code userRole}:
 * {@code POST /me/activeStudy} binds it together with the study, from the
 * caller's own active bindings on that study, and refuses a study the
 * caller holds no role on. Neither the chain-level
 * {@code hasRole("USER")} rule nor site visibility says what a user may
 * change: the first says the caller is logged in, the second which
 * subjects the caller may see. Without the per-endpoint check a Monitor,
 * a read-only verification role, could write item data through the API.
 *
 * <p>Where a helper already owned a rule, the rule stays in that helper and
 * the table names it. The rules that had no helper are in this class.
 *
 * <h2>Roles</h2>
 *
 * <pre>
 *   ADM  admin (1)         SPA "Administrator"; the binding a system administrator gets
 *   CRC  coordinator (2)   SPA "CRC"
 *   DM   director (3)      SPA "Data Manager"
 *   INV  Investigator (4)  SPA "Investigator"
 *   RA   ra (5)            SPA "Investigator"
 *   MON  monitor (6)       SPA "Monitor"
 *   RA2  ra2 (7)           SPA "Investigator"
 * </pre>
 *
 * <h2>Matrix</h2>
 *
 * <p>SYS is any system administrator, whatever the binding; the other
 * columns are the binding on the active study.
 *
 * <pre>
 *                               SYS ADM CRC DM  INV RA  RA2 MON  rule
 *   Enter data                   .   x   x   x   x   x   x   .   {@link #roleMayEnterData}
 *   Reconcile DDE conflicts      .   x   .   x   x   .   .   .   EventCrfsApiController.roleMayReconcile
 *   Reopen a completed CRF       .   x   x   x   x   .   .   .   {@link CrfReopenAuthorization}
 *   Restore a removed CRF        x   x   x   x   .   .   .   .   {@link EventCrfRestoreAuthorization}
 *   Edit, cancel, restore or
 *     sign a visit               .   x   x   x   x   .   .   .   {@link EventEditAuthorization}
 *   Sign a subject               .   x   x   x   x   .   .   .   {@link EventEditAuthorization#roleMayEdit}
 *   Edit a subject               .   x   x   x   x   .   .   .   {@link SubjectEditAuthorization}
 *   Remove, restore, lock or
 *     unlock a subject; link
 *     a patient                  .   x   .   x   .   .   .   .   {@link SubjectLifecycleAuthorization}
 *   Verify SDV                   .   x   x   x   .   .   .   x   {@link #roleMayVerifySdv}
 *   Un-verify SDV                .   x   .   x   .   .   .   x   {@link SdvUnverifyAuthorization}
 *   Raise a query, annotation
 *     or failed check            .   x   x   x   x   .   .   x   {@link NoteTransitionMatrix#canCreateType}
 *   Reason-for-change note       .   x   .   x   .   .   .   .   {@link NoteTransitionMatrix#canCreateType}
 *   Change a note's status       per {@link NoteTransitionMatrix#check}
 *   Import CRF data              x   .   x   x   x   x   x   .   {@link BulkImportAuthorization}
 *   Bind an image or a parked
 *     retinal scan to a visit    .   x   x   x   x   .   .   .   {@link IngestBindAuthorization}
 * </pre>
 *
 * <h2>Endpoints</h2>
 *
 * <p>Paths are under {@code /pages/api/v1}.
 * <ul>
 *   <li><b>Enter data:</b> {@code POST eventCrfs/{id}/items},
 *       {@code POST} and {@code DELETE eventCrfs/{id}/groups/{groupOid}/rows},
 *       {@code POST} and {@code DELETE eventCrfs/{id}/items/{itemOid}/file},
 *       {@code POST eventCrfs/{id}/dde-commit},
 *       {@code POST eventCrfs/{id}/markComplete},
 *       {@code POST eventCrfs/{id}:autoPopulateRetinal},
 *       {@code POST events/{id}/crfs/{edcId}:start}, {@code POST events},
 *       {@code POST subjects}, {@code POST event-crfs/{id}/oct-upload},
 *       {@code POST study-events/{id}/namd-clinical-flags}, and
 *       {@code POST retinal-jobs/{id}/retry|rerun-as}, whose result is
 *       written into the visit's CRF.</li>
 *   <li><b>Reconcile DDE conflicts:</b>
 *       {@code POST eventCrfs/{id}/dde-conflicts/{itemOid}/resolve}.</li>
 *   <li><b>Reopen, restore a CRF:</b> {@code POST eventCrfs/{id}/markIncomplete},
 *       {@code POST eventCrfs/{id}/restore}.</li>
 *   <li><b>Visits:</b> {@code PUT} and {@code DELETE events/{id}},
 *       {@code POST events/{id}/restore}, {@code POST events/{id}/sign}.</li>
 *   <li><b>Subjects:</b> {@code POST subjects/{oid}/sign};
 *       {@code PUT subjects/{oid}}, {@code PUT subjects/{oid}/groups},
 *       {@code POST subjects/{label}/eyes/{eye}/transition};
 *       {@code POST subjects/{oid}/remove|restore|lock|unlock},
 *       {@code POST study-subjects/{id}/link-patient}.</li>
 *   <li><b>SDV:</b> {@code POST sdv/verify}; {@code POST sdv/unverify}, and
 *       {@code POST sdv/verify} with {@code verified: false}.</li>
 *   <li><b>Notes:</b> {@code POST discrepancies},
 *       {@code POST discrepancies/{id}/thread}.</li>
 *   <li><b>Import:</b> {@code POST import}, {@code POST import/commit},
 *       {@code GET import/{token}/rows}.</li>
 *   <li><b>Binding:</b> the bind, unbind, dismiss and restore endpoints of
 *       {@code ingest}, {@code ingest/upload} and {@code image-ingest}, and
 *       {@code PATCH retinal-jobs/{id}/bind}, {@code POST retinal-jobs/bulk-bind}.</li>
 * </ul>
 *
 * <h2>Where the rules come from</h2>
 *
 * <p>Each rule follows the legacy servlet that does the same job:
 * <ul>
 *   <li>Entering data follows {@code SubmitDataServlet.maySubmitData}, the
 *       gate of {@code AddNewSubjectServlet}, {@code CreateNewStudyEventServlet}
 *       and {@code AdministrativeEditingServlet}, and the check
 *       {@code InitialDataEntryServlet} and {@code DoubleDataEntryServlet}
 *       carry commented out: coordinator, director, investigator, ra and ra2.</li>
 *   <li>SDV follows {@code SDVController.mayProceed}: director, coordinator
 *       and monitor.</li>
 *   <li>Signing a subject follows {@code SignStudySubjectServlet}: director,
 *       coordinator and investigator. It shares the visit-signing rule.</li>
 * </ul>
 *
 * <p>System administrators: every helper here admits the {@code admin}
 * binding, which is how the SPA binds a system administrator to a study,
 * and the rules in this class do the same. A helper short-circuits on
 * {@code isSysAdmin()} only where its legacy servlet does, which is the
 * restore and import gates. {@code maySubmitData} and
 * {@code SDVController} have no such short-circuit, so neither rule here
 * has one.
 *
 * <p>Some older helpers are deliberately narrower than their legacy
 * servlet, and stay so: editing a subject or a visit refuses ra and ra2,
 * and un-verifying SDV refuses the coordinator, whom {@code SDVController}
 * admits.
 *
 * <h2>Order of checks</h2>
 *
 * <p>The role check runs after the 401 and no-active-study checks and
 * before any row is read, so a refused caller learns nothing about the
 * rows it names. Where the body decides which rule applies (the note
 * type, {@code verified: false}), the body is read first. A refusal is a
 * 403 with the same {@code message} body as every other refusal in this
 * package. No refusal writes an audit row: the failure audit
 * ({@code FailureAuditTemplate}) records exceptions thrown by a write,
 * and every guard in this package runs before it.
 */
final class ClinicalWriteAuthorization {

    private ClinicalWriteAuthorization() {}

    /** The legacy {@link Role} id of the session's {@code userRole}; 0 when none is bound. */
    static int roleIdOf(HttpSession session) {
        Object bound = session.getAttribute("userRole");
        if (!(bound instanceof StudyUserRoleBean role) || role.getRole() == null) {
            return 0;
        }
        return role.getRole().getId();
    }

    /**
     * May the role enter or change clinical data: item values, repeating
     * rows, file items, a double-data-entry pass, completing or starting a
     * CRF, adding a subject, scheduling a visit? Every role but the Monitor.
     *
     * @param roleId legacy {@link Role} id; 0 for no role (refused)
     */
    static boolean roleMayEnterData(int roleId) {
        return roleId == Role.ADMIN.getId()
                || roleId == Role.COORDINATOR.getId()
                || roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.INVESTIGATOR.getId()
                || roleId == Role.RESEARCHASSISTANT.getId()
                || roleId == Role.RESEARCHASSISTANT2.getId();
    }

    /**
     * May the role mark event CRFs source-data verified? Director,
     * coordinator and monitor, as in {@code SDVController}, and admin.
     *
     * @param roleId legacy {@link Role} id; 0 for no role (refused)
     */
    static boolean roleMayVerifySdv(int roleId) {
        return roleId == Role.ADMIN.getId()
                || roleId == Role.COORDINATOR.getId()
                || roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.MONITOR.getId();
    }

    /**
     * @param action what is refused, completing "Your role does not permit …"
     * @return a 403 unless the session's role may enter data; {@code null}
     *         when it may
     */
    static ResponseEntity<?> refuseUnlessMayEnterData(HttpSession session, String action) {
        return roleMayEnterData(roleIdOf(session)) ? null : forbidden(action);
    }

    /**
     * The refusal the role checks in this package return.
     *
     * @param action what is refused, completing "Your role does not permit …"
     */
    static ResponseEntity<?> forbidden(String action) {
        return ResponseEntity.status(403).body(Map.of("message",
                "Your role does not permit " + action));
    }
}
