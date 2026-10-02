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
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;

/**
 * Who may put a file into the study's attached-file directory through
 * {@code /UploadFile}, the data-entry form's upload popup. The data-entry roles
 * may, in a study open for data entry; a Monitor's upload, or one into a frozen
 * study, is refused and nothing is written.
 */
class UploadFileDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String BOUNDARY = "it-boundary";

    @TempDir
    Path attachedFiles;

    private LegacyServletHarness harness;
    private String savedLocation;

    @BeforeEach
    void setUp() throws Exception {
        Properties dataInfo = dataInfo();
        savedLocation = dataInfo.getProperty("attached_file_location");
        dataInfo.setProperty("attached_file_location", attachedFiles.toString() + java.io.File.separator);
        harness = new LegacyServletHarness(DATA_SOURCE)
                .bean("securityManager", mock(SecurityManager.class))
                .bean("mailSender", mock(JavaMailSenderImpl.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (savedLocation == null) {
            dataInfo().remove("attached_file_location");
        } else {
            dataInfo().setProperty("attached_file_location", savedLocation);
        }
    }

    @Test
    void aMonitorCannotUploadAFile() throws Exception {
        upload(request(user("manual_monitor")));

        assertEquals(0, filesWritten(), "nothing was written");
    }

    @Test
    void nothingIsUploadedIntoAFrozenStudy() throws Exception {
        MockHttpServletRequest req = request(user("manual_crc"));
        ((StudyBean) Objects.requireNonNull(req.getSession()).getAttribute("study")).setStatus(Status.FROZEN);

        upload(req);

        assertEquals(0, filesWritten(), "nothing was written");
    }

    @Test
    void aDataEntryRoleUploadsAFile() throws Exception {
        upload(request(user("manual_crc")));

        assertEquals(1, filesWritten());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private MockHttpServletRequest request(UserAccountBean user) {
        return harness.request("POST", "/UploadFile", user);
    }

    /** The popup's form: the item id and one text file. */
    private void upload(MockHttpServletRequest req) throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"itemId\"\r\n\r\n3\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"scan.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nIT upload\r\n"
                + "--" + BOUNDARY + "--\r\n";
        req.setContentType("multipart/form-data; boundary=" + BOUNDARY);
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        harness.run(new UploadFileServlet(), req);
    }

    private long filesWritten() throws Exception {
        try (Stream<Path> files = Files.walk(attachedFiles)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static Properties dataInfo() throws ReflectiveOperationException {
        Field field = CoreResources.class.getDeclaredField("DATAINFO");
        field.setAccessible(true);
        return (Properties) field.get(null);
    }

    /** A user with the roles login would load. */
    private static UserAccountBean user(String name) {
        UserAccountDAO dao = new UserAccountDAO(DATA_SOURCE);
        UserAccountBean ub = dao.findByUserName(name);
        for (StudyUserRoleBean role : dao.findAllRolesByUserName(name)) {
            ub.addRole(role);
        }
        ub.setPasswdTimestamp(new Date());
        return ub;
    }
}
