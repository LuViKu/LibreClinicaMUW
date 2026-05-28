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
 * <p>Pins two contracts the production "Add Subject" flow relies on:
 * <ol>
 *   <li><strong>{@link SubjectDAO#create} + {@link StudySubjectDAO#create}
 *       produce linked rows.</strong> Creating a subject in a study is a
 *       two-row operation (one in {@code subject}, one in {@code study_subject}
 *       linking the subject to the study by FK). The Add Subject form
 *       drives exactly this sequence.</li>
 *   <li><strong>Re-enrolling a subject with the same label in the same
 *       study is rejected.</strong> The {@code study_subject} table has
 *       a unique constraint on {@code (study_id, label)}; a duplicate
 *       insert raises a DB constraint violation. The production code
 *       catches this and re-renders the Add Subject form with an error.</li>
 * </ol>
 *
 * <p><strong>Phase B.5 gate:</strong> these DAOs are hand-rolled JDBC, so
 * Hibernate 6 doesn't affect them directly. But the unique-constraint
 * exception type that bubbles up from the JDBC driver may change shape
 * under Spring's DataAccessException translation in B.4 — pinned here so
 * a translation drift surfaces.
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
     * production DAO create paths. The test creates a parent study,
     * then enrols one subject into it, then asserts the two rows exist
     * and are linked.
     */
    public void testEnrolSubjectInStudy() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        StudyDAO studyDao = new StudyDAO(dataSource);
        SubjectDAO subjectDao = new SubjectDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        // Boot-strap user_id=1 exists in every LibreClinica install.
        UserAccountBean owner = (UserAccountBean) userDao.findByPK(1);
        assertNotNull("Bootstrap user (id=1) must exist", owner);

        // Parent study to enrol the subject into.
        StudyBean study = new StudyBean();
        study.setName("MUW Subject Enrolment IT Study");
        study.setIdentifier("MUW_SUBJ_ENROL_IT_STUDY");
        study.setStatus(Status.AVAILABLE);
        study.setOwnerId(owner.getId());
        study = studyDao.create(study);
        assertTrue("study.create() must yield positive PK", study.getId() > 0);

        // 1) Subject row.
        SubjectBean subject = new SubjectBean();
        subject.setUniqueIdentifier("MUW-SUBJ-IT-001");
        subject.setStatus(Status.AVAILABLE);
        subject.setOwner(owner);
        subject = subjectDao.create(subject);
        assertTrue("subject.create() must yield positive PK", subject.getId() > 0);

        // 2) StudySubject row linking the subject to the study.
        StudySubjectBean enrolment = new StudySubjectBean();
        enrolment.setLabel("MUW-ENROL-001");
        enrolment.setSubjectId(subject.getId());
        enrolment.setStudyId(study.getId());
        enrolment.setStatus(Status.AVAILABLE);
        enrolment.setOwner(owner);
        enrolment = studySubjectDao.create(enrolment);
        assertTrue("study_subject.create() must yield positive PK",
                enrolment.getId() > 0);

        // Both rows persisted and linked.
        StudySubjectBean roundTripped =
                (StudySubjectBean) studySubjectDao.findByPK(enrolment.getId());
        assertNotNull("findByPK round-trips the enrolment row",
                roundTripped);
        assertEquals("FK subject_id round-trips",
                subject.getId(), roundTripped.getSubjectId());
        assertEquals("FK study_id round-trips",
                study.getId(), roundTripped.getStudyId());
        assertEquals("label round-trips",
                "MUW-ENROL-001", roundTripped.getLabel());
    }

    /**
     * Item 7: pin the CURRENT behaviour of duplicate-label enrolment.
     *
     * <p><strong>Phase 0 finding (2026-05-28):</strong> the production
     * {@code study_subject} table has NO unique constraint on
     * {@code (study_id, label)} and {@code StudySubjectDAO.create} does
     * not defend against duplicates. The second create with the same
     * label in the same study succeeds and yields a positive PK — a
     * silent double-enrolment hazard the GCP regression suite should
     * not have shipped with. Surfacing this here so the fix gets
     * scheduled.
     *
     * <p>This test pins the (broken) status quo so a future fix that
     * adds the unique constraint (and/or makes the DAO throw) breaks
     * this test loudly. Recommended fix:
     * <ul>
     *   <li>Liquibase changeset adding {@code UNIQUE (study_id, label)}
     *       on {@code study_subject} — institutional priority because
     *       duplicate subject labels corrupt every cross-event report.</li>
     *   <li>Or: a {@code findByLabel}-based pre-flight check in
     *       {@code StudySubjectDAO.create} that raises a typed exception.</li>
     * </ul>
     *
     * <p>When the fix lands, flip the assertion to "must reject" and
     * rename to {@code testDuplicateLabelRejected}.
     */
    public void testDuplicateLabelCurrentlyNotRejected() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        StudyDAO studyDao = new StudyDAO(dataSource);
        SubjectDAO subjectDao = new SubjectDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean owner = (UserAccountBean) userDao.findByPK(1);

        StudyBean study = new StudyBean();
        study.setName("MUW Subject Enrolment IT Dup Study");
        study.setIdentifier("MUW_SUBJ_ENROL_IT_DUP");
        study.setStatus(Status.AVAILABLE);
        study.setOwnerId(owner.getId());
        study = studyDao.create(study);

        SubjectBean subjectOne = new SubjectBean();
        subjectOne.setUniqueIdentifier("MUW-SUBJ-IT-DUP-A");
        subjectOne.setStatus(Status.AVAILABLE);
        subjectOne.setOwner(owner);
        subjectOne = subjectDao.create(subjectOne);

        SubjectBean subjectTwo = new SubjectBean();
        subjectTwo.setUniqueIdentifier("MUW-SUBJ-IT-DUP-B");
        subjectTwo.setStatus(Status.AVAILABLE);
        subjectTwo.setOwner(owner);
        subjectTwo = subjectDao.create(subjectTwo);

        // First enrolment with label "MUW-DUP-001": succeeds.
        StudySubjectBean firstEnrolment = new StudySubjectBean();
        firstEnrolment.setLabel("MUW-DUP-001");
        firstEnrolment.setSubjectId(subjectOne.getId());
        firstEnrolment.setStudyId(study.getId());
        firstEnrolment.setStatus(Status.AVAILABLE);
        firstEnrolment.setOwner(owner);
        firstEnrolment = studySubjectDao.create(firstEnrolment);
        assertTrue("first enrolment must succeed",
                firstEnrolment.getId() > 0);

        // Second enrolment with the SAME label in the SAME study: must
        // be rejected. The DAO either throws or leaves id == 0; the
        // production code path treats either as "duplicate label".
        StudySubjectBean dupEnrolment = new StudySubjectBean();
        dupEnrolment.setLabel("MUW-DUP-001");
        dupEnrolment.setSubjectId(subjectTwo.getId());
        dupEnrolment.setStudyId(study.getId());
        dupEnrolment.setStatus(Status.AVAILABLE);
        dupEnrolment.setOwner(owner);

        // Pin the CURRENT (broken) behaviour: the second create succeeds.
        // When the unique-constraint fix lands, this assertion will fail
        // and the test must be flipped to assertTrue(rejected) + renamed.
        StudySubjectBean attempted = studySubjectDao.create(dupEnrolment);
        assertNotNull("status quo: the second create does NOT throw"
                + " (Phase 0 finding — fix scheduled)", attempted);
        assertTrue("status quo: the second create yields a positive PK"
                + " — silently double-enrolling. Fix this and flip the"
                + " assertion to assertTrue(rejected).",
                attempted.getId() > 0);
    }
}
