/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.crfdata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ImportCRFInfo;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ImportCRFInfoContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;

/**
 * An import file whose StudyEventRepeatKey is not a whole number: the
 * metadata check reports it, and the steps that look up the visit refuse the
 * file, or leave the visit out, instead of throwing NumberFormatException.
 *
 * <p>Runs against the demo seed: subject SS_M001 and event definition
 * SE_V1_INCLUSION in S_DEFAULTS1 (study 1).
 */
class ImportCRFDataServiceRepeatKeyDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static ODMContainer importFile(String repeatKeyAttribute) {
        return importFile("SS_M001", studyEvent("SE_V1_INCLUSION", repeatKeyAttribute, ""));
    }

    /** One StudyEventData holding the demographics form; formAttributes go on its FormData. */
    private static String studyEvent(String studyEventOid, String repeatKeyAttribute, String formAttributes) {
        return ""
                + "      <StudyEventData StudyEventOID=\"" + studyEventOid + "\"" + repeatKeyAttribute + ">\n"
                + "        <FormData FormOID=\"F_DEMOGRAPHICS_V1\"" + formAttributes + ">\n"
                + "          <ItemGroupData ItemGroupOID=\"IG_DEMOG_UNGROUPED\" TransactionType=\"Insert\">\n"
                + "            <ItemData ItemOID=\"I_HEIGHT_CM\" Value=\"175\"/>\n"
                + "          </ItemGroupData>\n"
                + "        </FormData>\n"
                + "      </StudyEventData>\n";
    }

    private static ODMContainer importFile(String subjectKey, String... studyEvents) {
        String xml = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\" xmlns:OpenClinica=\"http://www.openclinica.org/ns/odm_ext_v130/v3.1\"\n"
                + "     ODMVersion=\"1.3\" FileType=\"Snapshot\" FileOID=\"LCMUW_Q5_IT\" CreationDateTime=\"2026-09-30T00:00:00Z\">\n"
                + "  <ClinicalData StudyOID=\"S_DEFAULTS1\" MetaDataVersionOID=\"v1.0\">\n"
                + "    <SubjectData SubjectKey=\"" + subjectKey + "\">\n"
                + String.join("", studyEvents)
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
    void fetchingTheEventCrfsRefusesTheFile() {
        assertNull(service().fetchEventCRFBeans(importFile(" StudyEventRepeatKey=\"abc\""), root()));
    }

    /**
     * SS_M005's day-90 visit (study_event 15) is scheduled and has no event
     * CRF, so a resolvable visit there gets one created. The bad key comes
     * after it in the file, and the file is refused before anything is
     * created.
     */
    @Test
    void fetchingTheEventCrfsCreatesNothingWhenALaterVisitHasABadKey() throws Exception {
        int before = eventCrfCount();

        assertNull(service().fetchEventCRFBeans(importFile("SS_M005",
                studyEvent("SE_V3_DAY90", "", ""),
                studyEvent("SE_V1_INCLUSION", " StudyEventRepeatKey=\"abc\"", "")), root()));
        assertEquals(before, eventCrfCount());
    }

    @Test
    void theStatusCheckRefusesTheFile() {
        assertFalse(service().eventCRFStatusesValid(importFile(" StudyEventRepeatKey=\"abc\""), root()));
    }

    @Test
    void thePostImportStatusesSkipTheUnresolvableVisit() {
        String status = " OpenClinica:Status=\"initial data entry\"";

        assertEquals(Map.of(1, "initial data entry"), service().fetchEventCRFStatuses(
                importFile("SS_M001", studyEvent("SE_V1_INCLUSION", " StudyEventRepeatKey=\"1\"", status))));
        assertTrue(service().fetchEventCRFStatuses(
                importFile("SS_M001", studyEvent("SE_V1_INCLUSION", " StudyEventRepeatKey=\"abc\"", status))).isEmpty());
    }

    @Test
    void theImportSummaryLeavesTheUnresolvableVisitOut() {
        ImportCRFInfoContainer summary = assertDoesNotThrow(
                () -> new ImportCRFInfoContainer(importFile(" StudyEventRepeatKey=\"abc\""), DATA_SOURCE));

        assertTrue(summary.getImportCRFList().isEmpty(), "listed: " + summary.getImportCRFList());
        assertTrue(summary.getImportCRFMap().isEmpty(), "mapped: " + summary.getImportCRFMap());
    }

    @Test
    void theImportSummaryListsTheVisitANumericKeyNames() {
        ImportCRFInfoContainer summary = new ImportCRFInfoContainer(importFile(" StudyEventRepeatKey=\"1\""), DATA_SOURCE);

        List<ImportCRFInfo> listed = summary.getImportCRFList();
        assertEquals(1, listed.size(), "listed: " + listed);
        assertEquals(Integer.valueOf(1), listed.get(0).getEventCRFID());
        assertTrue(listed.get(0).isProcessImport());
        assertEquals(Map.of("SS_M001", Map.of("SE_V1_INCLUSION", Map.of("F_DEMOGRAPHICS_V1", "true"))),
                summary.getImportCRFMap());
    }
}
