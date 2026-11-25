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
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpSession;

import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;

/**
 * The role matrix for the SPA's clinical-data write APIs, in one place.
 *
 * <p>Every endpoint under {@code controller/api} that creates or changes
 * clinical data, or its status, checks the caller's roles on the active
 * study. An account may hold several there, and it may do what any of them
 * allows ({@link #anyRoleOnTheStudyMay}, DR-042): the session's
 * {@code userRole}, which {@code POST /me/activeStudy} binds together with
 * the study, from the caller's own active bindings on that study, and the
 * caller's other active bindings there. {@code POST /me/activeStudy}
 * refuses a study the caller holds no role on. Neither the chain-level
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
 *   ADM  admin (1)         SPA "Administrator"; a study role row like the others,
 *                          the usual binding of a system administrator
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
 * columns are the roles held on the active study. A caller who holds
 * several may do what any of them allows.
 *
 * <pre>
 *                               SYS ADM CRC DM  INV RA  RA2 MON  rule
 *   Enter data                   .   x   x   x   x   x   x   .   {@link #roleMayEnterData}
 *   Reconcile DDE conflicts      .   x   .   x   x   .   .   .   EventCrfsApiController.roleMayReconcile
 *   Reopen a completed CRF       .   x   x   x   x   .   .   .   {@link CrfReopenAuthorization}
 *   Restore a removed CRF        x   x   x   x   .   .   .   .   {@link EventCrfRestoreAuthorization}
 *   Edit, cancel or restore
 *     a visit                    .   x   x   x   x   .   .   .   {@link EventEditAuthorization}
 *   Sign a visit or a subject    .   .   .   .   x   .   .   .   SubjectsApiController.roleMaySignSubject
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
 *   <li>Signing a subject or a visit is narrower than
 *       {@code SignStudySubjectServlet} (director, coordinator, investigator
 *       and any system administrator): both endpoints refuse with 403 every
 *       caller who holds no investigator role on the study, and the
 *       subject's preflight repeats the rule as its {@code user-role-can-sign}
 *       check. The signature is the investigator's (2026-11: the director,
 *       coordinator and admin roles lost it; study management enters and
 *       corrects data, it does not attest it). An investigator who also
 *       holds one of those roles signs as the investigator.</li>
 * </ul>
 *
 * <p>System administrators: every rule goes by the roles held on the
 * active study, which {@code POST /me/activeStudy} takes from the caller's
 * own role rows; a system administrator usually holds {@code admin}, and
 * every helper here admits that role. Only the restore and import gates
 * also admit a system administrator whatever the binding
 * ({@code isSysAdmin()}). {@code maySubmitData} and {@code SDVController}
 * have no such short-circuit either. {@code SignStudySubjectServlet},
 * {@code UpdateStudySubjectServlet}, {@code RemoveStudySubjectServlet} and
 * {@code RestoreStudySubjectServlet} do; their rules here deliberately do
 * not, so a system administrator who holds only, say, monitor neither signs
 * nor edits, removes or restores a subject through the SPA.
 *
 * <p>Some older helpers are deliberately narrower than their legacy
 * servlet, and stay so: editing a subject or a visit refuses ra and ra2,
 * signing a subject or a visit refuses the director, the coordinator and the
 * admin binding (above),
 * and un-verifying SDV refuses the coordinator, whom {@code SDVController}
 * admits. {@code GET /me} reports what the caller's roles on the study may
 * do ({@code activeStudy.permissions}), so that the SPA offers only what
 * these rules admit; ra and ra2 share the SPA role "Investigator".
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
        return session.getAttribute("userRole") instanceof StudyUserRoleBean role ? roleIdOf(role) : 0;
    }

    private static int roleIdOf(StudyUserRoleBean binding) {
        return binding == null || binding.getRole() == null ? 0 : binding.getRole().getId();
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
     * Does {@code rule} admit one of the roles the caller holds on the
     * active study?
     *
     * <p>An account may hold several roles on a study, and it may do what
     * any of them allows (DR-042). {@code POST /me/activeStudy} and the login
     * bind one of them, the highest-ranked, as the session's
     * {@code userRole}: a Data Manager who is also the study's Investigator is
     * bound as Data Manager, and still signs. The rule is tried on that
     * binding first. The caller's other active bindings are read only when it
     * refuses: those on the active study and, when the study is a site, those
     * on its parent, whose roles apply at its sites. A failed read refuses.
     *
     * <p>Restrictions are counted the same way: any treating role the
     * caller holds blinds it ({@link AiArmPolicy#isTreatingRole}).
     *
     * @param rule takes a legacy {@link Role} id; 0 when the session has no role
     */
    static boolean anyRoleOnTheStudyMay(HttpSession session, DataSource dataSource,
                                        IntPredicate rule) {
        return anyBindingOnTheStudyMay(session, dataSource, binding -> rule.test(roleIdOf(binding)));
    }

    /**
     * {@link #anyRoleOnTheStudyMay} for a rule that reads the binding, not
     * only its role. The rule is given the session's binding first, or
     * {@code null} when none is bound, so a rule that admits a system
     * administrator whatever the binding still does.
     */
    static boolean anyBindingOnTheStudyMay(HttpSession session, DataSource dataSource,
                                           Predicate<StudyUserRoleBean> rule) {
        StudyUserRoleBean bound = session.getAttribute("userRole") instanceof StudyUserRoleBean b ? b : null;
        if (rule.test(bound)) {
            return true;
        }
        for (StudyUserRoleBean binding : otherBindingsOnTheStudy(session, dataSource)) {
            if (rule.test(binding)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The legacy role ids the caller holds on the active study, the
     * session's first: the roles {@link #anyRoleOnTheStudyMay} counts, read
     * once, for a caller that asks several rules ({@code GET /me}).
     */
    static List<Integer> roleIdsOnTheStudy(HttpSession session, DataSource dataSource) {
        List<Integer> ids = new ArrayList<>();
        ids.add(roleIdOf(session));
        for (StudyUserRoleBean binding : otherBindingsOnTheStudy(session, dataSource)) {
            int id = roleIdOf(binding);
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * The caller's active bindings on the active study, or on its parent
     * when it is a site; empty when there is no caller, no study, or the
     * bindings cannot be read.
     */
    private static List<StudyUserRoleBean> otherBindingsOnTheStudy(HttpSession session,
                                                                   DataSource dataSource) {
        if (!(session.getAttribute("userBean") instanceof UserAccountBean user)
                || !(session.getAttribute("study") instanceof StudyBean study)
                || study.getId() <= 0) {
            return List.of();
        }
        List<StudyUserRoleBean> bindings = new ArrayList<>();
        try {
            for (StudyUserRoleBean binding
                    : new UserAccountDAO(dataSource).findAllRolesByUserName(user.getName())) {
                if (binding != null && binding.getRole() != null
                        && onTheStudyOrItsParent(binding.getStudyId(), study)
                        && Status.AVAILABLE.equals(binding.getStatus())) {
                    bindings.add(binding);
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return bindings;
    }

    private static boolean onTheStudyOrItsParent(int studyId, StudyBean study) {
        return studyId == study.getId()
                || (study.getParentStudyId() > 0 && studyId == study.getParentStudyId());
    }

    /**
     * @param action what is refused, completing "Your role does not permit …"
     * @return a 403 unless one of the caller's roles on the study may enter
     *         data; {@code null} when one may
     */
    static ResponseEntity<?> refuseUnlessMayEnterData(HttpSession session, DataSource dataSource,
                                                      String action) {
        return anyRoleOnTheStudyMay(session, dataSource, ClinicalWriteAuthorization::roleMayEnterData)
                ? null : forbidden(action);
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
