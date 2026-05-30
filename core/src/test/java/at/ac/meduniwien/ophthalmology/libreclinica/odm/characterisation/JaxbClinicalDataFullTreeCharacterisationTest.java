/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.odm.characterisation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.CRFDataPostImportContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.FormDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ImportItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ImportItemGroupDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.StudyEventDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.SubjectDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;
import org.junit.Test;

/**
 * Phase B.3 PR 3 prep — JAXB twin of {@link CastorClinicalDataFullTreeCharacterisationTest}.
 *
 * <p>Same input ODM 1.3 document, same assertions, but the unmarshal
 * goes through {@link OdmJaxbContext#unmarshalClinicalData} (Jakarta-
 * style JAXB on the existing odm/-module jakarta.xml.bind v2.3
 * annotations added in this commit). If both Castor and JAXB produce
 * the same bean-tree shape from the same input, the production
 * migration of {@code ImportCRFDataServlet} from Castor to JAXB
 * cannot silently lose data.
 *
 * <p>Sibling-tested so Castor stays the reference until B.3 PR 3
 * lands; once the Castor production code paths are gone (B.3 PR 3c),
 * the Castor twin can also retire and this test becomes the sole
 * characterisation for the cd_odm unmarshal contract.
 */
public class JaxbClinicalDataFullTreeCharacterisationTest {

    private static final String FULL_ODM_INPUT =
              "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"\n"
            + "     xmlns:OpenClinica=\"http://www.openclinica.org/ns/odm_ext_v130/v3.1\">\n"
            + "  <ClinicalData StudyOID=\"S_MUW_FULL\">\n"
            + "    <SubjectData SubjectKey=\"SS_MUW_001\">\n"
            + "      <StudyEventData StudyEventOID=\"SE_VISIT1\" StudyEventRepeatKey=\"1\">\n"
            + "        <FormData FormOID=\"F_VITALS\" OpenClinica:Status=\"completed\">\n"
            + "          <ItemGroupData ItemGroupOID=\"IG_VITALS\" ItemGroupRepeatKey=\"1\">\n"
            + "            <ItemData ItemOID=\"I_BP_SYS\" Value=\"120\"/>\n"
            + "            <ItemData ItemOID=\"I_BP_DIA\" Value=\"80\"/>\n"
            + "          </ItemGroupData>\n"
            + "        </FormData>\n"
            + "      </StudyEventData>\n"
            + "    </SubjectData>\n"
            + "  </ClinicalData>\n"
            + "</ODM>\n";

    @Test
    public void fullOdmTreeUnmarshalsWithCorrectShape() {
        ODMContainer parsed = unmarshalViaJaxb(FULL_ODM_INPUT);

        assertNotNull("ODMContainer must not be null", parsed);
        CRFDataPostImportContainer clinical = parsed.getCrfDataPostImportContainer();
        assertNotNull("CRFDataPostImportContainer must be populated", clinical);
        assertEquals("StudyOID round-trips", "S_MUW_FULL", clinical.getStudyOID());

        ArrayList<SubjectDataBean> subjects = clinical.getSubjectData();
        assertNotNull("SubjectData list must not be null", subjects);
        assertEquals("Exactly one SubjectData row", 1, subjects.size());
        SubjectDataBean subject = subjects.get(0);
        assertEquals("SubjectKey → subjectOID",
                "SS_MUW_001", subject.getSubjectOID());

        ArrayList<StudyEventDataBean> events = subject.getStudyEventData();
        assertNotNull("StudyEventData list must not be null", events);
        assertEquals("Exactly one StudyEventData row", 1, events.size());
        StudyEventDataBean event = events.get(0);
        assertEquals("StudyEventOID round-trips",
                "SE_VISIT1", event.getStudyEventOID());
        assertEquals("StudyEventRepeatKey round-trips",
                "1", event.getStudyEventRepeatKey());

        ArrayList<FormDataBean> forms = event.getFormData();
        assertNotNull("FormData list must not be null", forms);
        assertEquals("Exactly one FormData row", 1, forms.size());
        FormDataBean form = forms.get(0);
        assertEquals("FormOID round-trips",
                "F_VITALS", form.getFormOID());
        assertEquals("OpenClinica:Status attribute round-trips to EventCRFStatus",
                "completed", form.getEventCRFStatus());

        ArrayList<ImportItemGroupDataBean> groups = form.getItemGroupData();
        assertNotNull("ItemGroupData list must not be null", groups);
        assertEquals("Exactly one ItemGroupData row", 1, groups.size());
        ImportItemGroupDataBean group = groups.get(0);
        assertEquals("ItemGroupOID round-trips",
                "IG_VITALS", group.getItemGroupOID());
        assertEquals("ItemGroupRepeatKey round-trips",
                "1", group.getItemGroupRepeatKey());

        ArrayList<ImportItemDataBean> items = group.getItemData();
        assertNotNull("ItemData list must not be null", items);
        assertEquals("Exactly two ItemData rows", 2, items.size());
        ImportItemDataBean systolic = items.get(0);
        assertEquals("first ItemOID",  "I_BP_SYS", systolic.getItemOID());
        assertEquals("first Value",    "120",      systolic.getValue());
        ImportItemDataBean diastolic = items.get(1);
        assertEquals("second ItemOID", "I_BP_DIA", diastolic.getItemOID());
        assertEquals("second Value",   "80",       diastolic.getValue());
    }

    @Test
    public void omittedUpsertOnYieldsNullField() {
        ODMContainer parsed = unmarshalViaJaxb(FULL_ODM_INPUT);
        assertNull("UpsertOn omitted from input → field stays null"
                + " (matches Castor's contract)",
                parsed.getCrfDataPostImportContainer().getUpsertOn());
    }

    private static ODMContainer unmarshalViaJaxb(String inputXml) {
        OdmJaxbContext ctx = new OdmJaxbContext();
        return ctx.unmarshalClinicalData(
                new ByteArrayInputStream(inputXml.getBytes(StandardCharsets.UTF_8)));
    }
}
