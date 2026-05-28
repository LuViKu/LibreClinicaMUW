/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.odm.characterisation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;

import org.akaza.openclinica.bean.submit.crfdata.CRFDataPostImportContainer;
import org.akaza.openclinica.bean.submit.crfdata.FormDataBean;
import org.akaza.openclinica.bean.submit.crfdata.ImportItemDataBean;
import org.akaza.openclinica.bean.submit.crfdata.ImportItemGroupDataBean;
import org.akaza.openclinica.bean.submit.crfdata.ODMContainer;
import org.akaza.openclinica.bean.submit.crfdata.StudyEventDataBean;
import org.akaza.openclinica.bean.submit.crfdata.SubjectDataBean;
import org.exolab.castor.mapping.Mapping;
import org.exolab.castor.xml.Unmarshaller;
import org.junit.Test;
import org.xml.sax.InputSource;

/**
 * Phase 0 backlog item 17 (MIGRATION.md): full-tree characterisation
 * of the CDISC ODM 1.3 clinical-data unmarshal that
 * {@code ImportCRFDataServlet} drives.
 *
 * <p>Complements {@link CastorClinicalDataUnmarshallerCharacterisationTest}
 * (empty envelopes) by walking a representative non-empty ODM tree —
 * subjects → events → forms → item groups → item data — and asserting
 * every level's bean-tree shape + key fields. Pins the contract that
 * {@code ImportCRFDataServlet.processCsvOrXml} relies on for every CRF
 * data import in production.
 *
 * <p><strong>Phase B.3 gate:</strong> when the production migration of
 * Castor → Jakarta JAXB lands (DR-006, B.3 PR 3/3), the test must
 * continue to pass against the new JAXB unmarshaller. Failure here
 * means the JAXB binding is silently dropping fields the production
 * code relies on; treat it as a release blocker.
 */
public class CastorClinicalDataFullTreeCharacterisationTest {

    /**
     * One subject, one event, one form, one item group, two item-data
     * rows. The smallest input that exercises every level of the bean
     * tree {@code ImportCRFDataServlet} walks.
     */
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
    public void fullOdmTreeUnmarshalsWithCorrectShape() throws Exception {
        ODMContainer parsed = unmarshalViaCastor(FULL_ODM_INPUT);

        // L1 — ODMContainer wraps the ClinicalData payload.
        assertNotNull("ODMContainer must not be null", parsed);
        CRFDataPostImportContainer clinical = parsed.getCrfDataPostImportContainer();
        assertNotNull("CRFDataPostImportContainer must be populated", clinical);
        assertEquals("StudyOID round-trips", "S_MUW_FULL", clinical.getStudyOID());

        // L2 — One SubjectData.
        ArrayList<SubjectDataBean> subjects = clinical.getSubjectData();
        assertNotNull("SubjectData list must not be null", subjects);
        assertEquals("Exactly one SubjectData row", 1, subjects.size());
        SubjectDataBean subject = subjects.get(0);
        assertEquals("SubjectKey → subjectOID",
                "SS_MUW_001", subject.getSubjectOID());

        // L3 — One StudyEventData.
        ArrayList<StudyEventDataBean> events = subject.getStudyEventData();
        assertNotNull("StudyEventData list must not be null", events);
        assertEquals("Exactly one StudyEventData row", 1, events.size());
        StudyEventDataBean event = events.get(0);
        assertEquals("StudyEventOID round-trips",
                "SE_VISIT1", event.getStudyEventOID());
        assertEquals("StudyEventRepeatKey round-trips",
                "1", event.getStudyEventRepeatKey());

        // L4 — One FormData.
        ArrayList<FormDataBean> forms = event.getFormData();
        assertNotNull("FormData list must not be null", forms);
        assertEquals("Exactly one FormData row", 1, forms.size());
        FormDataBean form = forms.get(0);
        assertEquals("FormOID round-trips",
                "F_VITALS", form.getFormOID());
        assertEquals("OpenClinica:Status attribute round-trips to EventCRFStatus",
                "completed", form.getEventCRFStatus());

        // L5 — One ItemGroupData.
        ArrayList<ImportItemGroupDataBean> groups = form.getItemGroupData();
        assertNotNull("ItemGroupData list must not be null", groups);
        assertEquals("Exactly one ItemGroupData row", 1, groups.size());
        ImportItemGroupDataBean group = groups.get(0);
        assertEquals("ItemGroupOID round-trips",
                "IG_VITALS", group.getItemGroupOID());
        assertEquals("ItemGroupRepeatKey round-trips",
                "1", group.getItemGroupRepeatKey());

        // L6 — Two ItemData rows, ordered as in the XML.
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

    /**
     * UpsertOn defaults: when the ODM input omits the UpsertOn element,
     * the bean field stays null (Castor doesn't auto-instantiate). The
     * production import flow null-checks before applying upsert rules.
     */
    @Test
    public void omittedUpsertOnYieldsNullField() throws Exception {
        ODMContainer parsed = unmarshalViaCastor(FULL_ODM_INPUT);
        assertNull("UpsertOn omitted from input → field stays null",
                parsed.getCrfDataPostImportContainer().getUpsertOn());
    }

    /**
     * Production code path lifted from {@code ImportCRFDataServlet.processCsvOrXml(...)}.
     * Castor 1.4.1, {@code cd_odm_mapping.xml}. Mirrors
     * {@link CastorClinicalDataUnmarshallerCharacterisationTest}'s
     * unmarshalViaCastor (intentional duplication so the two tests can
     * diverge under Phase B.3's JAXB swap).
     */
    private static ODMContainer unmarshalViaCastor(String inputXml) throws Exception {
        byte[] mappingBytes = readClasspathResource("/properties/cd_odm_mapping.xml");
        Mapping mapping = new Mapping();
        mapping.loadMapping(new InputSource(new ByteArrayInputStream(mappingBytes)));

        Unmarshaller um1 = new Unmarshaller(mapping);
        ODMContainer odmContainer = new ODMContainer();
        um1.setObject(odmContainer);
        odmContainer = (ODMContainer) um1.unmarshal(new InputSource(new StringReader(inputXml)));
        return odmContainer;
    }

    private static byte[] readClasspathResource(String path) throws Exception {
        try (InputStream in = CastorClinicalDataFullTreeCharacterisationTest.class
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Castor mapping missing: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
