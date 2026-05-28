/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.it;

import java.util.ArrayList;

import javax.sql.DataSource;

import org.akaza.openclinica.bean.core.Status;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.dao.managestudy.StudyDAO;
import org.akaza.openclinica.templates.HibernateOcDbTestCase;
import org.dbunit.operation.DatabaseOperation;

/**
 * Phase 0 integration-test backlog (MIGRATION.md items 4 + 5):
 * institutional regression net for the study CRUD path.
 *
 * <p>Pins two contracts the production study-management UI relies on:
 * <ol>
 *   <li><strong>{@link StudyDAO#create} + {@link StudyDAO#findByPK} round-trip
 *       the canonical fields.</strong> Every "create a study" form in the
 *       admin UI flows through here. A drift in the column-to-bean mapping
 *       would corrupt every newly-created study silently.</li>
 *   <li><strong>{@link StudyDAO#findAllByParent} returns the right sites.</strong>
 *       The site/parent-study hierarchy is the foundation of LibreClinica's
 *       multi-site model: a parent "Study" has zero or more "Site" children,
 *       and the production code uses {@code findAllByParent} on every
 *       Manage Sites screen + every cross-site report. Wrong children =
 *       wrong reports.</li>
 * </ol>
 *
 * <p><strong>Phase B.5 gate:</strong> these tests must keep passing through
 * the Hibernate 5.6 → 6.4 migration. StudyDAO is a hand-rolled JDBC DAO
 * (not a Hibernate-managed bean), so the Hibernate jump shouldn't break it
 * directly — but the underlying connection pool + transaction manager
 * configuration changes in B.5 could.
 */
public class StudyCrudIT extends HibernateOcDbTestCase {

    public StudyCrudIT() {
        super();
    }

    /**
     * REFRESH (not the default CLEAN_INSERT) — same reason as in
     * {@code LoginFlowIT}: the study table has incoming FKs from many other
     * tables (study_event, study_subject, audit_log_event, …), so DELETE
     * fails on bootstrap rows. INSERT-for-new-PKs + UPDATE-for-existing
     * is the safe combination.
     */
    @Override
    protected DatabaseOperation getSetUpOperation() {
        return DatabaseOperation.REFRESH;
    }

    @Override
    protected DatabaseOperation getTearDownOperation() {
        return DatabaseOperation.NONE;
    }

    /**
     * Item 4: persist a study via {@code create}, retrieve it via
     * {@code findByPK}, assert the round-trip preserves the canonical
     * fields. The DBUnit fixture is empty for this test; the study is
     * built in-memory and persisted via the production DAO so the
     * full {@code createStepOne} → {@code createStepFour} cascade is
     * exercised.
     *
     * <p>Caveat: the Spring per-test transaction rolls back at tearDown
     * (per {@code HibernateOcDbTestCase}), but {@code StudyDAO} uses a
     * JDBC {@code DataSource} directly rather than the Hibernate-managed
     * session. So the inserts here commit to the database. The negative-id
     * keyspace ({@code -101…}) is reserved for IT fixture data and avoids
     * collision with the bootstrap-row positive-id space.
     */
    public void testCreateAndRetrieveStudyRoundTrip() {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        StudyDAO studyDao = new StudyDAO(dataSource);

        StudyBean sb = new StudyBean();
        sb.setName("MUW Study Crud IT Round Trip");
        sb.setIdentifier("MUW_STUDY_CRUD_IT_RT");
        sb.setOfficialTitle("Round-trip test fixture");
        sb.setStatus(Status.AVAILABLE);
        sb.setOwnerId(1);

        StudyBean created = studyDao.create(sb);

        assertTrue("create() must assign a positive primary key",
                created.getId() > 0);
        StudyBean roundTripped = studyDao.findByPK(created.getId());
        assertNotNull("findByPK must round-trip the just-created study",
                roundTripped);
        assertEquals("name round-trips",
                "MUW Study Crud IT Round Trip", roundTripped.getName());
        assertEquals("identifier round-trips",
                "MUW_STUDY_CRUD_IT_RT", roundTripped.getIdentifier());
        assertEquals("officialTitle round-trips",
                "Round-trip test fixture", roundTripped.getOfficialTitle());
    }

    /**
     * Item 5: persist a parent study + two sites referencing the parent,
     * assert {@link StudyDAO#findAllByParent} returns both children.
     *
     * <p>Pins the multi-site hierarchy invariant — the very foundation of
     * LibreClinica's site-based access control. If {@code findAllByParent}
     * regresses to returning {@code null}, empty, or the wrong children,
     * every monitor's site-scoped view of subject data breaks at once.
     */
    public void testFindAllByParentReturnsChildSites() {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        StudyDAO studyDao = new StudyDAO(dataSource);

        StudyBean parent = new StudyBean();
        parent.setName("MUW Study Crud IT Parent");
        parent.setIdentifier("MUW_STUDY_CRUD_IT_PARENT");
        parent.setStatus(Status.AVAILABLE);
        parent.setOwnerId(1);
        parent = studyDao.create(parent);
        assertTrue("parent.create() must assign a positive PK",
                parent.getId() > 0);

        StudyBean siteA = new StudyBean();
        siteA.setName("MUW Study Crud IT Site A");
        siteA.setIdentifier("MUW_STUDY_CRUD_IT_SITE_A");
        siteA.setParentStudyId(parent.getId());
        siteA.setStatus(Status.AVAILABLE);
        siteA.setOwnerId(1);
        siteA = studyDao.create(siteA);

        StudyBean siteB = new StudyBean();
        siteB.setName("MUW Study Crud IT Site B");
        siteB.setIdentifier("MUW_STUDY_CRUD_IT_SITE_B");
        siteB.setParentStudyId(parent.getId());
        siteB.setStatus(Status.AVAILABLE);
        siteB.setOwnerId(1);
        siteB = studyDao.create(siteB);

        ArrayList<StudyBean> children = studyDao.findAllByParent(parent.getId());

        assertNotNull("findAllByParent must never return null",
                children);
        assertEquals("parent has exactly 2 child sites",
                2, children.size());
        boolean foundA = false;
        boolean foundB = false;
        for (StudyBean child : children) {
            assertEquals("each child carries the correct parentStudyId",
                    parent.getId(), child.getParentStudyId());
            if ("MUW_STUDY_CRUD_IT_SITE_A".equals(child.getIdentifier())) {
                foundA = true;
            }
            if ("MUW_STUDY_CRUD_IT_SITE_B".equals(child.getIdentifier())) {
                foundB = true;
            }
        }
        assertTrue("site A is among the children", foundA);
        assertTrue("site B is among the children", foundB);
    }
}
