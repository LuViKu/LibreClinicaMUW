/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The stylesheet runner behind the SAS export.
 *
 * <p>Until 2026-09 the only code that could apply an extract stylesheet lived
 * inside the Quartz scheduled-job screens, so every other export route wrote an
 * empty file. These pin the runner itself rather than the export around it:
 * that it resolves a packaged stylesheet without the application context having
 * copied it to disk, that it runs XSLT 2.0, and that it refuses rather than
 * quietly returning nothing.
 */
public class OdmXsltTransformerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * A minimal ODM document in the shape the SAS stylesheets walk: one study,
     * one metadata version with a form, an item group and an item, and one
     * subject's clinical data.
     */
    private static final String MINIMAL_ODM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:OpenClinica="http://www.openclinica.org/ns/odm_ext_v130/v3.1"
                 ODMVersion="1.3" FileType="Snapshot" FileOID="TEST_ODM">
              <Study OID="S_TESTONE">
                <GlobalVariables>
                  <StudyName>Test One</StudyName>
                  <StudyDescription/>
                  <ProtocolName>TEST1</ProtocolName>
                </GlobalVariables>
                <MetaDataVersion OID="v1.0.0" Name="MetaDataVersion_v1.0.0">
                  <StudyEventDef OID="SE_VISIT" Name="Visit" Repeating="No" Type="Scheduled">
                    <FormRef FormOID="F_DEMO" Mandatory="Yes"/>
                  </StudyEventDef>
                  <FormDef OID="F_DEMO" Name="Demographics - 1.0" Repeating="No">
                    <ItemGroupRef ItemGroupOID="IG_DEMO_UNGROUPED" Mandatory="Yes"/>
                  </FormDef>
                  <ItemGroupDef OID="IG_DEMO_UNGROUPED" Name="Ungrouped" Repeating="No" SASDatasetName="IG_DEMO">
                    <ItemRef ItemOID="I_DEMO_HEIGHT" Mandatory="Yes"/>
                    <OpenClinica:ItemGroupDetails>
                      <OpenClinica:PresentInForm FormOID="F_DEMO"/>
                    </OpenClinica:ItemGroupDetails>
                  </ItemGroupDef>
                  <ItemDef OID="I_DEMO_HEIGHT" Name="HEIGHT_CM" DataType="integer" SASFieldName="HEIGHT"/>
                </MetaDataVersion>
              </Study>
              <ClinicalData StudyOID="S_TESTONE" MetaDataVersionOID="v1.0.0">
                <SubjectData SubjectKey="SS_0001" OpenClinica:StudySubjectID="M-001">
                  <StudyEventData StudyEventOID="SE_VISIT">
                    <FormData FormOID="F_DEMO">
                      <ItemGroupData ItemGroupOID="IG_DEMO_UNGROUPED" ItemGroupRepeatKey="1">
                        <ItemData ItemOID="I_DEMO_HEIGHT" Value="162"/>
                      </ItemGroupData>
                    </FormData>
                  </StudyEventData>
                </SubjectData>
              </ClinicalData>
            </ODM>
            """;

    private File odmFile() throws Exception {
        File f = tmp.newFile("odm.xml");
        Files.writeString(f.toPath(), MINIMAL_ODM, StandardCharsets.UTF_8);
        return f;
    }

    private static final List<String> SAS = List.of(
            "xml_convert_sas_map.xsl", "xml_convert_sas_data.xsl", "xml_convert_sas_format.xsl");

    /**
     * The packaged stylesheets are found on the classpath. The application
     * copies them to disk at boot, but a test, a fresh container, or a run
     * before that copy step must still work rather than fail late.
     */
    @Test
    public void resolvesPackagedStylesheetsWithoutTheOnDiskCopy() throws Exception {
        List<String> out = OdmXsltTransformer.transform(odmFile(), SAS);
        assertEquals("one result per stylesheet, in order", 3, out.size());
        for (String s : out) {
            assertTrue("a stylesheet produced nothing", s != null && !s.isBlank());
        }
    }

    /** The map stylesheet declares version 2.0, so the default 1.0 processor cannot run it. */
    @Test
    public void runsAnXslt2Stylesheet() throws Exception {
        String map = OdmXsltTransformer.transform(odmFile(), List.of("xml_convert_sas_map.xsl")).get(0);
        assertTrue("expected an SXLEMAP document, got: " + head(map), map.contains("SXLEMAP"));
    }

    @Test
    public void theDataDocumentCarriesTheSubjectAndItsValue() throws Exception {
        String data = OdmXsltTransformer.transform(odmFile(), List.of("xml_convert_sas_data.xsl")).get(0);
        assertTrue("subject label missing from: " + head(data), data.contains("M-001"));
        assertTrue("item value missing from: " + head(data), data.contains("162"));
    }

    /**
     * The syntax file names the other two by filename. Renaming an entry
     * produces a script that cannot find its own data, which is why the export
     * fixes those names rather than deriving them from the dataset.
     */
    @Test
    public void theSyntaxFileReferencesItsCompanionsByName() throws Exception {
        String sas = OdmXsltTransformer.transform(odmFile(), List.of("xml_convert_sas_format.xsl")).get(0);
        assertTrue(sas.contains("SAS_DATA.xml"));
        assertTrue(sas.contains("SAS_MAP.xml"));
        assertTrue(sas.contains("LIBNAME"));
    }

    @Test
    public void noStylesheetsMeansNoResults() throws Exception {
        assertTrue(OdmXsltTransformer.transform(odmFile(), List.of()).isEmpty());
    }

    /** A missing source must fail loudly: an empty export that looks fine is the defect being fixed. */
    @Test
    public void aMissingOdmIsRefused() {
        try {
            OdmXsltTransformer.transform(new File(tmp.getRoot(), "absent.xml"), SAS);
            fail("expected a missing ODM source to be refused");
        } catch (Exception expected) {
            assertTrue(expected instanceof IllegalArgumentException);
        }
    }

    @Test
    public void anEmptyOdmIsRefused() throws Exception {
        File empty = tmp.newFile("empty.xml");
        try {
            OdmXsltTransformer.transform(empty, SAS);
            fail("expected an empty ODM source to be refused");
        } catch (Exception expected) {
            assertTrue(expected instanceof IllegalArgumentException);
        }
    }

    /** Stylesheet names come from a config file an administrator edits. */
    @Test
    public void aStylesheetNameCannotEscapeItsDirectory() throws Exception {
        for (String bad : List.of("../secrets.xsl", "sub/dir.xsl", "..")) {
            try {
                OdmXsltTransformer.transform(odmFile(), List.of(bad));
                fail("expected '" + bad + "' to be refused");
            } catch (Exception expected) {
                assertTrue(expected.getMessage(), expected instanceof java.io.IOException);
            }
        }
    }

    @Test
    public void anUnknownStylesheetIsReported() throws Exception {
        try {
            OdmXsltTransformer.transform(odmFile(), List.of("no_such_stylesheet.xsl"));
            fail("expected an unknown stylesheet to be refused");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("no_such_stylesheet.xsl"));
        }
    }

    private static String head(String s) {
        return s == null ? "null" : s.substring(0, Math.min(200, s.length()));
    }
}
