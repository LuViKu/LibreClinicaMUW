/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.it;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.dbunit.operation.DatabaseOperation;

/**
 * Phase 0 integration-test backlog (MIGRATION.md items 8 + 9):
 * institutional regression net for the study-event scheduling +
 * status-transition path.
 *
 * <p>See {@link SubjectEnrolmentIT}'s class-Javadoc for the
 * VARCHAR(30) overflow root cause behind the earlier CI flakes —
 * the same lesson applies here: keep every persisted string &le; 30
 * chars, and reload via {@code findByPK} after multi-step DAO creates
 * so silent INSERT failures surface immediately.
 */
public class StudyEventScheduleIT extends HibernateOcDbTestCase {

    public StudyEventScheduleIT() {
        super();
    }

    @Override
    protected DatabaseOperation getSetUpOperation() {
        return DatabaseOperation.REFRESH;
    }

    @Override
    protected DatabaseOperation getTearDownOperation() {
        return DatabaseOperation.NONE;
    }

    /**
     * Item 8: schedule a study event and assert the resulting
     * {@code study_event} row carries {@code subject_event_status =
     * SCHEDULED}. Setup chain: Study → StudyEventDefinition →
     * Subject + StudySubject → StudyEvent.
     */
    public void testScheduleEvent() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        SequenceUtil.bumpAll(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        SubjectDAO subjectDao = new SubjectDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        StudyEventDAO eventDao = new StudyEventDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean owner = (UserAccountBean) userDao.findByPK(1);
        assertTrue("bootstrap owner.id must be > 0", owner.getId() > 0);

        // 1) Study.
        StudyBean study = new StudyBean();
        study.setName("MUW SE Sched Study");           // 18 chars
        study.setIdentifier("MUW_SE_SCHED_STUDY");     // 18 chars
        study.setStatus(Status.AVAILABLE);
        study.setOwnerId(owner.getId());
        study = studyDao.create(study);
        assertTrue("study.create() must yield positive PK (failureDetails="
                + studyDao.getFailureDetails() + ")",
                study.getId() > 0);
        assertTrue("study row must exist after create",
                studyDao.findByPK(study.getId()).getId() > 0);

        // 2) Study event definition.
        StudyEventDefinitionBean sed = new StudyEventDefinitionBean();
        sed.setName("MUW SE Visit 1");
        sed.setStudyId(study.getId());
        sed.setDescription("Round-trip schedule test");
        sed.setRepeating(false);
        sed.setType("scheduled");
        sed.setCategory("Visit");
        sed.setStatus(Status.AVAILABLE);
        sed.setOwnerId(owner.getId());
        sed.setOrdinal(1);
        sed = sedDao.create(sed);
        assertTrue("sed.create() must yield positive PK", sed.getId() > 0);

        // 3) Subject + study_subject.
        SubjectBean subject = new SubjectBean();
        subject.setUniqueIdentifier("MUW-SE-001");
        subject.setStatus(Status.AVAILABLE);
        subject.setOwner(owner);
        subject = subjectDao.create(subject);
        assertTrue("subject.create() must yield positive PK", subject.getId() > 0);

        StudySubjectBean enrolment = new StudySubjectBean();
        enrolment.setLabel("MUW-SE-ENROL-001");
        enrolment.setSubjectId(subject.getId());
        enrolment.setStudyId(study.getId());
        enrolment.setStatus(Status.AVAILABLE);
        enrolment.setOwner(owner);
        enrolment = studySubjectDao.create(enrolment);
        assertTrue("enrolment.create() must yield positive PK"
                + " (failureDetails=" + studySubjectDao.getFailureDetails() + ")",
                enrolment.getId() > 0);

        // 4) Schedule the event.
        StudyEventBean event = new StudyEventBean();
        event.setStudyEventDefinitionId(sed.getId());
        event.setStudySubjectId(enrolment.getId());
        event.setSampleOrdinal(1);
        event.setStatus(Status.AVAILABLE);
        event.setOwner(owner);
        event.setSubjectEventStatus(SubjectEventStatus.SCHEDULED);
        event.setDateStarted(new Date());
        event = eventDao.create(event);

        assertTrue("study_event.create() must yield positive PK"
                + " (failureDetails=" + eventDao.getFailureDetails() + ")",
                event.getId() > 0);
        StudyEventBean roundTripped =
                (StudyEventBean) eventDao.findByPK(event.getId());
        assertNotNull("findByPK must round-trip the scheduled event",
                roundTripped);
        assertEquals("subject_event_status persists as SCHEDULED",
                SubjectEventStatus.SCHEDULED, roundTripped.getSubjectEventStatus());
        assertEquals("study_event_definition_id round-trips",
                sed.getId(), roundTripped.getStudyEventDefinitionId());
        assertEquals("study_subject_id round-trips",
                enrolment.getId(), roundTripped.getStudySubjectId());
    }

    /**
     * Item 9: drive a scheduled event through the canonical status
     * lifecycle SCHEDULED → DATA_ENTRY_STARTED → COMPLETED → LOCKED
     * → SIGNED. Each transition must persist via update().
     */
    public void testEventStatusTransitions() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        SequenceUtil.bumpAll(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        SubjectDAO subjectDao = new SubjectDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        StudyEventDAO eventDao = new StudyEventDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean owner = (UserAccountBean) userDao.findByPK(1);

        StudyBean study = new StudyBean();
        study.setName("MUW SE Trans Study");           // 18 chars
        study.setIdentifier("MUW_SE_TRANS_STUDY");     // 19 chars
        study.setStatus(Status.AVAILABLE);
        study.setOwnerId(owner.getId());
        study = studyDao.create(study);
        assertTrue("study.create() must yield positive PK (failureDetails="
                + studyDao.getFailureDetails() + ")",
                study.getId() > 0);
        assertTrue("study row must exist after create",
                studyDao.findByPK(study.getId()).getId() > 0);

        StudyEventDefinitionBean sed = new StudyEventDefinitionBean();
        sed.setName("MUW SE Visit 2");
        sed.setStudyId(study.getId());
        sed.setDescription("Transition test");
        sed.setRepeating(false);
        sed.setType("scheduled");
        sed.setCategory("Visit");
        sed.setStatus(Status.AVAILABLE);
        sed.setOwnerId(owner.getId());
        sed.setOrdinal(1);
        sed = sedDao.create(sed);
        assertTrue("sed.create() must yield positive PK", sed.getId() > 0);

        SubjectBean subject = new SubjectBean();
        subject.setUniqueIdentifier("MUW-SE-002");
        subject.setStatus(Status.AVAILABLE);
        subject.setOwner(owner);
        subject = subjectDao.create(subject);
        assertTrue("subject.create() must yield positive PK", subject.getId() > 0);

        StudySubjectBean enrolment = new StudySubjectBean();
        enrolment.setLabel("MUW-SE-ENROL-002");
        enrolment.setSubjectId(subject.getId());
        enrolment.setStudyId(study.getId());
        enrolment.setStatus(Status.AVAILABLE);
        enrolment.setOwner(owner);
        enrolment = studySubjectDao.create(enrolment);
        assertTrue("enrolment.create() must yield positive PK",
                enrolment.getId() > 0);

        StudyEventBean event = new StudyEventBean();
        event.setStudyEventDefinitionId(sed.getId());
        event.setStudySubjectId(enrolment.getId());
        event.setSampleOrdinal(1);
        event.setStatus(Status.AVAILABLE);
        event.setOwner(owner);
        event.setSubjectEventStatus(SubjectEventStatus.SCHEDULED);
        // dateStarted is required by StudyEventDAO.update (eager .getTime())
        event.setDateStarted(new Date());
        event = eventDao.create(event);
        assertTrue("scheduled event must have positive PK",
                event.getId() > 0);
        int eventId = event.getId();

        // Drive the lifecycle. Each update() must persist; reload via
        // findByPK to bypass any in-memory caching the bean might do.
        SubjectEventStatus[] sequence = {
                SubjectEventStatus.DATA_ENTRY_STARTED,
                SubjectEventStatus.COMPLETED,
                SubjectEventStatus.LOCKED,
                SubjectEventStatus.SIGNED,
        };
        for (SubjectEventStatus target : sequence) {
            StudyEventBean current = (StudyEventBean) eventDao.findByPK(eventId);
            current.setSubjectEventStatus(target);
            current.setUpdater(owner);
            eventDao.update(current);

            StudyEventBean reloaded = (StudyEventBean) eventDao.findByPK(eventId);
            assertEquals("transition to " + target.getName() + " persisted",
                    target, reloaded.getSubjectEventStatus());
        }
    }

    /**
     * H1 (GA-cohort plan, 2026-05-30): institutional regression net
     * for long-running observational studies where each subject
     * accumulates 10-20 repeating visits over multiple years (MUW
     * Ophthalmology's geographic-atrophy cohort, 6-month follow-up).
     *
     * <p>Creates a {@code StudyEventDefinition} with
     * {@code repeating=true} + 15 {@link StudyEventBean}s for one
     * subject, ordinals 1-15, {@code dateStarted} spaced 6 months
     * apart over a 7-year window. Verifies:
     * <ol>
     *   <li>All 15 ordinals persist (no hidden cap in DAO / schema).</li>
     *   <li>All 15 round-trip via {@code findByPK}.</li>
     *   <li>{@link StudyEventDAO#findAllByDefinitionAndSubjectOrderByOrdinal}
     *       returns them in ordinal order — the listing path the
     *       Subject Matrix UI uses.</li>
     *   <li>{@code dateStarted} is preserved across the date range.</li>
     * </ol>
     *
     * <p>The matching unique-ordinal-per-subject constraint
     * ({@code 3.9/2015-11-12-OC-6820.xml}) is pinned by
     * {@link #testOrdinalUniquenessEnforcedPerSubject()}.
     */
    public void testRepeatingEventScalesTo15Visits() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        SequenceUtil.bumpAll(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        SubjectDAO subjectDao = new SubjectDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        StudyEventDAO eventDao = new StudyEventDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean owner = (UserAccountBean) userDao.findByPK(1);

        // 1) Study + repeating-event definition (the "Visit" SED).
        StudyBean study = new StudyBean();
        study.setName("MUW GA Cohort 15-Visit");        // 22 chars
        study.setIdentifier("MUW_GA_15_VISIT");         // 15 chars
        study.setStatus(Status.AVAILABLE);
        study.setOwnerId(owner.getId());
        study = studyDao.create(study);
        assertTrue("study.create() must yield positive PK", study.getId() > 0);

        StudyEventDefinitionBean sed = new StudyEventDefinitionBean();
        sed.setName("MUW GA Visit");                    // 12 chars
        sed.setStudyId(study.getId());
        sed.setDescription("6-month follow-up visit");
        sed.setRepeating(true);
        sed.setType("scheduled");
        sed.setCategory("Visit");
        sed.setStatus(Status.AVAILABLE);
        sed.setOwnerId(owner.getId());
        sed.setOrdinal(1);
        sed = sedDao.create(sed);
        assertTrue("sed.create() must yield positive PK", sed.getId() > 0);

        // 2) Subject + enrolment.
        SubjectBean subject = new SubjectBean();
        subject.setUniqueIdentifier("MUW-GA-15-001");
        subject.setStatus(Status.AVAILABLE);
        subject.setOwner(owner);
        subject = subjectDao.create(subject);
        assertTrue("subject.create() must yield positive PK", subject.getId() > 0);

        StudySubjectBean enrolment = new StudySubjectBean();
        enrolment.setLabel("GA-15-ENROL-001");
        enrolment.setSubjectId(subject.getId());
        enrolment.setStudyId(study.getId());
        enrolment.setStatus(Status.AVAILABLE);
        enrolment.setOwner(owner);
        enrolment = studySubjectDao.create(enrolment);
        assertTrue("enrolment.create() must yield positive PK", enrolment.getId() > 0);

        // 3) 15 visits spaced 6 months apart, starting 7 years ago.
        final int VISIT_COUNT = 15;
        final int MONTHS_BETWEEN_VISITS = 6;
        Calendar enrolmentCal = Calendar.getInstance();
        enrolmentCal.add(Calendar.YEAR, -7);                 // first visit 7 years ago
        int[] createdEventIds = new int[VISIT_COUNT];
        Date[] expectedDates = new Date[VISIT_COUNT];

        for (int i = 0; i < VISIT_COUNT; i++) {
            Calendar visitCal = (Calendar) enrolmentCal.clone();
            visitCal.add(Calendar.MONTH, i * MONTHS_BETWEEN_VISITS);
            Date visitDate = visitCal.getTime();
            expectedDates[i] = visitDate;

            StudyEventBean event = new StudyEventBean();
            event.setStudyEventDefinitionId(sed.getId());
            event.setStudySubjectId(enrolment.getId());
            event.setSampleOrdinal(i + 1);                   // ordinals 1..15
            event.setStatus(Status.AVAILABLE);
            event.setOwner(owner);
            event.setSubjectEventStatus(SubjectEventStatus.COMPLETED);
            event.setDateStarted(visitDate);
            event = eventDao.create(event);
            assertTrue("visit #" + (i + 1) + " must persist (failureDetails="
                    + eventDao.getFailureDetails() + ")",
                    event.getId() > 0);
            createdEventIds[i] = event.getId();
        }

        // 4) Round-trip every visit via findByPK.
        for (int i = 0; i < VISIT_COUNT; i++) {
            StudyEventBean reloaded = (StudyEventBean) eventDao.findByPK(createdEventIds[i]);
            assertNotNull("findByPK must round-trip visit #" + (i + 1), reloaded);
            assertEquals("visit #" + (i + 1) + " sampleOrdinal round-trips",
                    Integer.valueOf(i + 1), Integer.valueOf(reloaded.getSampleOrdinal()));
            assertEquals("visit #" + (i + 1) + " definition_id round-trips",
                    sed.getId(), reloaded.getStudyEventDefinitionId());
            assertEquals("visit #" + (i + 1) + " study_subject_id round-trips",
                    enrolment.getId(), reloaded.getStudySubjectId());
        }

        // 5) Listing path: 15 events come back in ordinal order.
        ArrayList<StudyEventBean> listed =
                eventDao.findAllByDefinitionAndSubjectOrderByOrdinal(sed, enrolment);
        assertEquals("findAllByDefinitionAndSubjectOrderByOrdinal must list all 15 visits",
                VISIT_COUNT, listed.size());
        for (int i = 0; i < VISIT_COUNT; i++) {
            assertEquals("listing entry " + i + " carries ordinal " + (i + 1),
                    Integer.valueOf(i + 1),
                    Integer.valueOf(listed.get(i).getSampleOrdinal()));
        }
    }

    /**
     * H1 (GA-cohort plan, 2026-05-30): pin the unique-ordinal-per-
     * (definition, subject) constraint added in changeset
     * {@code 3.9/2015-11-12-OC-6820.xml}. Attempts to create a second
     * event with the same {@code (study_event_definition_id,
     * study_subject_id, sample_ordinal)} triple; the DAO's
     * {@code create} returns a non-positive id (the upstream pattern
     * for an INSERT failure) and {@code getFailureDetails} surfaces
     * the constraint violation.
     */
    public void testOrdinalUniquenessEnforcedPerSubject() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        SequenceUtil.bumpAll(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        SubjectDAO subjectDao = new SubjectDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        StudyEventDAO eventDao = new StudyEventDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean owner = (UserAccountBean) userDao.findByPK(1);

        StudyBean study = new StudyBean();
        study.setName("MUW GA Uniqueness");             // 16 chars
        study.setIdentifier("MUW_GA_UNIQ");             // 11 chars
        study.setStatus(Status.AVAILABLE);
        study.setOwnerId(owner.getId());
        study = studyDao.create(study);
        assertTrue("study.create() must yield positive PK", study.getId() > 0);

        StudyEventDefinitionBean sed = new StudyEventDefinitionBean();
        sed.setName("MUW GA Visit U");                  // 14 chars
        sed.setStudyId(study.getId());
        sed.setDescription("Ordinal-uniqueness pin");
        sed.setRepeating(true);
        sed.setType("scheduled");
        sed.setCategory("Visit");
        sed.setStatus(Status.AVAILABLE);
        sed.setOwnerId(owner.getId());
        sed.setOrdinal(1);
        sed = sedDao.create(sed);
        assertTrue("sed.create() must yield positive PK", sed.getId() > 0);

        SubjectBean subject = new SubjectBean();
        subject.setUniqueIdentifier("MUW-GA-UNIQ-001");
        subject.setStatus(Status.AVAILABLE);
        subject.setOwner(owner);
        subject = subjectDao.create(subject);
        assertTrue("subject.create() must yield positive PK", subject.getId() > 0);

        StudySubjectBean enrolment = new StudySubjectBean();
        enrolment.setLabel("GA-UNIQ-ENROL-001");
        enrolment.setSubjectId(subject.getId());
        enrolment.setStudyId(study.getId());
        enrolment.setStatus(Status.AVAILABLE);
        enrolment.setOwner(owner);
        enrolment = studySubjectDao.create(enrolment);
        assertTrue("enrolment.create() must yield positive PK", enrolment.getId() > 0);

        // First visit at ordinal=1 — must succeed.
        StudyEventBean first = new StudyEventBean();
        first.setStudyEventDefinitionId(sed.getId());
        first.setStudySubjectId(enrolment.getId());
        first.setSampleOrdinal(1);
        first.setStatus(Status.AVAILABLE);
        first.setOwner(owner);
        first.setSubjectEventStatus(SubjectEventStatus.SCHEDULED);
        first.setDateStarted(new Date());
        first = eventDao.create(first);
        assertTrue("first ordinal=1 event must persist", first.getId() > 0);

        // Second visit at SAME ordinal — must be rejected by the
        // unique constraint on (study_event_definition_id,
        // study_subject_id, sample_ordinal).
        StudyEventBean duplicate = new StudyEventBean();
        duplicate.setStudyEventDefinitionId(sed.getId());
        duplicate.setStudySubjectId(enrolment.getId());
        duplicate.setSampleOrdinal(1);                  // same ordinal
        duplicate.setStatus(Status.AVAILABLE);
        duplicate.setOwner(owner);
        duplicate.setSubjectEventStatus(SubjectEventStatus.SCHEDULED);
        duplicate.setDateStarted(new Date());

        boolean rejected;
        try {
            StudyEventBean attempted = eventDao.create(duplicate);
            // The upstream DAO pattern swallows the SQLException and
            // returns a bean with id<=0; check for that path too.
            rejected = (attempted == null || attempted.getId() <= 0);
        } catch (RuntimeException ex) {
            rejected = true;
        }
        assertTrue("duplicate (definition, subject, ordinal=1) MUST be rejected"
                + " by the unique constraint added in changeset"
                + " 3.9/2015-11-12-OC-6820.xml (failureDetails="
                + eventDao.getFailureDetails() + ")",
                rejected);
    }
}
