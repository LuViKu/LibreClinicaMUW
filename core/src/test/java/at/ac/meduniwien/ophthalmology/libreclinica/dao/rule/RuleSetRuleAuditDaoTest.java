/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.rule;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleAuditDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleAuditBean;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.hibernate.CacheMode;
import org.hibernate.HibernateException;
import java.util.List;

public class RuleSetRuleAuditDaoTest extends HibernateOcDbTestCase {
private static RuleSetDao ruleSetDao;
private static  RuleSetRuleAuditDao ruleSetRuleAuditDao;
private static RuleSetRuleDao ruleSetRuleDao;
  
   /* static
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
    @Override
    public void setUp()throws Exception{
        super.setUp();
        ruleSetDao = (RuleSetDao) getContext().getBean("ruleSetDao");
        ruleSetRuleAuditDao = (RuleSetRuleAuditDao) getContext().getBean("ruleSetRuleAuditDao");
        ruleSetRuleDao = (RuleSetRuleDao) getContext().getBean("ruleSetRuleDao");
    }
    public void testFindAllByRuleSet() {
     //   RuleSetDao ruleSetDao = (RuleSetDao) getContext().getBean("ruleSetDao");
     //   RuleSetRuleAuditDao ruleSetRuleAuditDao = (RuleSetRuleAuditDao) getContext().getBean("ruleSetRuleAuditDao");

        RuleSetBean ruleSet = ruleSetDao.findById(-1);
        List<RuleSetRuleAuditBean> ruleSetRuleAudits = ruleSetRuleAuditDao.findAllByRuleSet(ruleSet);

        assertNotNull("ruleSetAudits is null", ruleSetRuleAudits);
        assertEquals("The size of the ruleSetRuleAudits is not 2", new Integer(2), Integer.valueOf(ruleSetRuleAudits.size()));

    }

    public void testFindById() {
      //  RuleSetRuleAuditDao ruleSetRuleAuditDao = (RuleSetRuleAuditDao) getContext().getBean("ruleSetRuleAuditDao");

        RuleSetRuleAuditBean ruleSetRuleAuditBean = ruleSetRuleAuditDao.findById(-1);

        assertNotNull("ruleSetRuleAuditBean is null", ruleSetRuleAuditBean);
        assertEquals("The ruleSetRuleAuditBean.getRuleSetRule.getId should be 3", new Integer(3), Integer.valueOf(ruleSetRuleAuditBean.getRuleSetRuleBean()
                .getId()));

    }

    
    //JN following throwing a bizzare error of staleObjectStateException, TODO check this later.
/*    public void testSaveOrUpdate() {
       RuleSetRuleAuditDao ruleSetRuleAuditDao = (RuleSetRuleAuditDao) getContext().getBean("ruleSetRuleAuditDao");
       RuleSetRuleDao ruleSetRuleDao = (RuleSetRuleDao) getContext().getBean("ruleSetRuleDao");
        RuleSetRuleBean ruleSetRuleBean = new RuleSetRuleBean();
    
         ruleSetRuleBean = ruleSetRuleDao.findById(3);
      //  ruleSetRuleBean.setId(3);
        RuleSetRuleAuditBean ruleSetRuleAuditBean = new RuleSetRuleAuditBean();
        ruleSetRuleAuditBean.setRuleSetRuleBean(ruleSetRuleBean);
        ruleSetRuleAuditBean = ruleSetRuleAuditDao.saveOrUpdate(ruleSetRuleAuditBean);

        assertNotNull("Persistant id is null", ruleSetRuleAuditBean.getId());
    }*/
    public void tearDown(){
        try {
            ruleSetRuleDao.getSessionFactory().getCurrentSession().close();
            ruleSetRuleAuditDao.getSessionFactory().getCurrentSession().close();
            ruleSetDao.getSessionFactory().getCurrentSession().close();
            ruleSetRuleDao.getSessionFactory().getCurrentSession().setCacheMode(CacheMode.REFRESH);
        } catch (HibernateException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        super.tearDown();
    }
}