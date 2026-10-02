/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;

import org.junit.Test;
import org.mockito.MockedConstruction;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemGroupDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;

/**
 * checkValidityOfItemOrItemGroupOidInCrf is the rule-import validity check. An
 * item-group OID that is not in the database is the normal "this rule is wrong"
 * case and has to come back as the offending OID, not as an NPE.
 */
public class ExpressionServiceUnknownOidTest {

    @Test
    public void unknownItemGroupOid_inCrfScopedRuleSet_isReportedNotThrown() {
        DataSource ds = mock(DataSource.class);
        try (MockedConstruction<ItemGroupDAO> _ = mockConstruction(ItemGroupDAO.class,
                (m, _) -> when(m.findByOid(anyString())).thenReturn(null))) {

            ExpressionService service = new ExpressionService(ds);
            RuleSetBean ruleSet = new RuleSetBean();
            // A CRF-scoped rule set is what makes the CRF comparison run at all;
            // with crfId null the old code short-circuited and never dereferenced.
            ruleSet.setCrfId(42);

            assertEquals("IG_NOSUCH.I_NOSUCH",
                    service.checkValidityOfItemOrItemGroupOidInCrf("IG_NOSUCH.I_NOSUCH", ruleSet));
        }
    }
}
