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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.Context;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionObjectWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree.OpenClinicaExpressionParser;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression.ExpressionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
@Tag(name = "Rules — test", description = "Parse + test-evaluate rule expressions, validate target expressions, and create rule bodies.")
public class RuleExpressionApiController {

    private static final Logger LOG = LoggerFactory.getLogger(RuleExpressionApiController.class);

    /**
     * Phase E RX.5 — operator-supplied OID grammar for new rules.
     *
     * <p>Tightened from the underlying {@code OidGenerator} validator
     * ({@code ^[A-Z_0-9]+$}, max 40) — must start with a letter, then
     * letters / digits / underscores. Mirrors the canonical legacy
     * convention (e.g. {@code RUL_BP_HIGH}) so operator-authored OIDs
     * are visually consistent with the OIDs the import path produces.
     */
    private static final Pattern RULE_OID_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** Max length per legacy {@code rule.oc_oid} column width. */
    private static final int OID_MAX_LENGTH = 40;
    private static final int NAME_MAX_LENGTH = 255;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;

    /**
     * RX.5 collaborators — only used by {@link #createRule} and
     * {@link #validateTarget}. The {@code test-expression} endpoint
     * touches neither field; both are nullable for the no-arg
     * constructor used by MockMvc tests that exercise the test-
     * expression surface in isolation.
     */
    private final DataSource dataSource;
    private final RuleDao ruleDao;

    @Autowired
    public RuleExpressionApiController(@Qualifier("dataSource") DataSource dataSource,
                                       RuleDao ruleDao) {
        this.dataSource = dataSource;
        this.ruleDao = ruleDao;
    }

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
    /* Phase E.5 RX.3b — dry-run UNDEFERRED                               */
    /* ----------------------------------------------------------------- */

    /*
     * The dry-run preview is now exposed at
     * POST /api/v1/rule-sets/{id}/dry-run on RulesApiController.
     * The original RX.3 deferral was gated on the Show/Hide/Insert
     * ActionProcessor fall-through bug (case DRY_RUN: missing return,
     * falling through to case SAVE:). RX.3b closed that bug in the
     * same PR (commit lands fixes to ShowActionProcessor +
     * HideActionProcessor + InsertActionProcessor: each `dryRun(...)`
     * call now returns directly instead of falling through). With
     * the upstream fix in, runRulesInBulk(.., dryRun=true) is
     * genuinely side-effect-free and the REST surface is safe to
     * expose. See RulesApiController.dryRun(...).
     */

    /**
     * Public no-arg constructor — kept for the original
     * {@code test-expression} MockMvc tests that pre-date RX.5. Passes
     * {@code null} into the RX.5 collaborators; calling
     * {@link #createRule} or {@link #validateTarget} on an instance
     * constructed this way will NPE — those tests must use the
     * two-arg constructor with the collaborators they need.
     */
    public RuleExpressionApiController() {
        this(null, null);
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rules            — Phase E RX.5 inline rule body      */
    /* ----------------------------------------------------------------- */

    /**
     * Create a new {@code rule} (a named expression body) in the
     * active study. The matching {@code POST /api/v1/rule-sets} on
     * {@link RulesApiController} attaches one or more rules to a
     * target.
     *
     * <p>Mirrors the per-record chunk of
     * {@code RulesPostImportContainerService.validateRuleDefs} —
     * tightened OID grammar, length checks, body expression
     * syntax/scope validation against the active study, OID
     * uniqueness within the study.
     *
     * <h2>Validation</h2>
     * <ul>
     *   <li>{@code oid} — required. Must match
     *       {@link #RULE_OID_PATTERN}, ≤{@value #OID_MAX_LENGTH} chars,
     *       and must not collide with an existing rule in the same
     *       study.</li>
     *   <li>{@code name} — required. ≤{@value #NAME_MAX_LENGTH}
     *       chars.</li>
     *   <li>{@code description} — optional. ≤{@value #DESCRIPTION_MAX_LENGTH}
     *       chars.</li>
     *   <li>{@code expression} — required. Validated via
     *       {@code ExpressionService.ruleExpressionChecker} bound to
     *       the active study scope.</li>
     * </ul>
     *
     * <p>Persistence: builds a {@link RuleBean} with its
     * {@link ExpressionBean}, sets {@code studyId}, {@code owner},
     * {@code status = AVAILABLE}, and saves via
     * {@code ruleDao.saveOrUpdate}. Audit row written via
     * {@link AuditEventDAO}.
     */
    @PostMapping
    @Transactional
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = CreateRuleResponse.class)))
    public ResponseEntity<?> createRule(@RequestBody(required = false) CreateRuleRequest body,
                                        HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit rule create"));
        }

        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Request body is required: { oid, name, description?, expression }"));
        }

        List<Map<String, String>> errors = new ArrayList<>();
        String oid = body.oid() == null ? "" : body.oid().trim();
        if (oid.isEmpty()) {
            errors.add(Map.of("field", "oid", "message", "oid is required"));
        } else if (oid.length() > OID_MAX_LENGTH) {
            errors.add(Map.of("field", "oid", "message",
                    "oid must be at most " + OID_MAX_LENGTH + " characters"));
        } else if (!RULE_OID_PATTERN.matcher(oid).matches()) {
            errors.add(Map.of("field", "oid", "message",
                    "oid must match " + RULE_OID_PATTERN.pattern()
                            + " (start with a letter, then letters / digits / underscores)"));
        }
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) {
            errors.add(Map.of("field", "name", "message", "name is required"));
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.add(Map.of("field", "name", "message",
                    "name must be at most " + NAME_MAX_LENGTH + " characters"));
        }
        String description = body.description() == null ? "" : body.description().trim();
        if (description.length() > DESCRIPTION_MAX_LENGTH) {
            errors.add(Map.of("field", "description", "message",
                    "description must be at most " + DESCRIPTION_MAX_LENGTH + " characters"));
        }
        String expression = body.expression() == null ? "" : body.expression().trim();
        if (expression.isEmpty()) {
            errors.add(Map.of("field", "expression", "message", "expression is required"));
        }

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", errors));
        }

        // OID uniqueness — within this study only. The legacy import
        // path treats a same-OID rule as a "duplicate" the operator
        // can overwrite; the inline RX.5 path keeps it simple and
        // refuses the second create. Operators wanting to replace a
        // rule body use the future per-rule PUT (RX.6).
        RuleBean existing = ruleDao.findByOid(oid, study.getId());
        if (existing != null) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Rule oid '" + oid + "' already exists in study '"
                            + study.getOid() + "'"));
        }

        // Body expression syntax + scope validation. Build a
        // per-request ExpressionService bound to the active study.
        ExpressionBean exprBean = new ExpressionBean(Context.OC_RULES_V1, expression);
        ExpressionObjectWrapper eow = new ExpressionObjectWrapper(
                dataSource, study, exprBean, ExpressionObjectWrapper.CONTEXT_EXPRESSION);
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        ExpressionService perRequestExprSvc = new ExpressionService(eow);
        try {
            if (!perRequestExprSvc.ruleExpressionChecker(expression)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "expression",
                                "message", "Expression failed validation"))));
            }
        } catch (OpenClinicaSystemException ose) {
            String code = ose.getErrorCode() == null ? "OCRERR_unknown" : ose.getErrorCode();
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "expression",
                            "message", code + " : "
                                    + (ose.getMessage() == null ? "expression invalid" : ose.getMessage())))));
        } catch (RuntimeException rte) {
            LOG.debug("createRule: expression checker threw {}", rte.toString());
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "expression",
                            "message", rte.getMessage() == null ? "Expression invalid" : rte.getMessage()))));
        }

        RuleBean rule = new RuleBean();
        rule.setOid(oid);
        rule.setName(name);
        rule.setDescription(description);
        rule.setExpression(exprBean);
        rule.setStudy(study);
        rule.setStudyId(study.getId());
        rule.setStatus(Status.AVAILABLE);
        rule.setOwner(me);
        rule.setCreatedDate(new java.util.Date());
        rule.setEnabled(true);

        RuleBean persisted = ruleDao.saveOrUpdate(rule);
        if (persisted == null || persisted.getId() == null || persisted.getId() == 0) {
            LOG.warn("createRule: ruleDao.saveOrUpdate returned no id for oid='{}'", oid);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist rule"));
        }

        writeRuleCreateAudit(me, study, persisted);
        LOG.info("Create rule: id={} oid='{}' study={} by={}",
                persisted.getId(), oid, study.getOid(), me.getName());

        return ResponseEntity.status(201).body(new CreateRuleResponse(
                persisted.getId(),
                persisted.getOid(),
                persisted.getName(),
                persisted.getDescription(),
                persisted.getExpression() == null ? "" : persisted.getExpression().getValue()));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rules/validate-target  — Phase E RX.5 target probe    */
    /* ----------------------------------------------------------------- */

    /**
     * Validate a {@code rule_set} target expression against the
     * active study scope. Read-only — nothing is persisted.
     *
     * <p>Wraps {@code ExpressionService.ruleSetExpressionChecker} so
     * the SPA can live-validate the target as the operator types
     * (debounced). Always returns HTTP 200; the validation outcome
     * lives in the {@code valid} flag + {@code errors} list of the
     * {@link ValidateTargetResponse} body, not in the status code —
     * lets the SPA distinguish "expression syntax is fine but the
     * OID doesn't resolve in this study" from "the network call
     * failed".
     *
     * <p>A missing or blank {@code target} still surfaces as HTTP 400
     * (the request body shape is wrong), separate from the validation
     * outcome shape.
     */
    @PostMapping("/validate-target")
    @Transactional(readOnly = true)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ValidateTargetResponse.class)))
    public ResponseEntity<?> validateTarget(@RequestBody(required = false) ValidateTargetRequest body,
                                            HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        StudyBean study = (StudyBean) session.getAttribute("study");

        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Request body is required: { target }"));
        }
        String target = body.target() == null ? "" : body.target().trim();
        if (target.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "target",
                            "message", "target must not be blank"))));
        }

        ExpressionBean targetExpr = new ExpressionBean(Context.OC_RULES_V1, target);
        ExpressionObjectWrapper eow = new ExpressionObjectWrapper(
                dataSource, study, targetExpr, ExpressionObjectWrapper.CONTEXT_TARGET);
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        ExpressionService perRequestExprSvc = new ExpressionService(eow);
        try {
            boolean ok = perRequestExprSvc.ruleSetExpressionChecker(target);
            if (ok) {
                return ResponseEntity.ok(new ValidateTargetResponse(true, List.of()));
            }
            return ResponseEntity.ok(new ValidateTargetResponse(false, List.of(
                    new ValidateTargetResponse.ValidationIssue("Target expression failed validation"))));
        } catch (OpenClinicaSystemException ose) {
            String code = ose.getErrorCode() == null ? "OCRERR_unknown" : ose.getErrorCode();
            String msg = code + " : "
                    + (ose.getMessage() == null ? "target invalid" : ose.getMessage());
            return ResponseEntity.ok(new ValidateTargetResponse(false, List.of(
                    new ValidateTargetResponse.ValidationIssue(msg))));
        } catch (RuntimeException rte) {
            LOG.debug("validateTarget: checker threw {}", rte.toString());
            return ResponseEntity.ok(new ValidateTargetResponse(false, List.of(
                    new ValidateTargetResponse.ValidationIssue(
                            rte.getMessage() == null ? "Target invalid" : rte.getMessage()))));
        }
    }

    /* ----------------------------------------------------------------- */
    /* PUT /api/v1/rules/{id}        — Phase E RX.6 per-rule edit         */
    /* ----------------------------------------------------------------- */

    /**
     * Update a {@code rule}'s {@code name}, {@code description},
     * and/or {@code expression} body. Per-field optional: a
     * {@code null} field on the request body leaves the persisted
     * value alone, so the SPA submits only the fields the operator
     * actually edited.
     *
     * <p>Authorization: same gate as the RX.5 create — sysadmin OR
     * study director/coordinator bound to the active study, via
     * {@link StudyAdminAuthorization#roleMayEditStudy}.
     *
     * <h2>Validation</h2>
     * <ul>
     *   <li>404 if the rule doesn't exist OR belongs to a different
     *       study (the controller cross-checks
     *       {@code rule.studyId == study.id}).</li>
     *   <li>400 if {@code name} is present but blank or
     *       &gt;{@value #NAME_MAX_LENGTH} chars.</li>
     *   <li>400 if {@code description} is present and
     *       &gt;{@value #DESCRIPTION_MAX_LENGTH} chars.</li>
     *   <li>400 if {@code expression} is present and either blank or
     *       fails {@code ExpressionService.ruleExpressionChecker}
     *       against the active study scope.</li>
     * </ul>
     *
     * <h2>Audit</h2>
     *
     * <p>One {@code audit_log_event} row per actually-changed field,
     * matching the RX.7 per-field-diff pattern. So a single PUT can
     * emit 0..3 rows depending on which fields the operator touched
     * that differ from the persisted values. {@code auditTable =
     * "rule"}, {@code entityId = rule.id}, {@code columnName ∈
     * {name, description, expression}}.
     *
     * <p>Response shape: {@link CreateRuleResponse} (same shape as
     * the create endpoint) so the SPA can swap the row in-place
     * without a second GET round-trip.
     */
    @PutMapping("/{ruleId}")
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = CreateRuleResponse.class)))
    public ResponseEntity<?> updateRule(@PathVariable("ruleId") int ruleId,
                                        @RequestBody(required = false) UpdateRuleRequest body,
                                        HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit rule edit"));
        }

        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Request body is required: { name?, description?, expression? }"));
        }

        // Per-field validation runs before the DAO lookup so the
        // cheapest rejections happen first — fixed-shape input
        // problems don't need to hit the database to surface as 400s.
        List<Map<String, String>> errors = new ArrayList<>();
        String newName = null;
        if (body.name() != null) {
            newName = body.name().trim();
            if (newName.isEmpty()) {
                errors.add(Map.of("field", "name", "message", "name must not be blank"));
            } else if (newName.length() > NAME_MAX_LENGTH) {
                errors.add(Map.of("field", "name", "message",
                        "name must be at most " + NAME_MAX_LENGTH + " characters"));
            }
        }
        String newDescription = null;
        if (body.description() != null) {
            // Description tolerates blank — the operator might want to
            // wipe a previously-set description back to empty.
            newDescription = body.description().trim();
            if (newDescription.length() > DESCRIPTION_MAX_LENGTH) {
                errors.add(Map.of("field", "description", "message",
                        "description must be at most " + DESCRIPTION_MAX_LENGTH + " characters"));
            }
        }
        String newExpression = null;
        if (body.expression() != null) {
            newExpression = body.expression().trim();
            if (newExpression.isEmpty()) {
                errors.add(Map.of("field", "expression", "message",
                        "expression must not be blank"));
            }
        }
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", errors));
        }

        // Resolve the rule and confirm scope. findById is unscoped so
        // we re-check studyId here — a rule from a different study
        // surfaces as a 404, not a 403, to avoid leaking existence.
        RuleBean rule = ruleDao.findById(ruleId);
        if (rule == null
                || rule.getStudyId() == null
                || rule.getStudyId() != study.getId()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No rule with id " + ruleId + " in study '" + study.getOid() + "'"));
        }

        // Expression-body validation against the active study scope.
        // Only fires when the operator actually submitted a new
        // expression — leaves untouched persisted expressions alone
        // (which might already be considered "stale" if the CRF
        // versioning churn deprecated their referenced OIDs; that's
        // the "rule health" follow-up the plan defers).
        if (newExpression != null) {
            ExpressionBean exprBeanProbe = new ExpressionBean(Context.OC_RULES_V1, newExpression);
            ExpressionObjectWrapper eow = new ExpressionObjectWrapper(
                    dataSource, study, exprBeanProbe, ExpressionObjectWrapper.CONTEXT_EXPRESSION);
            ResourceBundleProvider.updateLocale(Locale.ENGLISH);
            ExpressionService perRequestExprSvc = new ExpressionService(eow);
            try {
                if (!perRequestExprSvc.ruleExpressionChecker(newExpression)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "message", "Validation failed",
                            "errors", List.of(Map.of("field", "expression",
                                    "message", "Expression failed validation"))));
                }
            } catch (OpenClinicaSystemException ose) {
                String code = ose.getErrorCode() == null ? "OCRERR_unknown" : ose.getErrorCode();
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "expression",
                                "message", code + " : "
                                        + (ose.getMessage() == null ? "expression invalid" : ose.getMessage())))));
            } catch (RuntimeException rte) {
                LOG.debug("updateRule: expression checker threw {}", rte.toString());
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "expression",
                                "message", rte.getMessage() == null ? "Expression invalid" : rte.getMessage()))));
            }
        }

        // Per-field diff. Each branch captures old → new for the
        // audit pass below before mutating the bean.
        String oldName = rule.getName() == null ? "" : rule.getName();
        String oldDescription = rule.getDescription() == null ? "" : rule.getDescription();
        ExpressionBean exprBean = rule.getExpression();
        String oldExpression = exprBean == null || exprBean.getValue() == null
                ? "" : exprBean.getValue();

        boolean nameChanged = newName != null && !oldName.equals(newName);
        boolean descriptionChanged = newDescription != null && !oldDescription.equals(newDescription);
        boolean expressionChanged = newExpression != null && !oldExpression.equals(newExpression);

        if (!nameChanged && !descriptionChanged && !expressionChanged) {
            // SPA submitted current state (or nothing to change) —
            // return the refreshed shape so the caller's view stays
            // consistent without a second GET.
            return ResponseEntity.ok(toCreateRuleResponse(rule));
        }

        if (nameChanged) {
            rule.setName(newName);
        }
        if (descriptionChanged) {
            rule.setDescription(newDescription);
        }
        if (expressionChanged) {
            // The expression body is a cascaded @OneToOne; mutate the
            // existing bean's value rather than swapping the reference
            // so Hibernate's dirty-check picks it up. A swap would
            // orphan the old rule_expression row and create a new one
            // — the existing rule_expression row carries the foreign
            // key from rule_set_rule binders, so reusing it is safer.
            if (exprBean == null) {
                exprBean = new ExpressionBean(Context.OC_RULES_V1, newExpression);
                rule.setExpression(exprBean);
            } else {
                exprBean.setValue(newExpression);
            }
        }
        rule.setUpdater(me);
        rule.setUpdatedDate(new java.util.Date());

        RuleBean persisted = ruleDao.saveOrUpdate(rule);
        RuleBean fresh = persisted == null ? rule : persisted;

        // Per-field audit pass. Each row is independent so a partial
        // failure (e.g. row insert throws) doesn't roll back the
        // others — matches the RX.7 try/catch-per-row pattern.
        if (nameChanged) {
            writeRuleFieldAudit(me, study, fresh, "name", oldName, newName);
        }
        if (descriptionChanged) {
            writeRuleFieldAudit(me, study, fresh, "description", oldDescription, newDescription);
        }
        if (expressionChanged) {
            writeRuleFieldAudit(me, study, fresh, "expression", oldExpression, newExpression);
        }

        LOG.info("Update rule: id={} oid='{}' study={} name={} description={} expression={} by={}",
                fresh.getId(), fresh.getOid(), study.getOid(),
                nameChanged, descriptionChanged, expressionChanged, me.getName());

        return ResponseEntity.ok(toCreateRuleResponse(fresh));
    }

    private static CreateRuleResponse toCreateRuleResponse(RuleBean rule) {
        return new CreateRuleResponse(
                rule.getId() == null ? 0 : rule.getId(),
                rule.getOid() == null ? "" : rule.getOid(),
                rule.getName() == null ? "" : rule.getName(),
                rule.getDescription() == null ? "" : rule.getDescription(),
                rule.getExpression() == null || rule.getExpression().getValue() == null
                        ? "" : rule.getExpression().getValue());
    }

    /**
     * Phase E RX.6 — one audit row per changed scalar field on a
     * rule. Mirrors the RX.7 rule_set field-audit helper shape so
     * downstream audit-trail rendering treats both tables uniformly.
     */
    private void writeRuleFieldAudit(UserAccountBean me,
                                     StudyBean study,
                                     RuleBean rule,
                                     String columnName,
                                     String oldValue,
                                     String newValue) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule");
            ae.setEntityId(rule.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            ae.setActionMessage("rule_update: id=" + rule.getId()
                    + "." + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "' by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule field {} id={} (continuing): {}",
                    columnName, rule.getId(), e.getMessage());
        }
    }

    /* ----------------------------------------------------------------- */
    /* Audit helper                                                      */
    /* ----------------------------------------------------------------- */

    private void writeRuleCreateAudit(UserAccountBean me, StudyBean study, RuleBean rule) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule");
            ae.setEntityId(rule.getId());
            ae.setColumnName("create");
            ae.setOldValue("");
            ae.setNewValue(rule.getOid() == null ? "" : rule.getOid());
            ae.setActionMessage("rule_create: oid=" + rule.getOid()
                    + " name='" + (rule.getName() == null ? "" : rule.getName())
                    + "' by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule_create id={} (continuing): {}",
                    rule.getId(), e.getMessage());
        }
    }
}
