/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.DatabaseChangeLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.DatabaseChangeLogBean;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;

public class DatabaseChangeLogDaoTest extends HibernateOcDbTestCase {

    public DatabaseChangeLogDaoTest() {
        super();
    }

   /* public void testCount() {
        DatabaseChangeLogDao databaseChangeLogDao = (DatabaseChangeLogDao) getContext().getBean("databaseChangeLogDao");
        Long count = databaseChangeLogDao.count();

        if (getDbName().equals("postgres")) {
            assertEquals("Total Count should be", String.valueOf(POSTGRES_COUNT), String.valueOf(count));
        }

    }

    public void testfindAll() {
        DatabaseChangeLogDao databaseChangeLogDao = (DatabaseChangeLogDao) getContext().getBean("databaseChangeLogDao");
        DatabaseChangeLogBean databaseChangeLogBean = null;
        ArrayList<DatabaseChangeLogBean> databaseChangeLogBeans = databaseChangeLogDao.findAll();
        databaseChangeLogBean = databaseChangeLogBeans.get(0);

        if (getDbName().equals("postgres")) {
            assertEquals("Total Count should be", String.valueOf(POSTGRES_COUNT), String.valueOf(databaseChangeLogBeans.size()));
        }
        
        assertNotNull(databaseChangeLogBean);

    }
*/
    public void testfindById() {
        DatabaseChangeLogDao databaseChangeLogDao = (DatabaseChangeLogDao) getContext().getBean("databaseChangeLogDao");
        DatabaseChangeLogBean databaseChangeLogBean = null;
        databaseChangeLogBean = databaseChangeLogDao.findById("1235684743487-1", "pgawade (generated)", "migration/2.5/changeLogCreateTables.xml");

        assertNotNull(databaseChangeLogBean);
        assertEquals("Author should be pgawade (generated)", "pgawade (generated)", databaseChangeLogBean.getAuthor());

    }

}