/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.rule.XmlSchemaValidationHelper;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.AuditableBeanWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RulesPostImportContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RulesPostImportContainerService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import javax.sql.DataSource;

/**
 * Phase E RX.2 — XML rules import adapter (template + preview + commit).
 *
 * <p>Wraps the legacy {@code ImportRuleServlet} pipeline as a JSON
 * two-step. The legacy servlet parks the validated container in
 * {@code session["importedData"]} and forwards to
 * {@code VerifyImportedRuleServlet}, which lets the operator review +
 * commit. This controller mirrors that exactly — only the transport
 * changes (multipart upload returning a {@link RulesImportPreviewDto}
 * with a server-issued token, then a follow-up commit POST keyed by
 * the token).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/rules/template} — serves
 *       {@code rules_template_with_notes.xml}. Auth: any authenticated
 *       user (read-only download of static content).</li>
 *   <li>{@code POST /api/v1/rules/import} (multipart) — XML upload +
 *       XSD validation + {@link RulesPostImportContainerService}
 *       {@code validateRuleDefs} / {@code validateRuleSetDefs}.
 *       Returns a preview DTO. Does <b>not</b> persist.</li>
 *   <li>{@code POST /api/v1/rules/import/commit} — body
 *       {@code {previewToken, ignoreDuplicates}}. Resolves the parked
 *       container from the user's HTTP session and dispatches to
 *       {@link RuleSetServiceInterface#saveImport}.</li>
 * </ul>
 *
 * <p>Authorization:
 * <ul>
 *   <li>{@code GET /template} — any authenticated user with an active
 *       study (template is publicly downloadable in the legacy UI;
 *       the auth gate matches every other Phase E adapter).</li>
 *   <li>{@code POST /import} and {@code POST /import/commit} —
 *       sysadmin OR study director/coordinator bound to the active
 *       study, mirroring legacy {@code ImportRuleServlet.mayProceed}
 *       semantics and reusing
 *       {@link StudyAdminAuthorization#roleMayEditStudy}.</li>
 * </ul>
 *
 * <h2>Preview token strategy</h2>
 *
 * <p>The validated container is parked in the user's HTTP session
 * under attribute keys derived from a server-issued UUID:
 *
 * <ul>
 *   <li>{@code "rxImportContainer_<uuid>"} — the
 *       {@link RulesPostImportContainer} itself</li>
 *   <li>{@code "rxImportContainerExpiry_<uuid>"} — an {@link Instant}
 *       set to {@code now + 15 min}; commit checks this and returns
 *       410 Gone past expiry</li>
 * </ul>
 *
 * <p>This is session-scoped (not cross-request shared) which matches
 * how every other multipart preview in this codebase works — the
 * legacy servlet uses {@code session["importedData"]} for the same
 * purpose. Operators who switch browsers between preview and commit
 * lose the parked container; that's fine — they re-upload. The
 * narrower scope also avoids cross-user leakage that a shared
 * server-side cache would risk.
 *
 * <h2>File archival</h2>
 *
 * <p>Uploaded files are saved to
 * {@code <filePath>/rules/original/<timestamp>_<safeName>} mirroring
 * the legacy {@code ImportRuleServlet}'s {@code FileUploadHelper}
 * destination. The disk archive is for forensics — when a validator
 * rejects an import, the operator can grab the file from disk and
 * compare against the JSP-path error output. {@code filePath} comes
 * from {@code datainfo.properties} via
 * {@link CoreResources#getField(String)}; a missing key falls back to
 * {@code java.io.tmpdir}.
 *
 * <h2>Why a separate controller</h2>
 *
 * <p>The task allowed either extending {@link RuleExpressionApiController}
 * (RX.3) or creating a new controller. With XSD validation +
 * multipart + session token cache + audit + commit semantics, the
 * import surface is large enough to warrant its own file —
 * {@code RuleExpressionApiController}'s "constant-expression test"
 * surface (~150 LOC) and the import flow (~400 LOC) have no shared
 * collaborators (the expression test owns the parser; the import
 * owns the JAXB context + container service + RuleSetService).
 * Co-locating them would mean a 600+ LOC controller carrying four
 * unrelated autowires.
 */
@RestController
@RequestMapping("/api/v1/rules")
@Tag(name = "Rules — import",
     description = "Upload XML rules, preview the validator outcome, then commit. Two-step transaction to match the legacy operator workflow.")
public class RulesImportApiController {

    private static final Logger LOG = LoggerFactory.getLogger(RulesImportApiController.class);

    /**
     * Preview tokens expire 15 minutes after issue. Long enough for
     * an operator to review counts + issues, short enough to bound
     * how much session memory a stale upload can hold.
     */
    static final long PREVIEW_TTL_SECONDS = 900L;

    /** Session-attribute key prefix for the parked container. */
    static final String SESSION_CONTAINER_PREFIX = "rxImportContainer_";

    /** Session-attribute key prefix for the expiry {@link Instant}. */
    static final String SESSION_EXPIRY_PREFIX = "rxImportContainerExpiry_";

    private final DataSource dataSource;
    private final RulesPostImportContainerService rulesPostImportContainerService;
    private final RuleSetServiceInterface ruleSetService;
    private final OdmJaxbContext odmJaxbContext;

    @Autowired
    public RulesImportApiController(@Qualifier("dataSource") DataSource dataSource,
                                    @Qualifier("rulesPostImportContainerService")
                                            RulesPostImportContainerService rulesPostImportContainerService,
                                    @Qualifier("ruleSetService") RuleSetServiceInterface ruleSetService,
                                    @Qualifier("odmJaxbContext") OdmJaxbContext odmJaxbContext) {
        this.dataSource = dataSource;
        this.rulesPostImportContainerService = rulesPostImportContainerService;
        this.ruleSetService = ruleSetService;
        this.odmJaxbContext = odmJaxbContext;
    }

    /**
     * Test-only no-arg constructor: lets {@code MockMvc standaloneSetup}
     * instantiate the controller for the session-guard contract tests
     * without wiring the four collaborators. The 401 / 415 / 400 paths
     * short-circuit before any collaborator is touched; the
     * persistence + JAXB paths need Testcontainers + a full Spring
     * context and are deferred to the MockMvc IT cohort (per the
     * Phase E.4 plan).
     */
    RulesImportApiController() {
        this.dataSource = null;
        this.rulesPostImportContainerService = null;
        this.ruleSetService = null;
        this.odmJaxbContext = null;
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/rules/template                                          */
    /* ----------------------------------------------------------------- */

    /**
     * Serve the annotated rules-XML template
     * ({@code rules_template_with_notes.xml}) as
     * {@code application/xml}.
     *
     * <p>The legacy {@code ImportRuleServlet?action=downloadtemplateWithNotes}
     * does the same; we honour the same content-type and pass the
     * template's bytes through verbatim so XML editors that key off
     * the {@code <!-- ... -->} comments can read them.
     *
     * <p>Auth: any authenticated user with an active study. The
     * template is static content the legacy form makes available
     * without a role check, so widening the auth gate beyond
     * "must be logged in" buys nothing.
     */
    @GetMapping("/template")
    public ResponseEntity<?> downloadTemplate(HttpSession session) {
        ResponseEntity<?> guard = preflightAnyAuthenticated(session);
        if (guard != null) return guard;

        try {
            byte[] payload = loadClasspathResource("properties/rules_template_with_notes.xml");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .header("Content-Disposition",
                            "attachment; filename=\"rules_template_with_notes.xml\"")
                    .body(payload);
        } catch (IOException ioe) {
            LOG.warn("Failed to load rules_template_with_notes.xml from classpath", ioe);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load rules template"));
        }
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rules/import (multipart)                              */
    /* ----------------------------------------------------------------- */

    /**
     * Multipart upload of an XML rules file. Validates against
     * {@code rules.xsd}, runs the post-import container validator,
     * then parks the container in the user's HTTP session and
     * returns a {@link RulesImportPreviewDto} with a server-issued
     * preview token.
     *
     * <p>Does <b>not</b> persist anything. The follow-up
     * {@link #commitImport} call is required.
     *
     * <p>File-type guard: accepts only {@code .xml} (case-insensitive).
     * Refuses 415 on other extensions. The XSD validator catches
     * malformed payloads even when the extension is correct.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RulesImportPreviewDto.class)))
    public ResponseEntity<?> uploadImport(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "file part is required"));
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) originalFilename = "upload.xml";
        String lower = originalFilename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xml")) {
            return ResponseEntity.status(415).body(Map.of("message",
                    "Only .xml rule definition files are accepted"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        Locale locale = resolveLocale(acceptLanguage);
        // Bind the thread-local so the validator's OCRERR_* message
        // resolution works without traversing the legacy locale
        // filter chain.
        ResourceBundleProvider.updateLocale(locale);
        ResourceBundle respage;
        try {
            respage = ResourceBundleProvider.getPageMessagesBundle(locale);
        } catch (RuntimeException rbe) {
            LOG.warn("page_messages bundle unavailable for locale {} — error codes will surface raw", locale, rbe);
            respage = null;
        }

        // Step 1: persist to <filePath>/rules/original/ for forensics.
        // Mirrors ImportRuleServlet.getDirToSaveUploadedFileIn + the
        // CrfsApiController.persistUploadedFile pattern from A8.3.
        Path storedPath;
        try {
            storedPath = persistUploadedFile(file, originalFilename);
        } catch (IOException ioe) {
            LOG.warn("Failed to persist uploaded rules XML (name={})", originalFilename, ioe);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to store uploaded file"));
        }

        // Step 2: XSD validation against rules.xsd. The legacy
        // ImportRuleServlet uses CoreResources.getInputStream — we
        // do the same so the same classpath resource resolves.
        try {
            XmlSchemaValidationHelper schemaValidator = new XmlSchemaValidationHelper();
            try (InputStream xsd = openClasspathResource("properties/rules.xsd")) {
                schemaValidator.validateAgainstSchema(storedPath.toFile(), xsd);
            }
        } catch (OpenClinicaSystemException xse) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "XSD validation failed",
                    "errors", List.of(Map.of(
                            "field", "file",
                            "message", xse.getMessage() == null
                                    ? "rules.xsd validation rejected the upload"
                                    : xse.getMessage()))));
        } catch (IOException ioe) {
            LOG.warn("Failed to read rules.xsd from classpath", ioe);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load rules.xsd"));
        }

        // Step 3: JAXB unmarshal + validator pipeline. Both
        // validateRuleDefs and validateRuleSetDefs touch the DB to
        // resolve OID references; they need a current-study scope +
        // a user account for "modified by" stamping.
        RulesPostImportContainer importedRules;
        try (InputStream in = Files.newInputStream(storedPath)) {
            importedRules = odmJaxbContext.unmarshalRulesImport(in);
            importedRules.initializeRuleDef();
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Failed to unmarshal uploaded rules XML (name={})", originalFilename, ex);
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "JAXB unmarshal failed",
                    "errors", List.of(Map.of(
                            "field", "file",
                            "message", ex.getMessage() == null
                                    ? "Could not parse the XML as a Rules import payload"
                                    : ex.getMessage()))));
        }

        try {
            // The container service is a singleton with three
            // request-scoped setters (currentStudy / userAccount /
            // respage). Synchronize so two concurrent uploads don't
            // step on each other's state. The legacy servlet has the
            // same call sequence; the singleton is documented as
            // "set then use immediately" — not safe across
            // concurrent calls without external coordination.
            // Throughput is a non-issue (XML import is operator-
            // triggered, low volume).
            synchronized (rulesPostImportContainerService) {
                rulesPostImportContainerService.setCurrentStudy(currentStudy);
                rulesPostImportContainerService.setUserAccount(me);
                if (respage != null) rulesPostImportContainerService.setRespage(respage);
                importedRules = rulesPostImportContainerService.validateRuleDefs(importedRules);
                importedRules = rulesPostImportContainerService.validateRuleSetDefs(importedRules);
            }
        } catch (OpenClinicaSystemException ose) {
            LOG.warn("Rules validator rejected upload (name={}, code={})",
                    originalFilename, ose.getErrorCode(), ose);
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Rules validator failed",
                    "errors", List.of(Map.of(
                            "field", "file",
                            "message", ose.getMessage() == null
                                    ? "Validator rejected the upload"
                                    : ose.getMessage()))));
        }

        // Step 4: park the container + assign a preview token.
        String previewToken = UUID.randomUUID().toString();
        session.setAttribute(SESSION_CONTAINER_PREFIX + previewToken, importedRules);
        session.setAttribute(SESSION_EXPIRY_PREFIX + previewToken,
                Instant.now().plusSeconds(PREVIEW_TTL_SECONDS));

        LOG.info("Rules import preview: token={} study={} user={} file={} valid={}/{} dup={}/{} invalid={}/{}",
                previewToken, currentStudy.getOid(), me.getName(), storedPath.getFileName(),
                importedRules.getValidRuleDefs().size(), importedRules.getValidRuleSetDefs().size(),
                importedRules.getDuplicateRuleDefs().size(), importedRules.getDuplicateRuleSetDefs().size(),
                importedRules.getInValidRuleDefs().size(), importedRules.getInValidRuleSetDefs().size());

        return ResponseEntity.ok(buildPreviewDto(previewToken, importedRules));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rules/import/commit                                    */
    /* ----------------------------------------------------------------- */

    /**
     * Commit a previously parked container. The {@code previewToken}
     * was issued by {@link #uploadImport} and refers to a container
     * stored in this HTTP session under the
     * {@link #SESSION_CONTAINER_PREFIX} keys.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>{@code 400} when {@code previewToken} is missing or
     *       blank.</li>
     *   <li>{@code 410 Gone} when the token is unknown or expired —
     *       the operator must re-upload.</li>
     *   <li>{@code 200} with {@link RulesImportCommitResult} on
     *       success. The container is removed from the session
     *       afterwards regardless of outcome (committed-once
     *       semantics).</li>
     * </ul>
     *
     * <p>{@code ignoreDuplicates}: when true, duplicate rule defs +
     * rule sets are dropped from the container before
     * {@code saveImport} runs. Mirrors the legacy verify-page's
     * "ignore duplicates" checkbox.
     */
    @PostMapping(value = "/import/commit",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RulesImportCommitResult.class)))
    public ResponseEntity<?> commitImport(@RequestBody(required = false) CommitRequest body,
                                          HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (body == null || body.previewToken() == null || body.previewToken().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "previewToken",
                            "message", "previewToken is required"))));
        }
        String token = body.previewToken();
        boolean ignoreDuplicates = body.ignoreDuplicates() != null && body.ignoreDuplicates();

        Object containerAttr = session.getAttribute(SESSION_CONTAINER_PREFIX + token);
        Object expiryAttr = session.getAttribute(SESSION_EXPIRY_PREFIX + token);
        if (!(containerAttr instanceof RulesPostImportContainer container)) {
            return ResponseEntity.status(410).body(Map.of("message",
                    "Preview token unknown or already consumed — re-upload the XML."));
        }
        if (expiryAttr instanceof Instant expiry && Instant.now().isAfter(expiry)) {
            // Expired — drop session attrs so the operator's retry
            // doesn't trip on stale state.
            session.removeAttribute(SESSION_CONTAINER_PREFIX + token);
            session.removeAttribute(SESSION_EXPIRY_PREFIX + token);
            return ResponseEntity.status(410).body(Map.of("message",
                    "Preview token expired — re-upload the XML."));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");

        // Apply ignoreDuplicates BEFORE counting so the response
        // reflects what actually got persisted.
        int dupRulesBeforeIgnore = container.getDuplicateRuleDefs() == null
                ? 0 : container.getDuplicateRuleDefs().size();
        int dupRuleSetsBeforeIgnore = container.getDuplicateRuleSetDefs() == null
                ? 0 : container.getDuplicateRuleSetDefs().size();
        if (ignoreDuplicates) {
            if (container.getDuplicateRuleDefs() != null) container.getDuplicateRuleDefs().clear();
            if (container.getDuplicateRuleSetDefs() != null) container.getDuplicateRuleSetDefs().clear();
        }

        // Capture counts BEFORE save (the save mutates ids in place
        // but doesn't change the bucket sizes, so before/after is the
        // same — counting before keeps the "what we're about to do"
        // story crisp).
        int rulesCreated = container.getValidRuleDefs() == null
                ? 0 : container.getValidRuleDefs().size();
        int rulesReplaced = ignoreDuplicates ? 0 : dupRulesBeforeIgnore;
        int ruleSetsCreated = container.getValidRuleSetDefs() == null
                ? 0 : container.getValidRuleSetDefs().size();
        int ruleSetsReplaced = ignoreDuplicates ? 0 : dupRuleSetsBeforeIgnore;
        int actionsCreated = countActions(container);

        try {
            ruleSetService.saveImport(container);
        } catch (RuntimeException re) {
            LOG.error("Rules import commit failed (token={} study={} user={})",
                    token, currentStudy.getOid(), me.getName(), re);
            // Don't drop the session attrs on a transient error —
            // the operator might want to retry without re-uploading.
            return ResponseEntity.status(500).body(Map.of("message",
                    "Save failed — the import was rolled back. See server logs for details."));
        }

        // Session attrs are single-use; remove on success so a
        // double-click doesn't double-save.
        session.removeAttribute(SESSION_CONTAINER_PREFIX + token);
        session.removeAttribute(SESSION_EXPIRY_PREFIX + token);

        String committedAt = Instant.now().toString();
        writeAudit(me, currentStudy, token, rulesCreated, rulesReplaced,
                ruleSetsCreated, ruleSetsReplaced, ignoreDuplicates);

        LOG.info("Rules import commit: token={} study={} user={} rulesCreated={} rulesReplaced={} setsCreated={} setsReplaced={} actions={} ignoreDup={}",
                token, currentStudy.getOid(), me.getName(),
                rulesCreated, rulesReplaced, ruleSetsCreated, ruleSetsReplaced,
                actionsCreated, ignoreDuplicates);

        return ResponseEntity.ok(new RulesImportCommitResult(
                rulesCreated, rulesReplaced,
                ruleSetsCreated, ruleSetsReplaced,
                actionsCreated, committedAt));
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                            */
    /* ----------------------------------------------------------------- */

    /**
     * Builds the preview DTO with counts + per-entry issues list.
     * Issues come from {@link AuditableBeanWrapper#getImportErrors}
     * on every invalid + duplicate wrapper.
     */
    private static RulesImportPreviewDto buildPreviewDto(String token, RulesPostImportContainer c) {
        int validRules = c.getValidRuleDefs() == null ? 0 : c.getValidRuleDefs().size();
        int dupRules = c.getDuplicateRuleDefs() == null ? 0 : c.getDuplicateRuleDefs().size();
        int invalidRules = c.getInValidRuleDefs() == null ? 0 : c.getInValidRuleDefs().size();
        int validSets = c.getValidRuleSetDefs() == null ? 0 : c.getValidRuleSetDefs().size();
        int dupSets = c.getDuplicateRuleSetDefs() == null ? 0 : c.getDuplicateRuleSetDefs().size();
        int invalidSets = c.getInValidRuleSetDefs() == null ? 0 : c.getInValidRuleSetDefs().size();

        List<RulesImportPreviewDto.ImportIssue> issues = new ArrayList<>();
        if (c.getInValidRuleDefs() != null) {
            for (AuditableBeanWrapper<RuleBean> w : c.getInValidRuleDefs()) {
                String id = w.getAuditableBean() == null ? "" : w.getAuditableBean().getOid();
                for (String msg : safeMessages(w)) {
                    issues.add(new RulesImportPreviewDto.ImportIssue("rule", id, "ERROR", msg));
                }
            }
        }
        if (c.getDuplicateRuleDefs() != null) {
            for (AuditableBeanWrapper<RuleBean> w : c.getDuplicateRuleDefs()) {
                String id = w.getAuditableBean() == null ? "" : w.getAuditableBean().getOid();
                List<String> msgs = safeMessages(w);
                if (msgs.isEmpty()) {
                    issues.add(new RulesImportPreviewDto.ImportIssue("rule", id, "WARNING",
                            "Duplicate — commit will overwrite the existing definition unless ignoreDuplicates is set."));
                } else {
                    for (String msg : msgs) {
                        issues.add(new RulesImportPreviewDto.ImportIssue("rule", id, "WARNING", msg));
                    }
                }
            }
        }
        if (c.getInValidRuleSetDefs() != null) {
            for (AuditableBeanWrapper<RuleSetBean> w : c.getInValidRuleSetDefs()) {
                String id = ruleSetIdentifier(w);
                for (String msg : safeMessages(w)) {
                    issues.add(new RulesImportPreviewDto.ImportIssue("ruleSet", id, "ERROR", msg));
                }
            }
        }
        if (c.getDuplicateRuleSetDefs() != null) {
            for (AuditableBeanWrapper<RuleSetBean> w : c.getDuplicateRuleSetDefs()) {
                String id = ruleSetIdentifier(w);
                List<String> msgs = safeMessages(w);
                if (msgs.isEmpty()) {
                    issues.add(new RulesImportPreviewDto.ImportIssue("ruleSet", id, "WARNING",
                            "Duplicate — commit will overwrite the existing assignment unless ignoreDuplicates is set."));
                } else {
                    for (String msg : msgs) {
                        issues.add(new RulesImportPreviewDto.ImportIssue("ruleSet", id, "WARNING", msg));
                    }
                }
            }
        }

        return new RulesImportPreviewDto(token,
                validRules, dupRules, invalidRules,
                validSets, dupSets, invalidSets, issues);
    }

    /** Null-safe {@code getImportErrors} accessor. */
    private static List<String> safeMessages(AuditableBeanWrapper<?> w) {
        if (w == null || w.getImportErrors() == null) return List.of();
        return w.getImportErrors();
    }

    /** Identify a rule_set entry by its target expression for the issue list. */
    private static String ruleSetIdentifier(AuditableBeanWrapper<RuleSetBean> w) {
        if (w == null || w.getAuditableBean() == null) return "";
        RuleSetBean rs = w.getAuditableBean();
        if (rs.getTarget() != null && rs.getTarget().getValue() != null) {
            return rs.getTarget().getValue();
        }
        return rs.getId() == null ? "" : ("#" + rs.getId());
    }

    /**
     * Total {@code rule_action} count across all rule_sets the
     * commit will persist (valid + duplicate buckets, since both
     * end up calling {@code saveImport}).
     */
    private static int countActions(RulesPostImportContainer c) {
        int total = 0;
        if (c.getValidRuleSetDefs() != null) {
            for (AuditableBeanWrapper<RuleSetBean> w : c.getValidRuleSetDefs()) {
                total += countActionsInSet(w);
            }
        }
        if (c.getDuplicateRuleSetDefs() != null) {
            for (AuditableBeanWrapper<RuleSetBean> w : c.getDuplicateRuleSetDefs()) {
                total += countActionsInSet(w);
            }
        }
        return total;
    }

    private static int countActionsInSet(AuditableBeanWrapper<RuleSetBean> w) {
        if (w == null || w.getAuditableBean() == null) return 0;
        RuleSetBean rs = w.getAuditableBean();
        int n = 0;
        if (rs.getRuleSetRules() != null) {
            for (RuleSetRuleBean rsr : rs.getRuleSetRules()) {
                if (rsr != null && rsr.getActions() != null) n += rsr.getActions().size();
            }
        }
        return n;
    }

    /**
     * 401 if no userBean, 400 if no active study. Used by GET /template
     * — the template is static-content download accessible to any
     * authenticated user with an active study.
     */
    private static ResponseEntity<?> preflightAnyAuthenticated(HttpSession session) {
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
     * 401 / 400 / 403 preflight for the write endpoints (POST /import,
     * POST /import/commit). Mirrors
     * {@link StudyAdminAuthorization#roleMayEditStudy} — sysadmin OR
     * Director / Coordinator bound to the active study.
     */
    private static ResponseEntity<?> preflightWrite(HttpSession session) {
        ResponseEntity<?> base = preflightAnyAuthenticated(session);
        if (base != null) return base;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit importing rules — sysadmin or Director/Coordinator on the active study only"));
        }
        return null;
    }

    /**
     * Resolve {@code Accept-Language} with English fallback. Same
     * pattern as the A8.3 CRF parser adapter + the RX.3 expression
     * controller.
     */
    private static Locale resolveLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return Locale.ENGLISH;
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            if (ranges.isEmpty()) return Locale.ENGLISH;
            return Locale.forLanguageTag(ranges.get(0).getRange().replace('_', '-'));
        } catch (IllegalArgumentException iae) {
            return Locale.ENGLISH;
        }
    }

    /**
     * Persist the upload to {@code <filePath>/rules/original/<ts>_<safe>}.
     * Mirrors {@link CrfsApiController}'s archival path, with the
     * subdirectory matching legacy {@code ImportRuleServlet}'s
     * {@code rules/original/} convention.
     */
    private static Path persistUploadedFile(MultipartFile file, String originalFilename) throws IOException {
        String filePath = CoreResources.getField("filePath");
        if (filePath == null || filePath.isBlank()) {
            filePath = System.getProperty("java.io.tmpdir");
        }
        Path baseDir = Paths.get(filePath, "rules", "original");
        Files.createDirectories(baseDir);
        String safeName = originalFilename.replaceAll("[^A-Za-z0-9._-]", "_");
        String stamped = System.currentTimeMillis() + "_" + safeName;
        Path target = baseDir.resolve(stamped);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Read a classpath resource into a byte array.
     *
     * <p>Uses a {@link PathMatchingResourcePatternResolver} so the
     * resource resolution works when {@code core/} ships its
     * {@code properties/} dir on the classpath (both from the JAR and
     * from {@code core/target/classes} during development).
     */
    private static byte[] loadClasspathResource(String relPath) throws IOException {
        try (InputStream in = openClasspathResource(relPath)) {
            return in.readAllBytes();
        }
    }

    private static InputStream openClasspathResource(String relPath) throws IOException {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + relPath);
        if (!resource.exists()) {
            throw new IOException("Classpath resource not found: " + relPath);
        }
        return resource.getInputStream();
    }

    private void writeAudit(UserAccountBean me, StudyBean study, String token,
                            int rulesCreated, int rulesReplaced,
                            int ruleSetsCreated, int ruleSetsReplaced,
                            boolean ignoreDuplicates) {
        if (dataSource == null) return; // test-only path
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule_set");
            ae.setEntityId(0);
            ae.setColumnName("import");
            ae.setOldValue("");
            ae.setNewValue("rulesCreated=" + rulesCreated
                    + " rulesReplaced=" + rulesReplaced
                    + " ruleSetsCreated=" + ruleSetsCreated
                    + " ruleSetsReplaced=" + ruleSetsReplaced
                    + " ignoreDuplicates=" + ignoreDuplicates);
            ae.setActionMessage("rules_import_commit: token=" + token + " by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rules_import_commit (token={}, continuing): {}",
                    token, e.getMessage());
        }
    }

    /* ----------------------------------------------------------------- */
    /* Request body shape                                                 */
    /* ----------------------------------------------------------------- */

    /**
     * Commit request body. Both fields are nullable on the wire — the
     * controller normalises null {@code ignoreDuplicates} to
     * {@code false}, and rejects null/blank {@code previewToken} with
     * a 400.
     */
    public record CommitRequest(
            @Schema(description = "Preview token returned by /import.") String previewToken,
            @Schema(description = "When true, duplicate rules + rule sets are skipped; otherwise they overwrite existing definitions.")
            Boolean ignoreDuplicates) {}
}
