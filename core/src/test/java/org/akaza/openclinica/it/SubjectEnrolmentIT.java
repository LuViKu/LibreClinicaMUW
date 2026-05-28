/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.it;

import javax.sql.DataSource;

import org.akaza.openclinica.bean.core.Status;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.bean.managestudy.StudySubjectBean;
import org.akaza.openclinica.bean.submit.SubjectBean;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.dao.managestudy.StudyDAO;
import org.akaza.openclinica.dao.managestudy.StudySubjectDAO;
import org.akaza.openclinica.dao.submit.SubjectDAO;
import org.akaza.openclinica.templates.HibernateOcDbTestCase;
import org.dbunit.operation.DatabaseOperation;

/**
 * Phase 0 integration-test backlog (MIGRATION.md items 6 + 7):
 * institutional regression net for the subject-enrolment path.
 *
 * <p><strong>Root cause of the earlier CI-only failures (resolved
 * 2026-05-28):</strong> {@code study.name} is VARCHAR(30) and an earlier
 * version of {@code testDuplicateLabelCurrentlyNotRejected} used the name
 * "MUW Subject Enrolment IT Dup Study" (34 chars), which overflowed.
 * Postgres threw {@code value too long for type character varying(30)};
 * {@code StudyDAO.createStepOne} swallowed the exception silently while
 * pre-assigning a positive {@code study_id} via {@code findNextKey()},
 * so the bean looked successfully-created. Subsequent {@code study_subject}
 * inserts then failed with {@code fk_project__reference_study2} (FK to a
 * non-existent study). Locally this masked itself: single-test runs against
 * a fresh postgres + the {@code -Dtest=} entry-point both happened to fit
 * within VARCHAR(30), and the multi-step DAO's later {@code clearSignals}
 * + empty-{@code SQLException} from a 0-rows UPDATE replaced
 * {@code failureDetails} with an empty exception before the test could
 * inspect it.
 *
 * <p>Surfaced via the {@code -Doc.dao.preserveFirstSqlException=true}
 * test-only gate added to {@code EntityDAO} (production behaviour unchanged
 * when the property is unset; set in {@code pom.xml}'s integration-tests
 * profile).
 *
 * <p>Lessons baked into every IT below:
 * <ul>
 *   <li>Keep every persisted string &le; 30 chars to stay inside the
 *       VARCHAR(30) bounds on {@code study.name},
 *       {@code study_subject.label}, and friends.</li>
 *   <li>After {@code studyDao.create(study)} (or any multi-step DAO),
 *       reload via {@code findByPK} and assert the row exists — the bean
 *       can carry a positive id even when the INSERT silently failed.</li>
 * </ul>
 */
public class SubjectEnrolmentIT extends HibernateOcDbTestCase {

    public SubjectEnrolmentIT() {
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
     * Item 6: round-trip a fresh Subject + StudySubject pair via the
     * production DAO create paths.
     */
    public void testEnrolSubjectInStudy() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        SequenceUtil.bumpAll(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        SubjectDAO subjectDao = new SubjectDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean owner = (UserAccountBean) userDao.findByPK(1);
        assertNotNull("Bootstrap user (id=1) must exist", owner);

        StudyBean study = new StudyBean();
        study.setName("MUW Enrol IT Study");          // 18 chars
        study.setIdentifier("MUW_ENROL_IT_STUDY");    // 18 chars
        study.setStatus(Status.AVAILABLE);
        study.setOwnerId(owner.getId());
        study = studyDao.create(study);
        assertTrue("study.create() must yield positive PK (failureDetails="
                + studyDao.getFailureDetails() + ")",
                study.getId() > 0);
        StudyBean reloaded = studyDao.findByPK(study.getId());
        assertTrue("study row must actually exist in DB after create",
                reloaded.getId() > 0);

        SubjectBean subject = new SubjectBean();
        subject.setUniqueIdentifier("MUW-SUBJ-IT-001");
        subject.setStatus(Status.AVAILABLE);
        subject.setOwner(owner);
        subject = subjectDao.create(subject);
        assertTrue("subject.create() must yield positive PK", subject.getId() > 0);

        StudySubjectBean enrolment = new StudySubjectBean();
        enrolment.setLabel("MUW-ENROL-001");
        enrolment.setSubjectId(subject.getId());
        enrolment.setStudyId(study.getId());
        enrolment.setStatus(Status.AVAILABLE);
        enrolment.setOwner(owner);
        enrolment = studySubjectDao.create(enrolment);
        assertTrue("study_subject.create() must yield positive PK"
                + " (failureDetails=" + studySubjectDao.getFailureDetails() + ")",
                enrolment.getId() > 0);

        StudySubjectBean roundTripped =
                (StudySubjectBean) studySubjectDao.findByPK(enrolment.getId());
        assertNotNull("findByPK round-trips the enrolment row", roundTripped);
        assertEquals("FK subject_id round-trips",
                subject.getId(), roundTripped.getSubjectId());
        assertEquals("FK study_id round-trips",
                study.getId(), roundTripped.getStudyId());
        assertEquals("label round-trips",
                "MUW-ENROL-001", roundTripped.getLabel());
    }

    /**
     * Item 7: pin the CURRENT (broken) behaviour — `study_subject` has no
     * UNIQUE on `(study_id, label)`, so a second enrolment with the same
     * label succeeds. When the Liquibase changeset adding the unique
     * constraint lands, flip this to assert rejection + rename to
     * {@code testDuplicateLabelRejected}.
     */
    public void testDuplicateLabelCurrentlyNotRejected() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        SequenceUtil.bumpAll(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        SubjectDAO subjectDao = new SubjectDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean owner = (UserAccountBean) userDao.findByPK(1);

        StudyBean study = new StudyBean();
        study.setName("MUW Enrol Dup Study");         // 19 chars (was 34)
        study.setIdentifier("MUW_ENROL_DUP_STUDY");   // 19 chars
        study.setStatus(Status.AVAILABLE);
        study.setOwnerId(owner.getId());
        study = studyDao.create(study);
        assertTrue("study.create() must yield positive PK (failureDetails="
                + studyDao.getFailureDetails() + ")",
                study.getId() > 0);
        StudyBean reloaded = studyDao.findByPK(study.getId());
        assertTrue("study row must actually exist in DB after create",
                reloaded.getId() > 0);

        SubjectBean subjectOne = new SubjectBean();
        subjectOne.setUniqueIdentifier("MUW-SUBJ-DUP-A");
        subjectOne.setStatus(Status.AVAILABLE);
        subjectOne.setOwner(owner);
        subjectOne = subjectDao.create(subjectOne);
        assertTrue("subjectOne.create() must yield positive PK",
                subjectOne.getId() > 0);

        SubjectBean subjectTwo = new SubjectBean();
        subjectTwo.setUniqueIdentifier("MUW-SUBJ-DUP-B");
        subjectTwo.setStatus(Status.AVAILABLE);
        subjectTwo.setOwner(owner);
        subjectTwo = subjectDao.create(subjectTwo);
        assertTrue("subjectTwo.create() must yield positive PK",
                subjectTwo.getId() > 0);

        StudySubjectBean firstEnrolment = new StudySubjectBean();
        firstEnrolment.setLabel("MUW-DUP-001");
        firstEnrolment.setSubjectId(subjectOne.getId());
        firstEnrolment.setStudyId(study.getId());
        firstEnrolment.setStatus(Status.AVAILABLE);
        firstEnrolment.setOwner(owner);
        firstEnrolment = studySubjectDao.create(firstEnrolment);
        assertTrue("first enrolment must succeed (failureDetails="
                + studySubjectDao.getFailureDetails() + ")",
                firstEnrolment.getId() > 0);

        StudySubjectBean dupEnrolment = new StudySubjectBean();
        dupEnrolment.setLabel("MUW-DUP-001");           // same label
        dupEnrolment.setSubjectId(subjectTwo.getId());
        dupEnrolment.setStudyId(study.getId());
        dupEnrolment.setStatus(Status.AVAILABLE);
        dupEnrolment.setOwner(owner);

        // Pin the CURRENT (broken) behaviour: the second create succeeds.
        // When the unique-constraint fix lands, this assertion will fail
        // and the test must be flipped + renamed.
        StudySubjectBean attempted = studySubjectDao.create(dupEnrolment);
        assertNotNull("status quo: the second create does NOT throw"
                + " (Phase 0 finding — fix scheduled)", attempted);
        assertTrue("status quo: the second create yields a positive PK"
                + " — silently double-enrolling. Fix this and flip the"
                + " assertion to assertTrue(rejected).",
                attempted.getId() > 0);
    }
}
