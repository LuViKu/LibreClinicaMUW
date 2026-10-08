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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.SubjectDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ImportCRFInfoContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionRunBean.Phase;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ImportDataRuleRunnerContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;

/**
 * The other two readers of an import file's repeat keys, against a real
 * database: the post-import container ({@link ImportCRFInfoContainer}) and
 * the import's rule run ({@link ImportDataRuleRunnerContainer}). A key that is
 * not a whole number names no visit or row, so they skip it; it is neither
 * read as visit or row 1 nor fails the import with NumberFormatException.
 *
 * <p>Runs against the demo seed: subject SS_M001, event definition
 * SE_V1_INCLUSION and form F_DEMOGRAPHICS_V1 in S_DEFAULTS1 (study 1).
 */
class ImportRepeatKeyReadersDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static ODMContainer importFile(String eventKeyAttribute, String groupKeyAttribute) {
        String xml = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\" ODMVersion=\"1.3\" FileType=\"Snapshot\"\n"
                + "     FileOID=\"LCMUW_KEY_READERS_IT\" CreationDateTime=\"2026-10-02T00:00:00Z\">\n"
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

    /* ------------------------------------------------------------------ */
    /* ImportCRFInfoContainer                                              */
    /* ------------------------------------------------------------------ */

    @Test
    void thePostImportContainerSkipsAVisitKeyThatIsNotANumber() {
        ImportCRFInfoContainer container =
                new ImportCRFInfoContainer(importFile(" StudyEventRepeatKey=\"abc\"", ""), DATA_SOURCE);

        assertTrue(container.getImportCRFList().isEmpty(), "no visit is guessed for the key");
    }

    @Test
    void thePostImportContainerFindsTheVisitANumericKeyNames() {
        ImportCRFInfoContainer container =
                new ImportCRFInfoContainer(importFile(" StudyEventRepeatKey=\"1\"", ""), DATA_SOURCE);

        assertFalse(container.getImportCRFList().isEmpty());
    }

    /* ------------------------------------------------------------------ */
    /* ImportDataRuleRunnerContainer                                       */
    /* ------------------------------------------------------------------ */

    /** A rule service holding one rule set for the form, that runs on import. */
    private static RuleSetServiceInterface ruleService() {
        RuleSetServiceInterface rules = mock(RuleSetServiceInterface.class);
        List<RuleSetBean> ruleSets = new ArrayList<>(List.of(new RuleSetBean()));
        when(rules.getRuleSetsByCrfStudyAndStudyEventDefinition(any(), any(), any())).thenReturn(ruleSets);
        when(rules.filterByStatusEqualsAvailable(anyList())).thenReturn(ruleSets);
        when(rules.filterRuleSetsByStudyEventOrdinal(anyList(), any(), any(), any())).thenReturn(ruleSets);
        when(rules.shouldRunRulesForRuleSets(anyList(), any(Phase.class))).thenReturn(Boolean.TRUE);
        when(rules.solidifyGroupOrdinalsUsingFormProperties(anyList(), any())).thenReturn(ruleSets);
        return rules;
    }

    private static ImportDataRuleRunnerContainer runRules(ODMContainer file, RuleSetServiceInterface rules) {
        StudyBean study = new StudyDAO(DATA_SOURCE).findByPK(1);
        SubjectDataBean subject = file.getCrfDataPostImportContainer().getSubjectData().get(0);
        ImportDataRuleRunnerContainer container = new ImportDataRuleRunnerContainer();
        container.initRuleSetsAndTargets(DATA_SOURCE, study, subject, rules);
        return container;
    }

    @Test
    void theRuleRunSkipsAVisitKeyThatIsNotANumber() {
        RuleSetServiceInterface rules = ruleService();

        ImportDataRuleRunnerContainer container = runRules(importFile(" StudyEventRepeatKey=\"abc\"", ""), rules);

        verify(rules, never()).getRuleSetsByCrfStudyAndStudyEventDefinition(any(), any(), any());
        assertTrue(container.getVariableAndValue().isEmpty());
    }

    @Test
    void theRuleRunSkipsAGroupKeyThatIsNotANumber() {
        ImportDataRuleRunnerContainer container =
                runRules(importFile("", " ItemGroupRepeatKey=\"abc\""), ruleService());

        assertTrue(container.getVariableAndValue().isEmpty(), "the value is not read as row 1's: "
                + container.getVariableAndValue());
    }

    @Test
    void theRuleRunReadsTheValuesOfGoodKeys() {
        assertEquals("175", runRules(importFile(" StudyEventRepeatKey=\"1\"", ""), ruleService())
                .getVariableAndValue().get("I_HEIGHT_CM"));
        assertEquals("175", runRules(importFile("", " ItemGroupRepeatKey=\" 2 \""), ruleService())
                .getVariableAndValue().get("IG_DEMOG_UNGROUPED[2].I_HEIGHT_CM"));
    }
}
