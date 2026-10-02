/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.crfdata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;

/**
 * An import file whose ItemGroupRepeatKey is not a whole number of 1 or more
 * is refused: the metadata check reports it, and the step that builds the
 * values refuses the file instead of filing the value under row 1.
 *
 * <p>Runs against the demo seed: subject SS_M001, event definition
 * SE_V1_INCLUSION and form F_DEMOGRAPHICS_V1 in S_DEFAULTS1 (study 1).
 */
class ImportItemGroupRepeatKeyDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static ODMContainer importFile(String eventKeyAttribute, String groupKeyAttribute) {
        String xml = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\" ODMVersion=\"1.3\" FileType=\"Snapshot\"\n"
                + "     FileOID=\"LCMUW_IG_KEY_IT\" CreationDateTime=\"2026-09-30T00:00:00Z\">\n"
                + "  <ClinicalData StudyOID=\"S_DEFAULTS1\" MetaDataVersionOID=\"v1.0\">\n"
                + "    <SubjectData SubjectKey=\"SS_M001\">\n"
                + "      <StudyEventData StudyEventOID=\"SE_V1_INCLUSION\"" + eventKeyAttribute + ">\n"
                + "        <FormData FormOID=\"F_DEMOGRAPHICS_V1\">\n"
                + "          <ItemGroupData ItemGroupOID=\"IG_DEMOG_UNGROUPED\"" + groupKeyAttribute
                + " TransactionType=\"Insert\">\n"
                + "            <ItemData ItemOID=\"I_HEIGHT_CM\" Value=\"175\"/>\n"
                + "          </ItemGroupData>\n"
                + "        </FormData>\n"
                + "      </StudyEventData>\n"
                + "    </SubjectData>\n"
                + "  </ClinicalData>\n"
                + "</ODM>\n";
        return new OdmJaxbContext().unmarshalClinicalData(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
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

    private static boolean namesTheGroupKey(List<String> errors) {
        return errors.stream().anyMatch(e -> e.contains("ItemGroupRepeatKey"));
    }

    private static List<?> lookup(ODMContainer file) throws OpenClinicaException {
        // The event CRF the values go into, as the import servlet finds it.
        List<EventCRFBean> eventCrfs = service().fetchEventCRFBeans(importFile("", ""), root());
        assertNotNull(eventCrfs);
        ArrayList<Integer> permitted = new ArrayList<>();
        for (EventCRFBean ecb : eventCrfs) permitted.add(ecb.getId());
        return service().lookupValidationErrors(new MockHttpServletRequest(), file, root(),
                new HashMap<>(), new HashMap<>(), permitted);
    }

    @Test
    void theMetadataCheckReportsAGroupKeyThatIsNotAPositiveWholeNumber() {
        assertTrue(namesTheGroupKey(service().validateStudyMetadata(importFile("", " ItemGroupRepeatKey=\"abc\""), 1)));
        assertTrue(namesTheGroupKey(service().validateStudyMetadata(importFile("", " ItemGroupRepeatKey=\"0\""), 1)));
    }

    @Test
    void theMetadataCheckAcceptsANumericOrAbsentGroupKey() {
        assertFalse(namesTheGroupKey(service().validateStudyMetadata(importFile("", " ItemGroupRepeatKey=\"2\""), 1)));
        assertFalse(namesTheGroupKey(service().validateStudyMetadata(importFile("", ""), 1)));
    }

    @Test
    void anEmptyKeyIsAKeyLeftOutAndSpaceAroundANumberIsIgnored() throws Exception {
        // As the import's rule run reads them (ImportDataRuleRunnerContainer.repeatKey).
        for (String key : new String[] {"", "  ", " 2 "}) {
            String group = " ItemGroupRepeatKey=\"" + key + "\"";
            String event = " StudyEventRepeatKey=\"" + key.replace('2', '1') + "\"";
            List<String> errors = service().validateStudyMetadata(importFile(event, group), 1);
            assertFalse(namesTheGroupKey(errors), "ItemGroupRepeatKey='" + key + "': " + errors);
            assertFalse(errors.stream().anyMatch(e -> e.contains("StudyEventRepeatKey")),
                    "StudyEventRepeatKey='" + key + "': " + errors);
            try {
                lookup(importFile(event, group));
            } catch (OpenClinicaException e) {
                // The demo form may refuse the value for other reasons; never for its keys.
                assertFalse(e.getOpenClinicaMessage().contains("RepeatKey"),
                        "key '" + key + "': " + e.getOpenClinicaMessage());
            }
        }
    }

    @Test
    void aValueWithABadGroupKeyIsNotFiledUnderRowOne() {
        OpenClinicaException refused = assertThrows(OpenClinicaException.class,
                () -> lookup(importFile("", " ItemGroupRepeatKey=\"abc\"")));
        assertTrue(refused.getOpenClinicaMessage().contains("ItemGroupRepeatKey"), refused.getOpenClinicaMessage());
    }

    @Test
    void aValueWithABadEventKeyIsNotFiledUnderTheFirstVisit() {
        OpenClinicaException refused = assertThrows(OpenClinicaException.class,
                () -> lookup(importFile(" StudyEventRepeatKey=\"abc\"", "")));
        assertTrue(refused.getOpenClinicaMessage().contains("StudyEventRepeatKey"), refused.getOpenClinicaMessage());
    }
}
