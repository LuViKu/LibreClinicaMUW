/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.it;

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
}
