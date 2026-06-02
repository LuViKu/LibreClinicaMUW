/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree.OpenClinicaExpressionParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E RX.3 — rule expression test / dry-evaluation surface.
 *
 * <p>Sibling of {@link RulesApiController} (which is rule-set-scoped
 * under {@code /api/v1/rule-sets}). The test-expression endpoint
 * lives under {@code /api/v1/rules} because it is rule-body-scoped:
 * the caller hands over a free-form expression and optional
 * test-value map; the response is the parser's evaluation. No
 * rule_set id is involved.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/rules/test-expression} — parse + evaluate
 *       a rule expression against optional mock values</li>
 * </ul>
 *
 * <p>Authorization: any authenticated user with an active study —
 * the test surface is a read-only sandbox that touches no
 * persistent state, no audit log, no rule_set. Mirrors the legacy
 * {@code TestRuleServlet}'s gate of "study director / coordinator or
 * sysadmin" loosely; the MUW interpretation widens to any
 * authenticated user because the worst outcome of a failed parse is
 * an error message.
 *
 * <h2>Scope limits</h2>
 *
 * <p><b>Constant expressions only.</b> The endpoint wraps
 * {@link OpenClinicaExpressionParser#parseAndTestEvaluateExpression(String, HashMap)}
 * with no {@code ExpressionObjectWrapper}; that means the parser
 * uses the bean-variable code path, which returns {@code null} for
 * non-{@code _CURRENT_DATE} variable references and has no DAO
 * binding to look up item OIDs against the study scope. So this
 * endpoint reliably handles:
 *
 * <ul>
 *   <li>Pure arithmetic: {@code 2 + 2 eq 4}, {@code 3 * 4 gt 10}</li>
 *   <li>Boolean composition: {@code (2 gt 1) and (3 lt 5)}</li>
 *   <li>Date arithmetic: {@code 2026-01-01 + 30 gt 2026-01-15}</li>
 *   <li>The {@code _CURRENT_DATE} constant</li>
 * </ul>
 *
 * <p>It does <b>not</b> resolve study-scoped OID references like
 * {@code ITEM_BP_SYS gt 140}. Operators wanting to test such rules
 * against live data still need the legacy {@code TestRuleServlet}
 * path (which constructs a full {@code ExpressionObjectWrapper}).
 * Surfacing that to the SPA is RX.3b — wiring an
 * {@code ExpressionObjectWrapper} into this endpoint requires
 * controller-level access to the {@code RuleSetBean} target's study
 * scope, which is a larger change than RX.3's task envelope.
 *
 * <p><b>Known parser pitfall.</b>
 * {@code EqualityOpNode.testCalculate()} dereferences
 * {@code left.getNumber().endsWith(".STATUS")} without a null
 * guard, and {@code ArithmeticOpNode.getNumber()} returns
 * {@code null} (it doesn't override the base). So an expression
 * like {@code 2 + 2 eq 4} (left operand of {@code eq} is an
 * arithmetic sub-tree) NPEs inside the parser. The same expression
 * spelled as {@code 2 + 2 gt 3} ({@code RelationalOpNode}) works.
 * The controller surfaces the NPE as a generic 500 via
 * {@link ApiExceptionHandler}; the parser bug is upstream and out
 * of RX.3 scope. SPA UX guidance: prefer {@code gt}/{@code lt}
 * comparisons for range checks (which is the realistic clinical
 * pattern anyway).
 *
 * <p>Locale handling: the parser raises
 * {@link OpenClinicaSystemException}s carrying error codes like
 * {@code OCRERR_0005} (syntax error) / {@code OCRERR_0010}
 * (unexpected character). We bind the {@link ResourceBundleProvider}
 * thread-local before parsing so {@code page_messages} resolves
 * even though the request didn't traverse the legacy locale filter
 * chain — same pattern as
 * {@code CrfSpreadsheetParserService.parseAndPersist}. The
 * {@code Accept-Language} header is honoured when present (parsed
 * via {@link Locale.LanguageRange#parse(String)}); falls back to
 * {@link Locale#ENGLISH} otherwise.
 */
@RestController
@RequestMapping("/api/v1/rules")
@Tag(name = "Rules — test", description = "Parse + test-evaluate rule expressions without persisting anything.")
public class RuleExpressionApiController {

    private static final Logger LOG = LoggerFactory.getLogger(RuleExpressionApiController.class);

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rules/test-expression                                 */
    /* ----------------------------------------------------------------- */

    @PostMapping("/test-expression")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = TestExpressionResponse.class)))
    public ResponseEntity<?> testExpression(@RequestBody(required = false) TestExpressionRequest body,
                                            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
                                            HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;

        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "expression",
                            "message", "Request body is required"))));
        }
        String expression = body.expression();
        if (expression == null || expression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "expression",
                            "message", "Expression must not be blank"))));
        }

        Locale locale = resolveLocale(acceptLanguage);
        ResourceBundleProvider.updateLocale(locale);
        ResourceBundle respage;
        try {
            respage = ResourceBundleProvider.getPageMessagesBundle(locale);
        } catch (RuntimeException rbe) {
            // Pathological — the page_messages bundle should always
            // be on the classpath. Fall back to a null bundle and
            // surface the raw error code on parse failure rather
            // than crashing the request.
            LOG.warn("page_messages bundle unavailable for locale {}; error codes will not be resolved",
                    locale, rbe);
            respage = null;
        }

        HashMap<String, String> testValues = new HashMap<>();
        if (body.testValues() != null) {
            for (Map.Entry<String, String> e : body.testValues().entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    testValues.put(e.getKey(), e.getValue());
                }
            }
        }

        // Parser is per-request (instance-scoped state); ok to
        // construct on every call. No DataSource / wrapper — see
        // class javadoc on the "constant expressions only" scope.
        OpenClinicaExpressionParser parser = new OpenClinicaExpressionParser();

        String result;
        try {
            if (testValues.isEmpty()) {
                result = parser.parseAndTestEvaluateExpression(expression);
            } else {
                HashMap<String, String> response =
                        parser.parseAndTestEvaluateExpression(expression, testValues);
                result = response.get("result");
            }
        } catch (OpenClinicaSystemException ose) {
            String detailedMessage = resolveErrorMessage(ose, respage);
            LOG.debug("test-expression parse failure for expression='{}': {}", expression, detailedMessage);
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "expression",
                            "message", detailedMessage))));
        }

        return ResponseEntity.ok(new TestExpressionResponse(
                result == null ? "" : result,
                Instant.now().toString()));
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /**
     * 401 if no userBean, 400 if no active study. Mirrors
     * {@link RulesApiController#preflight(HttpSession)} so the error
     * envelope shape stays uniform across the two rules controllers.
     */
    private static ResponseEntity<?> preflight(HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean study = (StudyBean) session.getAttribute("study");
        if (study == null || study.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        return null;
    }

    /**
     * Parse an {@code Accept-Language} header into a {@link Locale}.
     * Falls back to {@link Locale#ENGLISH} when the header is absent,
     * blank, or malformed. Same pattern as the A8.3 CRF parser
     * adapter.
     */
    private static Locale resolveLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Locale.ENGLISH;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            if (ranges.isEmpty()) return Locale.ENGLISH;
            Locale.Builder b = new Locale.Builder();
            return b.setLanguageTag(ranges.get(0).getRange().replace('_', '-')).build();
        } catch (IllegalArgumentException iae) {
            return Locale.ENGLISH;
        }
    }

    /**
     * Resolve a parser {@link OpenClinicaSystemException} to a
     * human-readable message. Falls back to the raw error code (e.g.
     * {@code "OCRERR_0010"}) when the bundle lookup misses or the
     * exception carries no code — both surface enough information
     * for the SPA to render a useful toast without leaking stack
     * trace.
     */
    private static String resolveErrorMessage(OpenClinicaSystemException ose, ResourceBundle respage) {
        String errorCode = ose.getErrorCode();
        if (errorCode == null || errorCode.isBlank()) {
            return ose.getMessage() == null ? "Parse error" : ose.getMessage();
        }
        if (respage == null) {
            // bundle unavailable — return raw code so the SPA can
            // still distinguish failure modes
            return errorCode;
        }
        try {
            String pattern = respage.getString(errorCode);
            MessageFormat mf = new MessageFormat("");
            mf.applyPattern(pattern);
            Object[] arguments = ose.getErrorParams();
            String formatted = mf.format(arguments == null ? new Object[0] : arguments).trim();
            return errorCode + " : " + formatted;
        } catch (MissingResourceException mre) {
            return errorCode;
        } catch (IllegalArgumentException iae) {
            // MessageFormat patterns can fail on unbalanced braces;
            // fall back to the raw bundle string.
            return errorCode + " : " + respage.getString(errorCode);
        }
    }

    /* ----------------------------------------------------------------- */
    /* Phase E RX.3 dry-run deferral                                      */
    /* ----------------------------------------------------------------- */

    /*
     * The matching POST /api/v1/rule-sets/{id}/dry-run endpoint was
     * planned alongside this controller but DEFERRED — see the
     * Phase E RX.3 PR body for the safety analysis. Short form:
     * three ActionProcessor subclasses (Show, Hide, Insert) have a
     * missing `return`/`break` inside their `case DRY_RUN:` switch
     * arm and silently fall through to `case SAVE:`. That makes the
     * legacy {@code RuleSetService.runRulesInBulk(.., dryRun=true)}
     * NOT side-effect-free for studies carrying those action types.
     * Until the core/ fix lands, exposing a dry-run REST endpoint
     * would risk silent writes (e.g. an {@code Insert} action firing
     * for real during what the operator believes is a dry run).
     * Ship the test-expression endpoint only; revisit the dry-run
     * surface once the processor bug is closed.
     */

    /**
     * Public no-arg constructor. The controller carries no
     * collaborators — the parser is per-request, locale binding is a
     * thread-local on {@link ResourceBundleProvider}, and no DAO or
     * DataSource is touched. Spring's component scan + MockMvc
     * standaloneSetup both use this directly.
     */
    public RuleExpressionApiController() {
        // no-op
    }
}
