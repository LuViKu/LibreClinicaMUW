/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.rule;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetAuditDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetAuditBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.hibernate.HibernateException;
import java.util.List;

public class RuleSetAuditDaoTest extends HibernateOcDbTestCase {
    private static RuleSetAuditDao ruleSetAuditDao;
    private static RuleSetDao ruleSetDao;
   
  /*  static
    {
        
        loadProperties();
        dbName = properties.getProperty("dbName");
        dbUrl = properties.getProperty("url");
        dbUserName = properties.getProperty("username");
        dbPassword = properties.getProperty("password");
        dbDriverClassName = properties.getProperty("driver");
        locale = properties.getProperty("locale");
        initializeLocale();
        initializeQueriesInXml();
       
     
        
        context =
            new ClassPathXmlApplicationContext(
                    new String[] { "classpath*:applicationContext-core-s*.xml", "classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-db.xml",
                        "classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-email.xml",
                        "classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-hibernate.xml",
                        "classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-scheduler.xml",
                        "classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-service.xml",
                       " classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-timer.xml",
                        "classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-security.xml" });
      transactionManager = (PlatformTransactionManager) context.getBean("transactionManager");
      transactionManager.getTransaction(new DefaultTransactionDefinition());
        

    }*/
    
    
    public RuleSetAuditDaoTest() {
        super();
         ruleSetAuditDao = (RuleSetAuditDao) getContext().getBean("ruleSetAuditDao");
         ruleSetDao = (RuleSetDao) getContext().getBean("ruleSetDao");
    }

    public void testFindAllByRuleSet() {
      //RuleSetDao ruleSetDao = (RuleSetDao) getContext().getBean("ruleSetDao");
       // RuleSetAuditDao ruleSetAuditDao = (RuleSetAuditDao) getContext().getBean("ruleSetAuditDao");

        RuleSetBean ruleSet = ruleSetDao.findById(-1);
        List<RuleSetAuditBean> ruleSetAudits = ruleSetAuditDao.findAllByRuleSet(ruleSet);

        assertNotNull("ruleSetAudits is null", ruleSetAudits);
        assertEquals("The size of the ruleSetAudits is not 2", new Integer(2), Integer.valueOf(ruleSetAudits.size()));

    }

    public void testFindById() {
       // RuleSetAuditDao ruleSetAuditDao = (RuleSetAuditDao) getContext().getBean("ruleSetAuditDao");

        RuleSetAuditBean ruleSetAuditBean = ruleSetAuditDao.findById(-1);

        assertNotNull("ruleSetRuleAuditBean is null", ruleSetAuditBean);
        assertEquals("The ruleSetRuleAuditBean.getRuleSetRule.getId should be 3", new Integer(-1), Integer.valueOf(ruleSetAuditBean.getRuleSetBean().getId()));

    }

    public void testSaveOrUpdate() {
   //     RuleSetAuditDao ruleSetAuditDao = (RuleSetAuditDao) getContext().getBean("ruleSetAuditDao");
       // RuleSetDao ruleSetDao = (RuleSetDao) getContext().getBean("ruleSetDao");
        RuleSetBean ruleSetBean = ruleSetDao.findById(-1);

        RuleSetAuditBean ruleSetAuditBean = new RuleSetAuditBean();
        ruleSetAuditBean.setRuleSetBean(ruleSetBean);
        ruleSetAuditBean = ruleSetAuditDao.saveOrUpdate(ruleSetAuditBean);

        assertNotNull("Persistant id is null", ruleSetAuditBean.getId());
    }
    public void tearDown(){
        try {
            ruleSetAuditDao.getSessionFactory().getCurrentSession().close();
            ruleSetAuditDao.getSessionFactory().getCurrentSession().close();
        } catch (HibernateException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        super.tearDown();
    }
}