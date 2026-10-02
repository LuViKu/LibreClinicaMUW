/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.CrfBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.CrfVersion;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.Item;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.ItemGroup;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.dbunit.dataset.DefaultDataSet;
import org.dbunit.dataset.IDataSet;
import org.dbunit.operation.DatabaseOperation;

/**
 * {@link AbstractDomainDao#save} returns the id Hibernate generated, also for
 * an entity whose {@code getId()} is not its id.
 *
 * <p>CrfBean, CrfVersion, ItemGroup and Item extend {@code DataMapDomainObject},
 * whose {@code getId()} returns null; each maps its id on a getter of its own
 * ({@code getCrfId()} and so on). XformMetaDataService inserts one of each
 * through save() when an XForm CRF is uploaded, and hands the returned id to
 * that entity's {@code int} id setter, so a null return fails the upload.
 * {@code AuditLoginContractIT} covers the other kind of entity, whose
 * {@code getId()} is the mapped id.
 *
 * <p>Each test runs in the per-test transaction of {@link HibernateOcDbTestCase},
 * which is rolled back, so nothing is left in the database. The id sequences
 * are first moved past the highest existing id: rows seeded with explicit ids
 * by other ITs leave a sequence behind its table, and the generated id would
 * then collide with an existing row.
 */
public class AbstractDomainDaoSaveIT extends HibernateOcDbTestCase {

    @Override
    protected IDataSet getDataSet() {
        return new DefaultDataSet();
    }

    @Override
    protected DatabaseOperation getSetUpOperation() {
        return DatabaseOperation.NONE;
    }

    @Override
    protected DatabaseOperation getTearDownOperation() {
        return DatabaseOperation.NONE;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String[] t : new String[][] {
                    { "crf", "crf_id" },
                    { "crf_version", "crf_version_id" },
                    { "item_group", "item_group_id" },
                    { "item", "item_id" } }) {
                // setval is not transactional, so this holds after the test's rollback.
                st.execute("SELECT setval('" + t[0] + "_" + t[1] + "_seq', "
                        + "GREATEST((SELECT COALESCE(MAX(" + t[1] + "), 0) FROM " + t[0] + "), "
                        + "(SELECT last_value FROM " + t[0] + "_" + t[1] + "_seq)))");
            }
        }
    }

    public void testSaveReturnsTheCrfId() {
        CrfDao crfDao = (CrfDao) getContext().getBean("crfDao");

        CrfBean crf = newCrf("F_MUW_SAVE_IT_CRF");
        Serializable id = crfDao.save(crf);

        assertGeneratedId(id, crf.getCrfId());
        assertEquals("F_MUW_SAVE_IT_CRF", crfDao.findById((Integer) id).getOcOid());
    }

    public void testSaveReturnsTheCrfVersionId() {
        CrfVersionDao crfVersionDao = (CrfVersionDao) getContext().getBean("crfVersionDao");

        CrfVersion version = new CrfVersion();
        version.setCrf(insertedCrf("F_MUW_SAVE_IT_V"));
        version.setName("v1");
        version.setOcOid("F_MUW_SAVE_IT_V_V1");
        version.setStatus(Status.AVAILABLE);
        Serializable id = crfVersionDao.save(version);

        assertGeneratedId(id, version.getCrfVersionId());
        assertEquals("F_MUW_SAVE_IT_V_V1", crfVersionDao.findById((Integer) id).getOcOid());
    }

    public void testSaveReturnsTheItemGroupId() {
        ItemGroupDao itemGroupDao = (ItemGroupDao) getContext().getBean("itemGroupDao");

        ItemGroup group = new ItemGroup();
        group.setCrf(insertedCrf("F_MUW_SAVE_IT_G"));
        group.setName("group");
        group.setOcOid("IG_MUW_SAVE_IT_GROUP");
        group.setStatus(Status.AVAILABLE);
        Serializable id = itemGroupDao.save(group);

        assertGeneratedId(id, group.getItemGroupId());
        assertEquals("IG_MUW_SAVE_IT_GROUP", itemGroupDao.findById((Integer) id).getOcOid());
    }

    public void testSaveReturnsTheItemId() {
        ItemDao itemDao = (ItemDao) getContext().getBean("itemDao");

        Item item = new Item();
        item.setName("I_MUW_SAVE_IT");
        item.setDescription("");
        item.setUnits("");
        item.setPhiStatus(false);
        item.setOcOid("I_MUW_SAVE_IT_ITEM");
        item.setStatus(Status.AVAILABLE);
        Serializable id = itemDao.save(item);

        assertGeneratedId(id, item.getItemId());
        assertEquals("I_MUW_SAVE_IT_ITEM", itemDao.findById((Integer) id).getOcOid());
    }

    /**
     * The CRF a version or a group belongs to, inserted with saveOrUpdate so
     * that only the entity under test goes through save().
     */
    private CrfBean insertedCrf(String oid) {
        CrfDao crfDao = (CrfDao) getContext().getBean("crfDao");
        return crfDao.saveOrUpdate(newCrf(oid));
    }

    private static CrfBean newCrf(String oid) {
        CrfBean crf = new CrfBean();
        crf.setName(oid);
        crf.setOcOid(oid);
        crf.setStatus(Status.AVAILABLE);
        return crf;
    }

    private static void assertGeneratedId(Serializable returned, int mappedId) {
        assertNotNull("save must return the generated id", returned);
        assertTrue("the id must have been generated", mappedId > 0);
        assertEquals("the returned id is the one mapped on the entity",
                Integer.valueOf(mappedId), returned);
    }
}
