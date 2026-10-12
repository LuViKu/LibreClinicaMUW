package at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The constructor used to hand the context path to the superclass in place of
 * the request URL, so links built by the rule runner lost their host.
 */
public class BeanPropertyRuleRunnerUrlTest {

    @Test
    public void constructorKeepsRequestUrlAndContextPathApart() {
        BeanPropertyRuleRunner runner = new BeanPropertyRuleRunner(null, "https://ecrf.example.org/LibreClinica", "/LibreClinica", null);

        assertEquals("https://ecrf.example.org/LibreClinica", runner.requestURLMinusServletPath);
        assertEquals("/LibreClinica", runner.contextPath);
    }
}
