/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.templates;

import org.akaza.openclinica.dao.core.SQLFactory;
import org.akaza.openclinica.i18n.util.ResourceBundleProvider;
import org.apache.commons.dbcp.BasicDataSource;
import org.dbunit.DataSourceBasedDBTestCase;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.Locale;
import java.util.Properties;

import javax.sql.DataSource;

@SuppressWarnings("deprecation")
public abstract class HibernateOcDbTestCase extends DataSourceBasedDBTestCase {

    protected static final Logger logger = LoggerFactory.getLogger(HibernateOcDbTestCase.class);
   // public static PlatformTransactionManager transactionManager;
    protected static ApplicationContext context;

    protected static Properties properties = new Properties();
    public static String dbName;
    public static String dbUrl;
    public static String dbUserName;
    public static String dbPassword;
    public static String dbDriverClassName;
    public static String locale;
    public  BasicDataSource ds ;
    
   protected static  PlatformTransactionManager transactionManager;

   /**
    * Per-test Spring transaction status, opened in {@link #setUp()} and rolled
    * back in {@link #tearDown()}. The transaction binds a Hibernate session to
    * the current thread for the duration of the test, so DAO calls that resolve
    * {@code sessionFactory.getCurrentSession()} see a live session. Rolling back
    * keeps tests from leaving residue in the DB across the suite.
    *
    * <p>Replaces a long-standing defect (OpenClinica 2013, commit {@code adba9a97d})
    * where {@link #tearDown()} called {@code commit()} on a brand-new participant
    * {@code TransactionStatus} rather than the one opened in the static initializer,
    * leaving the outer transaction permanently uncommitted and the Hibernate session
    * subject to silent closure mid-test.
    */
   private TransactionStatus testTransactionStatus;

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
                   new String[] { "classpath*:applicationContext-core-s*.xml", "classpath*:org/akaza/openclinica/applicationContext-core-db.xml",
                       "classpath*:org/akaza/openclinica/applicationContext-core-email.xml",
                       "classpath*:org/akaza/openclinica/applicationContext-core-hibernate.xml",
                       "classpath*:org/akaza/openclinica/applicationContext-core-service.xml",
                      " classpath*:org/akaza/openclinica/applicationContext-core-timer.xml",
                       "classpath*:org/akaza/openclinica/applicationContext-security.xml" });
     transactionManager = (PlatformTransactionManager) context.getBean("transactionManager");
     // Per-test transactions are opened in setUp() and rolled back in tearDown();
     // no class-level transaction is opened here.

   }


    public HibernateOcDbTestCase() {


    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Open a transaction that lives for the duration of this single test method.
        // HibernateTransactionManager binds the Hibernate session to the current
        // thread so DAO beans wired with SessionFactory.getCurrentSession() can
        // use it. The transaction is rolled back in tearDown() to prevent test
        // residue from leaking into other tests.
        testTransactionStatus = transactionManager.getTransaction(new DefaultTransactionDefinition());
    }

    @Override
    protected IDataSet getDataSet() throws Exception {
        return new FlatXmlDataSet(HibernateOcDbTestCase.class.getResourceAsStream(getTestDataFilePath()));
    }

    @Override
    public  DataSource getDataSource() {
       ds = new BasicDataSource();
        ds.setAccessToUnderlyingConnectionAllowed(true);
        ds.setDriverClassName(dbDriverClassName);
        ds.setUsername(dbUserName);
        ds.setPassword(dbPassword);
        ds.setUrl(dbUrl);
        return ds;
    }

    public ApplicationContext getContext() {
        return context;
    }

    public static void loadProperties() {
        try {
            properties.load(HibernateOcDbTestCase.class.getResourceAsStream(getPropertiesFilePath()));
        } catch (Exception ioExc) {
            logger.error("Hibernate property loading is not working properly: ", ioExc);
        }
    }

    protected static void initializeLocale() {
        ResourceBundleProvider.updateLocale(new Locale(locale));
    }

    /**
     * Instantiates SQLFactory and all the xml files that contain the queries
     * that are used in our dao class
     */
    protected static void initializeQueriesInXml() {
        String baseDir = System.getProperty("basedir");
        if (baseDir == null || "".equalsIgnoreCase(baseDir)) {
            throw new IllegalStateException(
                    "The system properties basedir were not made available to the application. Therefore we cannot locate the test properties file.");
        }
        // @pgawade 05-Nov-2010 Updated the path of directory storing xml files
        // containing sql queries
        // SQLFactory.JUNIT_XML_DIR =
        // baseDir + File.separator + "src" + File.separator + "main" +
        // File.separator + "webapp" + File.separator + "properties" +
        // File.separator;
  
        //Revisit this later
        /*    SQLFactory.JUNIT_XML_DIR =
            baseDir + File.separator + "src" + File.separator + "main" + File.separator + "resources" + File.separator + "properties" + File.separator;
*/
        // @pgawade 10272010 - Added the ResourceLoader instance as a parameter
        // to run method of SQLFactory
        // SQLFactory.getnstance().run(dbName);
        SQLFactory.getInstance().run(dbName, context);
    }

    
    private static String getPropertiesFilePath() {
        return "/test.properties";
    }

    /**
     * Gets the path and the name of the xml file holding the data. Example if
     * your Class Name is called
     * org.akaza.openclinica.service.rule.expression.TestExample.java you need
     * an xml data file in resources folder under same path + testdata + same
     * Class Name .xml
     * org/akaza/openclinica/service/rule/expression/testdata/TestExample.xml
     * 
     * @return path to data file
     */
    private String getTestDataFilePath() {
        StringBuffer path = new StringBuffer("/");
        path.append(getClass().getPackage().getName().replace(".", "/"));
        path.append("/testdata/");
        path.append(getClass().getSimpleName() + ".xml");
        return path.toString();
    }

    public String getDbName() {
        return dbName;
    }
  @Override
  public void tearDown() {
      // Roll back the test transaction first. Rollback (not commit) keeps test
      // writes from accumulating across the suite. This is the actual fix for the
      // 2013-era defect that left the original outer transaction uncommitted and
      // Hibernate sessions in an inconsistent state.
      try {
          if (testTransactionStatus != null && !testTransactionStatus.isCompleted()) {
              transactionManager.rollback(testTransactionStatus);
          }
      } catch (Exception e) {
          logger.error("Failed to roll back test transaction: ", e);
      } finally {
          testTransactionStatus = null;
      }
      try {
          super.tearDown();
      } catch (Exception e) {
          logger.error("DBUnit super.tearDown() failed: ", e);
      }
      // ds is the DBUnit datasource; super.tearDown() already closed its
      // connection. Nothing to do here.
  }
}
