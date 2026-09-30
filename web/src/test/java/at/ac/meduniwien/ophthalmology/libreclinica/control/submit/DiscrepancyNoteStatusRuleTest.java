/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * The statuses the legacy note page offers each role, as
 * {@link DiscrepancyNoteStatusRule} states them for
 * {@code CreateOneDiscrepancyNoteServlet}. The lists are the page's:
 * {@code ViewDiscrepancyNoteServlet} (resolutionStatuses per role),
 * {@code viewDiscrepancyNote.jsp} (when a thread gets a reply box) and
 * {@code discrepancyNote.jsp} (the choices for a new thread).
 */
class DiscrepancyNoteStatusRuleTest {

    private static final int NEW = ResolutionStatus.OPEN.getId();
    private static final int UPDATED = ResolutionStatus.UPDATED.getId();
    private static final int PROPOSED = ResolutionStatus.RESOLVED.getId();
    private static final int CLOSED = ResolutionStatus.CLOSED.getId();
    private static final int NOT_APPLICABLE = ResolutionStatus.NOT_APPLICABLE.getId();

    private static final int FAILED_CHECK = DiscrepancyNoteType.FAILEDVAL.getId();
    private static final int ANNOTATION = DiscrepancyNoteType.ANNOTATION.getId();
    private static final int QUERY = DiscrepancyNoteType.QUERY.getId();
    private static final int REASON_FOR_CHANGE = DiscrepancyNoteType.REASON_FOR_CHANGE.getId();

    private static final List<Role> ENTERING_DATA = List.of(Role.INVESTIGATOR, Role.RESEARCHASSISTANT, Role.RESEARCHASSISTANT2);
    private static final List<Role> MANAGING = List.of(Role.COORDINATOR, Role.STUDYDIRECTOR);

    /** Role names in the messages below come from the terms bundle. */
    @BeforeEach
    void setUp() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    private static DiscrepancyNoteBean thread(int type, int status) {
        DiscrepancyNoteBean note = new DiscrepancyNoteBean();
        note.setId(40);
        note.setDiscrepancyNoteTypeId(type);
        note.setResolutionStatusId(status);
        return note;
    }

    // ---- replies ------------------------------------------------------------------------------

    @Test
    void investigatorsAndResearchAssistantsUpdateOrProposeAResolutionButDoNotClose() {
        for (Role role : ENTERING_DATA) {
            for (int status : new int[] { NEW, UPDATED, PROPOSED }) {
                DiscrepancyNoteBean query = thread(QUERY, status);
                assertTrue(DiscrepancyNoteStatusRule.mayReply(role, query, QUERY, UPDATED), role.getName());
                assertTrue(DiscrepancyNoteStatusRule.mayReply(role, query, QUERY, PROPOSED), role.getName());
                assertFalse(DiscrepancyNoteStatusRule.mayReply(role, query, QUERY, CLOSED), role.getName() + " closes");
                assertFalse(DiscrepancyNoteStatusRule.mayReply(role, query, QUERY, NEW), role.getName() + " reopens");
            }
        }
    }

    @Test
    void investigatorsAndResearchAssistantsDoNotReplyToAClosedThread() {
        for (Role role : ENTERING_DATA) {
            assertFalse(DiscrepancyNoteStatusRule.mayReply(role, thread(QUERY, CLOSED), QUERY, UPDATED), role.getName());
            assertFalse(DiscrepancyNoteStatusRule.mayReply(role, thread(QUERY, CLOSED), QUERY, PROPOSED), role.getName());
        }
    }

    @Test
    void aMonitorClosesReopensOrUpdatesButDoesNotProposeAResolution() {
        for (int status : new int[] { NEW, UPDATED, PROPOSED, CLOSED }) {
            DiscrepancyNoteBean query = thread(QUERY, status);
            assertTrue(DiscrepancyNoteStatusRule.mayReply(Role.MONITOR, query, QUERY, CLOSED));
            assertTrue(DiscrepancyNoteStatusRule.mayReply(Role.MONITOR, query, QUERY, NEW));
            assertTrue(DiscrepancyNoteStatusRule.mayReply(Role.MONITOR, query, QUERY, UPDATED));
            assertFalse(DiscrepancyNoteStatusRule.mayReply(Role.MONITOR, query, QUERY, PROPOSED));
        }
    }

    @Test
    void aCoordinatorOrDirectorSetsAnyStatusButNotApplicable() {
        for (Role role : MANAGING) {
            for (int status : new int[] { NEW, UPDATED, PROPOSED, CLOSED }) {
                DiscrepancyNoteBean query = thread(QUERY, status);
                for (int next : new int[] { NEW, UPDATED, PROPOSED, CLOSED }) {
                    assertTrue(DiscrepancyNoteStatusRule.mayReply(role, query, QUERY, next), role.getName());
                }
                assertFalse(DiscrepancyNoteStatusRule.mayReply(role, query, QUERY, NOT_APPLICABLE), role.getName());
            }
        }
    }

    @Test
    void noOneRepliesToANotApplicableThreadOrOneThatDoesNotExist() {
        for (Role role : List.of(Role.INVESTIGATOR, Role.MONITOR, Role.STUDYDIRECTOR)) {
            assertFalse(DiscrepancyNoteStatusRule.mayReply(role, thread(ANNOTATION, NOT_APPLICABLE), ANNOTATION, NOT_APPLICABLE));
            assertFalse(DiscrepancyNoteStatusRule.mayReply(role, new DiscrepancyNoteBean(), QUERY, UPDATED));
        }
    }

    @Test
    void aReplyGoesUnderTheThreadsParentNoteOnly() {
        // A thread's first reply keeps the status the thread started in, and a
        // reply that named it as the parent would change it.
        DiscrepancyNoteBean firstReply = thread(QUERY, NEW);
        firstReply.setParentDnId(39);
        assertFalse(DiscrepancyNoteStatusRule.mayReply(Role.STUDYDIRECTOR, firstReply, QUERY, CLOSED));
        assertFalse(DiscrepancyNoteStatusRule.mayReply(Role.MONITOR, firstReply, QUERY, CLOSED));
        assertTrue(DiscrepancyNoteStatusRule.mayReply(Role.STUDYDIRECTOR, thread(QUERY, NEW), QUERY, CLOSED));
    }

    @Test
    void aReplyKeepsItsThreadsType() {
        // An annotation or a reason for change is saved "not applicable", and
        // turns its thread so: a query answered as one would leave the page's rule.
        assertFalse(DiscrepancyNoteStatusRule.mayReply(Role.STUDYDIRECTOR, thread(QUERY, UPDATED), ANNOTATION, UPDATED));
        assertFalse(DiscrepancyNoteStatusRule.mayReply(Role.STUDYDIRECTOR, thread(QUERY, UPDATED), REASON_FOR_CHANGE, UPDATED));
        assertFalse(DiscrepancyNoteStatusRule.mayReply(Role.MONITOR, thread(FAILED_CHECK, UPDATED), QUERY, CLOSED));
        assertTrue(DiscrepancyNoteStatusRule.mayReply(Role.MONITOR, thread(FAILED_CHECK, UPDATED), FAILED_CHECK, CLOSED));
    }

    @Test
    void aReplyToAnAnnotationThreadThatIsStillOpenIsSavedNotApplicable() {
        // The page shows the reply box for such a thread, and the note is saved
        // "not applicable" whatever status was chosen.
        assertTrue(DiscrepancyNoteStatusRule.mayReply(Role.INVESTIGATOR, thread(ANNOTATION, NEW), ANNOTATION, UPDATED));
        assertFalse(DiscrepancyNoteStatusRule.mayReply(Role.INVESTIGATOR, thread(ANNOTATION, CLOSED), ANNOTATION, UPDATED));
    }

    @Test
    void rolesWithoutTheNotePageGetNothing() {
        for (Role role : List.of(Role.ADMIN, Role.INVALID)) {
            assertEquals(List.of(), DiscrepancyNoteStatusRule.replyStatuses(role));
            assertFalse(DiscrepancyNoteStatusRule.mayReply(role, thread(QUERY, UPDATED), QUERY, UPDATED));
            assertFalse(DiscrepancyNoteStatusRule.mayStart(role, ANNOTATION, NOT_APPLICABLE));
        }
    }

    // ---- new threads --------------------------------------------------------------------------

    @Test
    void investigatorsAndResearchAssistantsStartFailedChecksAnnotationsAndReasonsForChange() {
        for (Role role : ENTERING_DATA) {
            assertTrue(DiscrepancyNoteStatusRule.mayStart(role, FAILED_CHECK, NEW), role.getName());
            assertTrue(DiscrepancyNoteStatusRule.mayStart(role, FAILED_CHECK, PROPOSED), role.getName());
            assertFalse(DiscrepancyNoteStatusRule.mayStart(role, FAILED_CHECK, CLOSED), role.getName());
            assertTrue(DiscrepancyNoteStatusRule.mayStart(role, ANNOTATION, 0), role.getName());
            assertTrue(DiscrepancyNoteStatusRule.mayStart(role, REASON_FOR_CHANGE, NEW), role.getName());
            for (int status : new int[] { NEW, UPDATED, PROPOSED, CLOSED }) {
                assertFalse(DiscrepancyNoteStatusRule.mayStart(role, QUERY, status), role.getName() + " starts a query");
            }
        }
    }

    @Test
    void aMonitorStartsQueriesOnly() {
        assertTrue(DiscrepancyNoteStatusRule.mayStart(Role.MONITOR, QUERY, NEW));
        assertTrue(DiscrepancyNoteStatusRule.mayStart(Role.MONITOR, QUERY, UPDATED));
        assertTrue(DiscrepancyNoteStatusRule.mayStart(Role.MONITOR, QUERY, CLOSED));
        assertFalse(DiscrepancyNoteStatusRule.mayStart(Role.MONITOR, QUERY, PROPOSED));
        assertFalse(DiscrepancyNoteStatusRule.mayStart(Role.MONITOR, FAILED_CHECK, NEW));
        assertFalse(DiscrepancyNoteStatusRule.mayStart(Role.MONITOR, ANNOTATION, NOT_APPLICABLE));
        assertFalse(DiscrepancyNoteStatusRule.mayStart(Role.MONITOR, REASON_FOR_CHANGE, NOT_APPLICABLE));
    }

    @Test
    void aCoordinatorOrDirectorStartsEveryType() {
        for (Role role : MANAGING) {
            for (int status : new int[] { NEW, UPDATED, PROPOSED, CLOSED }) {
                assertTrue(DiscrepancyNoteStatusRule.mayStart(role, QUERY, status), role.getName());
            }
            assertTrue(DiscrepancyNoteStatusRule.mayStart(role, FAILED_CHECK, NEW), role.getName());
            assertTrue(DiscrepancyNoteStatusRule.mayStart(role, FAILED_CHECK, PROPOSED), role.getName());
            assertFalse(DiscrepancyNoteStatusRule.mayStart(role, FAILED_CHECK, CLOSED), role.getName());
            assertTrue(DiscrepancyNoteStatusRule.mayStart(role, ANNOTATION, NOT_APPLICABLE), role.getName());
            assertTrue(DiscrepancyNoteStatusRule.mayStart(role, REASON_FOR_CHANGE, NOT_APPLICABLE), role.getName());
        }
    }

    @Test
    void aQueryOrFailedCheckDoesNotStartNotApplicableOrWithoutAStatus() {
        assertFalse(DiscrepancyNoteStatusRule.mayStart(Role.STUDYDIRECTOR, QUERY, NOT_APPLICABLE));
        assertFalse(DiscrepancyNoteStatusRule.mayStart(Role.STUDYDIRECTOR, QUERY, 0));
        assertFalse(DiscrepancyNoteStatusRule.mayStart(Role.INVESTIGATOR, FAILED_CHECK, 0));
        assertFalse(DiscrepancyNoteStatusRule.mayStart(Role.STUDYDIRECTOR, 0, NEW), "no type");
    }
}
