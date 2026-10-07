/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.SpringServletAccess;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.SQLInitServlet;

/**
 * The upload step of the legacy import ({@code ImportCRFData?action=confirm}),
 * run through {@code processRequest} against the demo seed.
 *
 * <p>A check that fails forwards back to the upload page, and that is the end
 * of the request: the steps after the check do not run. They used to run on
 * after the page had been sent, so a StudyEventRepeatKey that is not a number
 * still ended in a NumberFormatException, and an upload with no usable file
 * in a NullPointerException.
 *
 * <p>The probe records forwards instead of dispatching them. The upload
 * directory and the ODM JAXB context are stubbed; the rest is the servlet
 * and the import service against the database.
 */
class ImportCRFDataServletDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    Path dir;

    /** Runs the servlet with its request state wired by hand. */
    private static final class Probe extends ImportCRFDataServlet {
        private static final long serialVersionUID = 1L;
        private final File upload;
        private final List<Page> forwards = new ArrayList<>();

        Probe(File upload) {
            this.upload = upload;
        }

        void confirm(Path filePath, Path propertiesDir, DataSource ds) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/LibreClinica/ImportCRFData");
            req.addParameter("action", "confirm");
            request = req;
            response = new MockHttpServletResponse();
            session = req.getSession();
            context = new MockServletContext();
            ub = new UserAccountBean();
            ub.setId(1);
            ub.setName("root");
            ub.setActiveStudyId(1);
            sm = mock(SessionManager.class);
            when(sm.getDataSource()).thenReturn(ds);
            locale = Locale.ENGLISH;
            respage = ResourceBundleProvider.getPageMessagesBundle(Locale.ENGLISH);

            ApplicationContext spring = mock(ApplicationContext.class);
            when(spring.getBean("odmJaxbContext")).thenReturn(new OdmJaxbContext());
            try (MockedStatic<SQLInitServlet> datainfo = mockStatic(SQLInitServlet.class, CALLS_REAL_METHODS);
                 MockedStatic<SpringServletAccess> access = mockStatic(SpringServletAccess.class)) {
                datainfo.when(() -> SQLInitServlet.getField("filePath")).thenReturn(filePath + File.separator);
                access.when(() -> SpringServletAccess.getPropertiesDir(any())).thenReturn(propertiesDir + File.separator);
                access.when(() -> SpringServletAccess.getApplicationContext(any())).thenReturn(spring);
                processRequest();
            }
        }

        @Override
        public File uploadFile(String theDir, CRFVersionBean version) {
            return upload;
        }

        @Override
        protected void forwardPage(Page jspPage, boolean checkTrail) {
            forwards.add(jspPage);
        }

        List<Page> forwards() {
            return forwards;
        }

        @SuppressWarnings("unchecked")
        List<String> messages() {
            List<String> messages = (List<String>) request.getAttribute(PAGE_MESSAGE);
            return messages == null ? List.of() : messages;
        }

        Object sessionAttribute(String name) {
            return session.getAttribute(name);
        }
    }

    /**
     * The seeded demo CRF has no item group rows (a CRF uploaded through the
     * application always gets an "Ungrouped" one), and the step that reads
     * the values needs one for the fixture's item.
     */
    @BeforeAll
    static void groupTheFixtureItem() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement group = c.prepareStatement(
                     "INSERT INTO item_group (name, crf_id, status_id, date_created, owner_id, oc_oid) "
                             + "VALUES ('Ungrouped', 1, 1, NOW(), 1, 'IG_DEMOG_UNGROUPED') RETURNING item_group_id");
             ResultSet rs = group.executeQuery()) {
            rs.next();
            try (PreparedStatement metadata = c.prepareStatement(
                    "INSERT INTO item_group_metadata (item_group_id, crf_version_id, item_id, ordinal, show_group, repeating_group) "
                            + "VALUES (?, 1, 3, 1, true, false)")) {
                metadata.setInt(1, rs.getInt(1));
                metadata.executeUpdate();
            }
        }
    }

    private static String importFile(String repeatKeyAttribute) {
        return ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\" ODMVersion=\"1.3\" FileType=\"Snapshot\"\n"
                + "     FileOID=\"LCMUW_Q5_SERVLET_IT\" CreationDateTime=\"2026-09-30T00:00:00Z\">\n"
                + "  <ClinicalData StudyOID=\"S_DEFAULTS1\" MetaDataVersionOID=\"v1.0\">\n"
                + "    <SubjectData SubjectKey=\"SS_M001\">\n"
                + "      <StudyEventData StudyEventOID=\"SE_V1_INCLUSION\"" + repeatKeyAttribute + ">\n"
                + "        <FormData FormOID=\"F_DEMOGRAPHICS_V1\">\n"
                + "          <ItemGroupData ItemGroupOID=\"IG_DEMOG_UNGROUPED\" TransactionType=\"Insert\">\n"
                + "            <ItemData ItemOID=\"I_HEIGHT_CM\" Value=\"175\"/>\n"
                + "          </ItemGroupData>\n"
                + "        </FormData>\n"
                + "      </StudyEventData>\n"
                + "    </SubjectData>\n"
                + "  </ClinicalData>\n"
                + "</ODM>\n";
    }

    private File upload(String content) throws Exception {
        return Files.writeString(dir.resolve("import.xml"), content, StandardCharsets.UTF_8).toFile();
    }

    private static int eventCrfCount() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM event_crf");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void aRepeatKeyThatIsNotANumberEndsAtTheMetadataErrors() throws Exception {
        int before = eventCrfCount();
        Probe probe = new Probe(upload(importFile(" StudyEventRepeatKey=\"abc\"")));

        probe.confirm(dir, dir, DATA_SOURCE);

        assertEquals(List.of(Page.IMPORT_CRF_DATA), probe.forwards(), "messages: " + probe.messages());
        assertTrue(probe.messages().stream().anyMatch(m -> m.contains("StudyEventRepeatKey")), "messages: " + probe.messages());
        assertNull(probe.sessionAttribute("importedData"));
        assertEquals(before, eventCrfCount());
    }

    @Test
    void aFileThatIsNotXmlEndsAtTheParseError() throws Exception {
        Probe probe = new Probe(upload("not an ODM file"));

        probe.confirm(dir, dir, DATA_SOURCE);

        assertEquals(List.of(Page.IMPORT_CRF_DATA), probe.forwards(), "messages: " + probe.messages());
        assertTrue(probe.messages().stream().anyMatch(m -> m.startsWith("Your XML is not well formed")), "messages: " + probe.messages());
    }

    @Test
    void anUploadWithoutAnXmlFileEndsThere() throws Exception {
        Probe probe = new Probe(null);

        probe.confirm(dir, dir, DATA_SOURCE);

        assertEquals(List.of(Page.IMPORT_CRF_DATA), probe.forwards(), "messages: " + probe.messages());
    }

    @Test
    void aFilePathThatDoesNotExistEndsThere() throws Exception {
        Path missing = dir.resolve("missing");
        Probe probe = new Probe(upload(importFile(" StudyEventRepeatKey=\"1\"")));

        probe.confirm(missing, dir, DATA_SOURCE);

        assertEquals(List.of(Page.IMPORT_CRF_DATA), probe.forwards(), "messages: " + probe.messages());
        assertFalse(Files.exists(missing), "the upload step went on and created " + missing);
    }

    @Test
    void aValidFileGoesOnToTheVerifyStep() throws Exception {
        int before = eventCrfCount();
        Probe probe = new Probe(upload(importFile(" StudyEventRepeatKey=\"1\"")));

        probe.confirm(dir, dir, DATA_SOURCE);

        assertEquals(List.of(Page.VERIFY_IMPORT_SERVLET), probe.forwards(), "messages: " + probe.messages());
        assertNotNull(probe.sessionAttribute("odmContainer"));
        assertEquals(1, ((List<?>) probe.sessionAttribute("importedData")).size());
        assertEquals(before, eventCrfCount());
    }
}
