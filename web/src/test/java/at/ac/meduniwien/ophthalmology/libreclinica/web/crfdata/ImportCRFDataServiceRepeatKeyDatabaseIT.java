/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.crfdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;

/**
 * An import file whose StudyEventRepeatKey is not a whole number: the
 * metadata check reports it, and the steps that look up the visit refuse the
 * file instead of throwing NumberFormatException.
 *
 * <p>Runs against the demo seed: subject SS_M001 and event definition
 * SE_V1_INCLUSION in S_DEFAULTS1 (study 1).
 */
class ImportCRFDataServiceRepeatKeyDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static ODMContainer importFile(String repeatKeyAttribute) {
        String xml = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\" ODMVersion=\"1.3\" FileType=\"Snapshot\"\n"
                + "     FileOID=\"LCMUW_Q5_IT\" CreationDateTime=\"2026-09-30T00:00:00Z\">\n"
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
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        return new OdmJaxbContext().unmarshalClinicalData(in);
    }

    private static ImportCRFDataService service() {
        return new ImportCRFDataService(DATA_SOURCE, Locale.ENGLISH);
    }

    private static UserAccountBean root() {
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        return ub;
    }

    private static boolean namesTheRepeatKey(List<String> errors) {
        return errors.stream().anyMatch(e -> e.contains("StudyEventRepeatKey"));
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
    void theMetadataCheckReportsARepeatKeyThatIsNotANumber() {
        List<String> errors = service().validateStudyMetadata(importFile(" StudyEventRepeatKey=\"abc\""), 1);

        assertTrue(namesTheRepeatKey(errors), "errors were: " + errors);
    }

    @Test
    void theMetadataCheckAcceptsANumericOrAbsentRepeatKey() {
        assertFalse(namesTheRepeatKey(service().validateStudyMetadata(importFile(" StudyEventRepeatKey=\"1\""), 1)));
        assertFalse(namesTheRepeatKey(service().validateStudyMetadata(importFile(""), 1)));
    }

    @Test
    void fetchingTheEventCrfsRefusesTheFileAndCreatesNothing() throws Exception {
        int before = eventCrfCount();

        assertNull(service().fetchEventCRFBeans(importFile(" StudyEventRepeatKey=\"abc\""), root()));
        assertEquals(before, eventCrfCount());
    }

    @Test
    void theStatusCheckRefusesTheFile() {
        assertFalse(service().eventCRFStatusesValid(importFile(" StudyEventRepeatKey=\"abc\""), root()));
    }

    @Test
    void thePostImportStatusesSkipTheUnresolvableVisit() {
        assertTrue(service().fetchEventCRFStatuses(importFile(" StudyEventRepeatKey=\"abc\"")).isEmpty());
    }
}
