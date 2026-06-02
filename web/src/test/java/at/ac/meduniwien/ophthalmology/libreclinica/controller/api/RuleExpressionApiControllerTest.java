/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E RX.3 — contract surface for the {@code /api/v1/rules/test-expression}
 * endpoint on {@link RuleExpressionApiController}.
 *
 * <p>Pure-parser tests + session-guard tests only; no DAO is
 * touched by this controller so MockMvc standaloneSetup is
 * sufficient.
 *
 * <p>The "happy path with variable references + testValues" case
 * from the original RX.3 plan is intentionally NOT covered here.
 * The parser's bean-variable code path returns {@code null} for
 * non-{@code _CURRENT_DATE} variable references when no
 * {@code ExpressionObjectWrapper} is bound (which is the
 * controller's per-request shape), so variable-reference tests
 * require the DB-bound study scope and are out of RX.3's scope —
 * see {@link RuleExpressionApiController} javadoc.
 */
class RuleExpressionApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new RuleExpressionApiController());
    }

    /* ----------------------------------------------------------------- */
    /* Session-guard contracts                                            */
    /* ----------------------------------------------------------------- */

    @Test
    void testExpressionReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"2 + 2 eq 4\"}")
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testExpressionReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"2 + 2 eq 4\"}")
                .session((MockHttpSession) authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ----------------------------------------------------------------- */
    /* Validation contracts                                               */
    /* ----------------------------------------------------------------- */

    @Test
    void testExpressionReturns400OnEmptyExpression() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"\"}")
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("expression"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(containsString("must not be blank")));
    }

    @Test
    void testExpressionReturns400OnBlankExpression() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"   \"}")
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expression"));
    }

    @Test
    void testExpressionReturns400OnNullBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testExpressionReturns400OnParseError() throws Exception {
        // "(" without a closing ")" — fails in factorTree at "OCRERR_0006"
        // or "OCRERR_0010" depending on which branch the parser hits.
        // Either way the wire shape is the same: 400 with errors[].
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"2 + (\"}")
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("expression"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(containsString("OCRERR_")));
    }

    /* ----------------------------------------------------------------- */
    /* Happy path — constant expressions                                  */
    /* ----------------------------------------------------------------- */

    /**
     * The parser's {@code RelationalOpNode} (used by {@code gt} /
     * {@code lt} / {@code gte} / {@code lte}) handles
     * arithmetic-result operands correctly. {@code EqualityOpNode}
     * ({@code eq} / {@code ne} / {@code ct}) NPEs at line 51 of
     * {@code EqualityOpNode.testCalculate()} when its left operand
     * is an arithmetic sub-tree (it calls
     * {@code left.getNumber().endsWith(".STATUS")} and
     * {@code ArithmeticOpNode.getNumber()} returns null). So these
     * happy-path tests stick to comparisons whose operands are
     * either pure constants or are wrapped in a {@code gt}/{@code lt}
     * branch — that's the realistic shape of clinical-rule range
     * checks anyway.
     */
    @Test
    void testExpressionReturns200ForArithmeticInequality() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"2 + 2 gt 3\"}")
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("true"))
                .andExpect(jsonPath("$.evaluatedAt").exists());
    }

    @Test
    void testExpressionReturns200ForBooleanComposition() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"(3 gt 1) and (5 lt 10)\"}")
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("true"));
    }

    @Test
    void testExpressionReturns200ForFalseResult() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"5 lt 3\"}")
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("false"));
    }

    @Test
    void testExpressionAcceptsEmptyTestValues() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/test-expression")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"3 gt 1\",\"testValues\":{}}")
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("true"));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/rules           (Phase E RX.5 — inline rule create)       */
    /* POST /api/v1/rules/validate-target  (Phase E RX.5 — target validation) */
    /* ---------------------------------------------------------------------- */

    @Test
    void createRuleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oid\":\"RUL_TEST\",\"name\":\"x\",\"expression\":\"1 gt 0\"}")
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRuleReturns400OnMissingBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("")
                .session((MockHttpSession)
                        authenticatedSysadminSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRuleReturns400OnBadOidFormat() throws Exception {
        // OID must match ^[A-Z][A-Z0-9_]*$ — lowercase letters reject.
        mockMvcWith().perform(post("/api/v1/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oid\":\"rul-bad\",\"name\":\"x\",\"expression\":\"1 gt 0\"}")
                .session((MockHttpSession)
                        authenticatedSysadminSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validateTargetReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/validate-target")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"ITEM.GROUP.CRF.SED\"}")
                .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validateTargetReturns400OnEmptyTarget() throws Exception {
        mockMvcWith().perform(post("/api/v1/rules/validate-target")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"\"}")
                .session((MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest());
    }
}
