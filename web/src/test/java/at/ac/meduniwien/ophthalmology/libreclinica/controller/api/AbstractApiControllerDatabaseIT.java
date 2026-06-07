/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.SQLFactory;

import liquibase.integration.spring.SpringLiquibase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase E.5 #1 — base class for happy-path MockMvc ITs that need real
 * DB rows.
 *
 * <p>Sister class to {@link AbstractApiControllerTest} (which uses a
 * Mockito mock {@link DataSource} for session-guard-only coverage). The
 * two are kept as siblings rather than parent/child so each test
 * declares its DB requirements explicitly via its base-class choice.
 *
 * <h2>How it boots</h2>
 *
 * <p>A single {@link PostgreSQLContainer} is started per-class (static
 * field + {@link BeforeAll}/{@link AfterAll}) instead of per-test, so
 * the ~10-second Liquibase migration runs once per IT class rather
 * than per method. The container is the canonical {@code postgres:14-alpine}
 * image used elsewhere in the repo (matches the Compose stack + the
 * isolated-network IT runner documented in CLAUDE.md).
 *
 * <p>{@link SpringLiquibase} is invoked directly against the container's
 * JDBC URL with {@code classpath:migration/master.xml} — the same
 * changelog production runs. The seed file
 * {@code lc-muw-2026-06-01-seed-demo-data.xml} fixes 7 demo subjects
 * (M-001 .. M-007) in study_id=1, two of which (M-003, M-006) are
 * status_id=8 SIGNED.
 *
 * <h2>What this base intentionally does NOT do</h2>
 *
 * <ul>
 *   <li><strong>No full {@code @SpringBootTest} context.</strong> The
 *       legacy {@code CoreResources.setResourceLoader} runs heavy
 *       file-copy logic against {@code $catalina.home/$WEBAPP.lower.config}
 *       on bean init, which is incompatible with Testcontainers'
 *       random JDBC URLs and would require either Tomcat or a mocked
 *       filesystem. Subclasses build a minimal collaborator graph by
 *       hand via {@link #buildSubjectsController()} + siblings.</li>
 *   <li><strong>No automatic MockMvc wiring.</strong> Subclasses build
 *       their own MockMvc via {@code MockMvcBuilders.standaloneSetup(...)}
 *       — same pattern as {@link AbstractApiControllerTest}. The
 *       lightweight setup keeps boot time at &lt;1 sec on warm Docker.</li>
 * </ul>
 *
 * <h2>Boot time</h2>
 *
 * <p>Cold (image pull): ~30 sec. Warm: container start ~3 sec + Liquibase
 * apply ~7 sec + per-test ~10 ms. The Liquibase run is amortised across
 * every test in the class — a 10-method IT class still pays only one
 * 7-second migration tax.
 */
public abstract class AbstractApiControllerDatabaseIT {

    /** Single container per IT-class lifetime. */
    protected static PostgreSQLContainer<?> POSTGRES;

    /** DataSource wired against the container; built once Liquibase has applied. */
    protected static DataSource DATA_SOURCE;

    /** Snapshot of {@code catalina.home} before the IT mutates it; restored in {@link #stopContainer()}. */
    private static String SAVED_CATALINA_HOME;

    private static boolean CATALINA_HOME_WAS_SET;

    @BeforeAll
    static void startContainer() throws Exception {
        // SQLFactory.run() branches on `catalina.home` to decide whether to load
        // the DAO query-XMLs (e.g. study_subject_dao.xml) from the filesystem or
        // the classpath. Unset → filesystem branch via getPropertiesDir(), which
        // resolves placeholder.properties through the classloader; inside a jar
        // that path becomes `file:/…core.jar!/properties/`, which FileInputStream
        // cannot open. The IOException is swallowed (SQLFactory.java:233–235), so
        // the `digesters` map stays empty and the first DAO call NPEs on
        // `this.digester.getQuery(...)`. Setting any non-null value flips to the
        // classpath branch which resolves `classpath:properties/<name>.xml`
        // against the core jar correctly. Tomcat sets this in production.
        SAVED_CATALINA_HOME = System.getProperty("catalina.home");
        CATALINA_HOME_WAS_SET = SAVED_CATALINA_HOME != null;
        System.setProperty("catalina.home", "testcontainers-it");

        // Phase E.6 ci — bind the ResourceBundle locale once for every
        // DatabaseIT. Without this, any DAO read path that eventually
        // resolves Term.getName() (e.g. findAllRolesByUserName) NPEs on
        // a ThreadLocal-bundle miss, surfacing as a 500 to MockMvc and
        // breaking assertions. AbstractApiControllerTest (mock DataSource
        // sibling) binds per-test in @BeforeEach; we bind here once
        // because the @BeforeAll lifecycle of the static container suits
        // a single bind for the whole class. Z2 (PR #153) added this
        // per-class for two user ITs; hoisting to the base so every
        // DatabaseIT gets it transparently.
        at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider.updateLocale(
                java.util.Locale.ENGLISH);

        // Phase E.6 ci — DAO paths that hit the EhCache wrapper
        // (e.g. ItemDataDAO.getDataType → ItemDAO.findByPK → EntityDAO.select)
        // call CoreResources.getField("…") which reads from the static
        // CoreResources.DATAINFO. In production a Tomcat bean lifecycle
        // populates that Properties bag; in standalone ITs it stays null
        // and any read path NPEs. Pre-populate with the classpath test
        // datainfo.properties (already used by the legacy DAO test suite).
        java.util.Properties dataInfo = new java.util.Properties();
        try (java.io.InputStream is = AbstractApiControllerDatabaseIT.class
                .getClassLoader().getResourceAsStream("datainfo.properties")) {
            if (is != null) dataInfo.load(is);
        }
        java.lang.reflect.Field dataInfoField =
                at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources.class
                        .getDeclaredField("DATAINFO");
        dataInfoField.setAccessible(true);
        if (dataInfoField.get(null) == null) {
            dataInfoField.set(null, dataInfo);
        }

        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:14-alpine"))
                .withDatabaseName("openclinica")
                .withUsername("clinica")
                .withPassword("clinica");
        POSTGRES.start();

        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new org.postgresql.Driver());
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        DATA_SOURCE = ds;

        // Apply migrations — same changelog production uses. The seed
        // file at the tail of master.xml fixes M-001 .. M-007.
        org.springframework.core.io.ResourceLoader resourceLoader =
                new org.springframework.core.io.DefaultResourceLoader();
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(DATA_SOURCE);
        liquibase.setChangeLog("classpath:migration/master.xml");
        liquibase.setResourceLoader(resourceLoader);
        liquibase.afterPropertiesSet();

        // DAO query catalog initialisation. Every DAO method delegates to
        // `DAODigester.getQuery(...)` to look up its prepared-statement SQL
        // by symbolic name; the digester graph is populated from XML files
        // under classpath:properties/sql/postgres/... by SQLFactory.run().
        // In production CoreResources.setResourceLoader() drives this; for
        // the IT we trigger it directly because the lightweight test
        // context bypasses CoreResources entirely.
        SQLFactory.getInstance().run("postgres", resourceLoader);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES != null) {
            POSTGRES.stop();
        }
        if (CATALINA_HOME_WAS_SET) {
            System.setProperty("catalina.home", SAVED_CATALINA_HOME);
        } else {
            System.clearProperty("catalina.home");
        }
    }

    /**
     * Build a real {@link SubjectsApiController} wired against the
     * Testcontainers-backed DataSource.
     *
     * <p>{@link SecurityManager} is mocked because the list endpoint
     * does not invoke it (the password-reauth path is only hit by
     * {@code POST /sign}). The
     * {@link at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter}
     * is constructed directly with the live DataSource so its
     * {@code visibleStudyIds()} call returns the full study tree the
     * happy-path test expects.
     */
    protected final SubjectsApiController buildSubjectsController() {
        return new SubjectsApiController(
                DATA_SOURCE,
                org.mockito.Mockito.mock(SecurityManager.class),
                new at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter(DATA_SOURCE)
        );
    }

    /**
     * Build a {@link MockHttpSession} carrying {@code userBean} + {@code study}
     * attributes referencing seeded rows (user_account #1 = "root",
     * study #1 = "default-study").
     *
     * <p>The seed migration sets user_account.user_id=1 to the
     * institutional root user; study_id=1 is the demo study. The OID
     * fields are left null because the SubjectsApiController list
     * endpoint reads study.id (not OID) before delegating to
     * StudySubjectDAO.
     */
    protected final MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("default-study");
        study.setName("default-study");
        session.setAttribute("study", study);
        return session;
    }
}
