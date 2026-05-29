/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.user.AuthoritiesBean;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.hibernate.HibernateException;

public class AuthoritiesDaoTest extends HibernateOcDbTestCase {
    private static AuthoritiesDao authoritiesDao;
   /* 
    static
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
     // transactionManager.getTransaction(new DefaultTransactionDefinition());
        

    }
*/
    public void setUp() throws Exception{
        super.setUp();
        authoritiesDao = (AuthoritiesDao) getContext().getBean("authoritiesDao");
        
    }
  
    public void testSaveOrUpdate() {
        AuthoritiesBean authorities = new AuthoritiesBean();
        authorities.setUsername("root");
        authorities.setAuthority("ROLE_USER");
        authorities.setId(-1);
        // Phase B.5: Hibernate 6 strictly requires a non-null @Version on an
        // entity that's being treated as detached (id manually set). Hibernate
        // 5 was lenient and accepted null → 0 on insert. Initialise to 0 to
        // match the new contract.
        authorities.setVersion(0);
        authorities = authoritiesDao.saveOrUpdate(authorities);

        assertNotNull("Persistant id is null", authorities.getId());
    }

    public void testFindById() {
    //	AuthoritiesDao authoritiesDao = (AuthoritiesDao) getContext().getBean("authoritiesDao");
        
    	AuthoritiesBean authorities = null;
    	authorities = authoritiesDao.findById(-1);

        // Test Authorities
        assertNotNull("RuleSet is null", authorities);
        assertEquals("The id of the retrieved Domain Object should be -1", new Integer(-1), authorities.getId());
   }
    public void testFindByUsername() {

        
        AuthoritiesBean authorities = null;
        authorities = authoritiesDao.findByUsername("root");
        
      
        // Test Authorities
        assertNotNull("RuleSet is null", authorities);
        assertEquals("The id of the retrieved Domain Object should be -1", new Integer(-1), authorities.getId());
    }
    
    
    
    
    public void tearDown(){
        try {
           
            authoritiesDao.getSessionFactory().getCurrentSession().close();
        } catch (HibernateException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        super.tearDown();
    }

}