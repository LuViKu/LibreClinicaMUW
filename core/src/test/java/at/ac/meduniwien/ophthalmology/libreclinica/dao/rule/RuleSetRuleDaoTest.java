/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.rule;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.hibernate.HibernateException;
import java.util.List;

public class RuleSetRuleDaoTest extends HibernateOcDbTestCase {
    private static RuleSetRuleDao ruleSetRuleDao;
    private static RuleDao ruleDao;
    private static RuleSetDao ruleSetDao;
    /*static
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
                                           "classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-service.xml",
                       " classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-timer.xml",
                        "classpath*:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-security.xml" });
      transactionManager = (PlatformTransactionManager) context.getBean("transactionManager");
      transactionManager.getTransaction(new DefaultTransactionDefinition());
        

    }*/
    @Override
    public void setUp() throws Exception{
        super.setUp();
        ruleSetRuleDao = (RuleSetRuleDao) getContext().getBean("ruleSetRuleDao"); 
        ruleSetDao = (RuleSetDao) getContext().getBean("ruleSetDao");
        ruleDao = (RuleDao) getContext().getBean("ruleDao");
    }
   
    public void testFindById() {
//        RuleSetRuleDao ruleSetRuleDao = (RuleSetRuleDao) getContext().getBean("ruleSetRuleDao");
        RuleSetRuleBean ruleSetRuleBean = null;
        ruleSetRuleBean = ruleSetRuleDao.findById(3);

        // Test RuleSetRule
        assertNotNull("RuleSet is null", ruleSetRuleBean);
        assertEquals("The id of the retrieved RuleSet should be 1", new Integer(3), ruleSetRuleBean.getId());

    }

    public void testFindByIdEmptyResultSet() {
    //    RuleSetRuleDao ruleSetRuleDao = (RuleSetRuleDao) getContext().getBean("ruleSetRuleDao");

        RuleSetRuleBean ruleSetRuleBean = null;
        ruleSetRuleBean = ruleSetRuleDao.findById(6);

        // Test Rule
        assertNull("RuleSet is null", ruleSetRuleBean);
    }

    public void testFindByRuleSetBeanAndRuleBean() {
      //  RuleDao ruleDao = (RuleDao) getContext().getBean("ruleDao");
       // RuleSetDao ruleSetDao = (RuleSetDao) getContext().getBean("ruleSetDao");
       // RuleSetRuleDao ruleSetRuleDao = (RuleSetRuleDao) getContext().getBean("ruleSetRuleDao");
        RuleBean persistentRuleBean = ruleDao.findById(-1);
        RuleSetBean persistentRuleSetBean = ruleSetDao.findById(-1);
        List<RuleSetRuleBean> ruleSetRules = ruleSetRuleDao.findByRuleSetBeanAndRuleBean(persistentRuleSetBean, persistentRuleBean);

        assertNotNull("RuleSetRules is null", ruleSetRules);
        assertEquals("The size of RuleSetRules should be 1", new Integer(1), new Integer(ruleSetRules.size()));
    }
    public void tearDown(){
        try {
           ruleSetDao.getSessionFactory().getCurrentSession().close();
            ruleDao.getSessionFactory().getCurrentSession().close();
        //   ruleDao.getSessionFactory().getCurrentSession().flush();
            ruleSetRuleDao.getSessionFactory().getCurrentSession().close();
        } catch (HibernateException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        super.tearDown();
    }
}