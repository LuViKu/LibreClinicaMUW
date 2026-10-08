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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DataEntryStage;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.DisplayItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.DisplayItemBeanWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.FormDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.StudyEventDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.SubjectDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.internal.ImportPreviewSession;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaException;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ImportDataRuleRunnerContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;
import at.ac.meduniwien.ophthalmology.libreclinica.web.crfdata.ImportCRFDataPersistenceService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.crfdata.ImportCRFDataService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E.6 {@code bulk-import} — ODM CRF-data bulk-import adapter.
 *
 * <p>Wraps the legacy {@code ImportCRFDataServlet} four-step wizard as
 * a JSON pipeline. Mirrors the RX.2 {@code RulesImportApiController}
 * shape because the operator UX is identical (upload → preview →
 * commit) — only the underlying domain (CRF data, not rules) changes.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code POST /api/v1/import} (multipart, {@code file} part) —
 *       XSD validation + ODM unmarshal + metadata-validation pass.
 *       Returns {@link ImportCrfPreviewDto} with summary counts +
 *       first {@value ImportCrfPreviewDto#INLINE_ROW_CAP} rows.</li>
 *   <li>{@code GET /api/v1/import/{token}/rows?offset=&limit=} —
 *       windowed access to the full row list when the import exceeds
 *       the inline cap. Returns
 *       {@link PreviewRowsPageDto}.</li>
 *   <li>{@code POST /api/v1/import/commit} — body
 *       {@code {previewToken, reasonForChange?, overwriteMode?}}.
 *       Commits the parked ODM through the legacy import pipeline
 *       ({@link ImportCRFDataService}, then the save step in
 *       {@link ImportCRFDataPersistenceService}). Returns
 *       {@link ImportCrfCommitResult}.</li>
 * </ul>
 *
 * <h2>Auth</h2>
 *
 * Sysadmin OR Director / Coordinator / Investigator / RA on the
 * active study — matches the legacy {@code ImportCRFDataServlet
 * .mayProceed}. The role gate lives in
 * {@link BulkImportAuthorization}.
 *
 * <h2>Preview token strategy</h2>
 *
 * Same pattern as {@link RulesImportApiController}: parse + validate
 * the ODM payload synchronously, then park an
 * {@link ImportPreviewSession} in the operator's HTTP session keyed
 * by a server-issued UUID. The token expires 15 minutes after issue.
 * Single-use semantics — commit removes the session attributes
 * regardless of outcome to prevent double-saves on a refresh.
 *
 * <h2>Preview and commit agree</h2>
 *
 * The preview states, per value, whether the commit inserts it,
 * overwrites a stored value, or skips it ({@link ImportRowProjection},
 * which reads the rules of the legacy pipeline: the file's
 * {@code UpsertOn}, the visit's status and the event CRF's stage). The
 * commit works that out again before it writes and answers
 * {@code 409} when the data changed since the preview, so what it
 * writes is what the operator saw and gave a reason for.
 *
 * <h2>RFC capture (21 CFR Part 11)</h2>
 *
 * The commit endpoint refuses ({@code 400}) when the preview reported
 * {@code overwriteCount > 0} but the body omits
 * {@code reasonForChange}, unless the operator explicitly passes
 * {@code overwriteMode = "skip"}, which leaves stored values as they
 * are. Every overwritten value gets an audit row of its own
 * ({@link AuditTypeIds#ITEM_DATA_REASON_FOR_CHANGE}) carrying the
 * reason, next to the value-change row the item_data trigger writes.
 *
 * <h2>What the commit writes</h2>
 *
 * Exactly what the legacy import writes: the pipeline is the legacy
 * one, and a value the item's definition rejects (the legacy "hard"
 * checks) refuses the file with nothing written, as the legacy
 * verification page does. Like the legacy save, the writes are not
 * one transaction: each goes through the legacy DAOs on its own
 * connection. Everything that can refuse the file is checked before
 * the first value is written, except that not-started event CRFs are
 * created (by {@code fetchEventCRFBeans}) before the value checks
 * run; the legacy import creates them at its preview.
 */
@RestController
@RequestMapping("/api/v1/import")
@Tag(name = "Bulk import — CRF data",
     description = "Upload an ODM 1.3 XML payload, preview the projected diff, then commit. Mirrors the legacy ImportCRFDataServlet wizard.")
public class ImportApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ImportApiController.class);

    /** Preview tokens expire 15 minutes after issue. */
    static final long PREVIEW_TTL_SECONDS = 900L;

    /** Session-attribute key prefix. */
    static final String SESSION_PREFIX = "bulkImportSession_";

    /** Cap on a single page of {@code /rows} (defensive guard). */
    static final int MAX_PAGE_LIMIT = 1000;

    private final DataSource dataSource;
    private final OdmJaxbContext odmJaxbContext;
    /** Runs the study's rules on imported data, as the legacy save does; null runs none. */
    private final RuleSetServiceInterface ruleSetService;

    @Autowired
    public ImportApiController(@Qualifier("dataSource") DataSource dataSource,
                               @Qualifier("odmJaxbContext") OdmJaxbContext odmJaxbContext,
                               @Qualifier("ruleSetService") RuleSetServiceInterface ruleSetService) {
        this.dataSource = dataSource;
        this.odmJaxbContext = odmJaxbContext;
        this.ruleSetService = ruleSetService;
    }

    /** Test seam: no rule service, so a commit runs no rules. */
    public ImportApiController(DataSource dataSource, OdmJaxbContext odmJaxbContext) {
        this(dataSource, odmJaxbContext, null);
    }

    /**
     * Test-only no-arg constructor. The session-guard + file-type +
     * empty-body + unknown-token paths short-circuit before any
     * collaborator is touched; the persistence path is deferred to
     * the MockMvc IT cohort.
     */
    ImportApiController() {
        this.dataSource = null;
        this.odmJaxbContext = null;
        this.ruleSetService = null;
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/import (multipart)                                    */
    /* ----------------------------------------------------------------- */

    /**
     * Multipart upload of an ODM 1.3 XML payload. Validates against
     * {@code ODM1-3.xsd}, unmarshals via {@link OdmJaxbContext},
     * projects an {@link ImportCrfPreviewDto}, and parks an
     * {@link ImportPreviewSession} in the HTTP session against a
     * server-issued token.
     *
     * <p>Does <b>not</b> persist anything. The follow-up
     * {@link #commitImport} call is required.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ImportCrfPreviewDto.class)))
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
                    "Only .xml CRF data files are accepted"));
        }
        ResponseEntity<?> closed = refuseIfStudyClosed(session);
        if (closed != null) return closed;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        Locale locale = resolveLocale(acceptLanguage);
        ResourceBundleProvider.updateLocale(locale);

        // Step 1: persist to <filePath>/crf/original/ for forensics —
        // mirrors legacy ImportCRFDataServlet.processRequest #confirm.
        Path storedPath;
        try {
            storedPath = persistUploadedFile(file, originalFilename);
        } catch (IOException ioe) {
            LOG.warn("Failed to persist uploaded ODM XML (name={})", originalFilename, ioe);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to store uploaded file"));
        }

        // Step 2: JAXB unmarshal. The legacy servlet uses
        // OdmJaxbContext.unmarshalClinicalData which already wraps
        // ODM 1.3 + 1.2.1 backwards compatibility.
        ODMContainer odmContainer;
        try (InputStream in = Files.newInputStream(storedPath)) {
            odmContainer = odmJaxbContext.unmarshalClinicalData(in);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Failed to unmarshal uploaded ODM XML (name={})", originalFilename, ex);
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "ODM unmarshal failed",
                    "errors", List.of(Map.of(
                            "field", "file",
                            "message", ex.getMessage() == null
                                    ? "Could not parse the XML as an ODM CRF data payload"
                                    : ex.getMessage()))));
        }

        // Step 3: metadata-validation pass — verifies the
        // study/event/CRF/item OIDs in the payload resolve to live
        // rows in the current study scope. Reuses the legacy
        // ImportCRFDataService.validateStudyMetadata; routed through
        // a single-flight guard because the service mutates a
        // ResourceBundle static (legacy globalish state).
        List<ImportCrfPreviewDto.ImportIssue> issues = new ArrayList<>();
        try {
            ImportCRFDataService dataService = new ImportCRFDataService(dataSource, locale);
            List<String> metaErrors = dataService.validateStudyMetadata(odmContainer, currentStudy.getId());
            if (metaErrors != null) {
                for (String msg : metaErrors) {
                    issues.add(new ImportCrfPreviewDto.ImportIssue(
                            "metadata", odmContainer.getCrfDataPostImportContainer().getStudyOID(),
                            "ERROR", msg));
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("Metadata validator threw on ODM upload (name={})", originalFilename, ex);
            issues.add(new ImportCrfPreviewDto.ImportIssue(
                    "metadata", "unknown", "ERROR",
                    ex.getMessage() == null
                            ? "Metadata validation failed"
                            : ex.getMessage()));
        }

        // Step 4: what the commit will do with each value — insert,
        // overwrite, or skip — worked out read-only from the same
        // rules the legacy pipeline applies (ImportRowProjection). A
        // file whose OIDs do not all resolve cannot be committed, so
        // its rows are shown refused. The per-value checks against
        // the item definitions (`lookupValidationErrors`) run at
        // commit, as before.
        List<ImportCrfPreviewDto.PreviewRowDto> allRows;
        if (issues.isEmpty()) {
            ImportRowProjection.Result projected = new ImportRowProjection(dataSource).project(odmContainer);
            allRows = projected.rows();
            issues.addAll(projected.issues());
        } else {
            allRows = ImportRowProjection.refused(odmContainer);
        }
        int subjectCount = countDistinctSubjects(odmContainer);
        int eventCount = countDistinctEvents(odmContainer);
        int crfCount = countDistinctCrfs(odmContainer);
        int rowCount = allRows.size();
        int insertCount = countAction(allRows, ImportRowProjection.ACTION_INSERT);
        int overwriteCount = countAction(allRows, ImportRowProjection.ACTION_OVERWRITE);
        int errorCount = (int) issues.stream()
                .filter(i -> "ERROR".equals(i.severity())).count();
        // Values the commit skips because their CRF is not open to this
        // import (UpsertOn, or the CRF's stage).
        int warningCount = (int) allRows.stream()
                .filter(r -> ImportRowProjection.WARNING.equals(r.status())).count();

        List<ImportCrfPreviewDto.PreviewRowDto> inlineRows = allRows.size() > ImportCrfPreviewDto.INLINE_ROW_CAP
                ? allRows.subList(0, ImportCrfPreviewDto.INLINE_ROW_CAP)
                : allRows;

        String previewToken = UUID.randomUUID().toString();
        String studyOid = odmContainer.getCrfDataPostImportContainer() == null
                ? ""
                : nullToBlank(odmContainer.getCrfDataPostImportContainer().getStudyOID());
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(PREVIEW_TTL_SECONDS);

        ImportCrfPreviewDto preview = new ImportCrfPreviewDto(
                previewToken, studyOid, originalFilename,
                subjectCount, eventCount, crfCount, rowCount,
                insertCount, overwriteCount, errorCount, warningCount,
                inlineRows, issues);

        ImportPreviewSession parked = new ImportPreviewSession(
                odmContainer, allRows, preview, originalFilename, now, expiresAt);
        session.setAttribute(SESSION_PREFIX + previewToken, parked);

        LOG.info("CRF import preview: token={} study={} user={} file={} subjects={} events={} crfs={} rows={} issues={}",
                previewToken, currentStudy.getOid(), me.getName(), storedPath.getFileName(),
                subjectCount, eventCount, crfCount, rowCount, issues.size());

        return ResponseEntity.ok(preview);
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/import/{token}/rows                                    */
    /* ----------------------------------------------------------------- */

    /**
     * Windowed access to the full row list parked at upload time.
     * Returns 410 when the token is unknown or expired so the operator
     * can re-upload. Auth is the same as upload + commit.
     */
    @GetMapping("/{token}/rows")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = PreviewRowsPageDto.class)))
    public ResponseEntity<?> listRows(@PathVariable("token") String token,
                                      @RequestParam(value = "offset", defaultValue = "0") int offset,
                                      @RequestParam(value = "limit",  defaultValue = "200") int limit,
                                      HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "token path variable is required"));
        }
        if (offset < 0) offset = 0;
        if (limit < 1) limit = 1;
        if (limit > MAX_PAGE_LIMIT) limit = MAX_PAGE_LIMIT;

        ImportPreviewSession parked = pullParked(session, token);
        if (parked == null) {
            return ResponseEntity.status(410).body(Map.of("message",
                    "Preview token unknown or already consumed — re-upload the XML."));
        }
        if (parked.isExpired(Instant.now())) {
            session.removeAttribute(SESSION_PREFIX + token);
            return ResponseEntity.status(410).body(Map.of("message",
                    "Preview token expired — re-upload the XML."));
        }

        List<ImportCrfPreviewDto.PreviewRowDto> all = parked.allRows();
        int total = all.size();
        if (offset >= total) {
            return ResponseEntity.ok(new PreviewRowsPageDto(total, offset, limit, List.of()));
        }
        int end = Math.min(offset + limit, total);
        return ResponseEntity.ok(new PreviewRowsPageDto(total, offset, limit, all.subList(offset, end)));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/import/commit                                         */
    /* ----------------------------------------------------------------- */

    /**
     * Commit a previously parked ODM payload through the legacy import
     * pipeline.
     *
     * <p>Every refusal comes before the first value is written:
     * <ol>
     *   <li>{@code 400} without a token, with an overwrite mode other than
     *       {@code replace} or {@code skip}, or without a reason when
     *       stored values will be overwritten. The token stays usable, so
     *       the operator can supply the reason.</li>
     *   <li>{@code 410} for an unknown, expired or used token.</li>
     *   <li>{@code 409} when the study is locked or frozen, as legacy
     *       refuses. The token stays usable. From here on the token is
     *       spent, whatever the outcome.</li>
     *   <li>{@code 422} when the preview reported errors, or the file's
     *       OIDs no longer resolve in the active study.</li>
     *   <li>{@code 409} when the data changed since the preview (a value
     *       shown as new is stored now, a CRF or a visit changed state):
     *       the operator must see and confirm what the import does now.</li>
     *   <li>{@code 422} when the legacy pipeline finds no CRF to import
     *       into, or a value the item's definition rejects.</li>
     * </ol>
     * Each attempt past the token check writes one
     * {@link AuditTypeIds#BULK_IMPORT_ATTEMPTED} row on the study with its
     * outcome.
     */
    @PostMapping(value = "/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ImportCrfCommitResult.class)))
    public ResponseEntity<?> commitImport(@RequestBody(required = false) CommitRequest body,
                                          @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
                                          HttpSession session,
                                          HttpServletRequest request) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (body == null || body.previewToken() == null || body.previewToken().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "previewToken",
                            "message", "previewToken is required"))));
        }
        String token = body.previewToken();
        String overwriteMode = body.overwriteMode() == null
                ? "replace" : body.overwriteMode().trim().toLowerCase(Locale.ROOT);
        if (!"replace".equals(overwriteMode) && !"skip".equals(overwriteMode)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "overwriteMode",
                            "message", "overwriteMode must be \"replace\" or \"skip\""))));
        }

        ImportPreviewSession parked = pullParked(session, token);
        if (parked == null) {
            return ResponseEntity.status(410).body(Map.of("message",
                    "Preview token unknown or already consumed — re-upload the XML."));
        }
        if (parked.isExpired(Instant.now())) {
            session.removeAttribute(SESSION_PREFIX + token);
            return ResponseEntity.status(410).body(Map.of("message",
                    "Preview token expired — re-upload the XML."));
        }

        // RFC gate: when the preview projected overwrites and the
        // operator chose to "replace", reasonForChange is required
        // (21 CFR Part 11 §11.10). When the operator picks "skip",
        // no overwrites will be applied so RFC is moot.
        int overwriteCount = parked.previewSummary().overwriteCount();
        boolean overwritesWillApply = overwriteCount > 0 && "replace".equals(overwriteMode);
        String reason = body.reasonForChange() == null ? null : body.reasonForChange().trim();
        if (overwritesWillApply && (reason == null || reason.isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "reasonForChange",
                            "message", "reasonForChange is required when overwrites will be applied"))));
        }
        ResponseEntity<?> closed = refuseIfStudyClosed(session);
        if (closed != null) return closed;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        Attempt attempt = new Attempt(me, currentStudy, token, parked, overwriteMode, reason);

        // Drop the parked attrs first so a refresh / double-click
        // can't re-enter this method against the same payload.
        session.removeAttribute(SESSION_PREFIX + token);

        if (parked.previewSummary().errorCount() > 0) {
            return refuse(attempt, 422, "The preview reported errors; correct the file and upload it again.",
                    List.of());
        }

        Locale locale = resolveLocale(acceptLanguage);
        ResourceBundleProvider.updateLocale(locale);
        ODMContainer odm = parked.odmContainer();
        ImportCRFDataService dataService = new ImportCRFDataService(dataSource, locale);

        List<String> metaErrors = dataService.validateStudyMetadata(odm, currentStudy.getId());
        if (metaErrors != null && !metaErrors.isEmpty()) {
            return refuse(attempt, 422, "The file does not match the active study.", metaErrors);
        }
        // What the preview showed must still be what happens.
        ImportRowProjection.Result current = new ImportRowProjection(dataSource).project(odm);
        if (!current.issues().isEmpty() || !sameOutcome(parked.allRows(), current.rows())) {
            return refuse(attempt, 409, "The data changed since the preview; upload the file again "
                    + "to see what the import would do now.", List.of());
        }

        // The legacy pipeline: ImportCRFDataServlet#confirm, then
        // VerifyImportedCRFDataServlet#save.
        boolean eventCRFStatusesValid = dataService.eventCRFStatusesValid(odm, me);
        // Creates the event CRFs of forms not started yet (UpsertOn NotStarted).
        List<EventCRFBean> eventCRFBeans = dataService.fetchEventCRFBeans(odm, me);
        HashMap<Integer, String> importedCRFStatuses = dataService.fetchEventCRFStatuses(odm);
        if (eventCRFBeans == null) {
            return refuse(attempt, 409, ImportCRFDataService.respage.getString("no_event_status_matching"),
                    List.of());
        }
        if (eventCRFBeans.isEmpty()) {
            return refuse(attempt, 422, ImportCRFDataService.respage.getString(eventCRFStatusesValid
                    ? "no_event_crfs_matching_the_xml_metadata" : "the_event_crf_not_correct_status"), List.of());
        }
        ArrayList<Integer> permittedEventCRFIds = new ArrayList<>();
        for (EventCRFBean eventCRFBean : eventCRFBeans) {
            DataEntryStage stage = eventCRFBean.getStage();
            if (Status.AVAILABLE.equals(eventCRFBean.getStatus())
                    || DataEntryStage.INITIAL_DATA_ENTRY.equals(stage)
                    || DataEntryStage.INITIAL_DATA_ENTRY_COMPLETE.equals(stage)
                    || DataEntryStage.DOUBLE_DATA_ENTRY_COMPLETE.equals(stage)
                    || DataEntryStage.DOUBLE_DATA_ENTRY.equals(stage)) {
                permittedEventCRFIds.add(Integer.valueOf(eventCRFBean.getId()));
            }
        }
        HashMap<String, String> totalValidationErrors = new HashMap<>();
        HashMap<String, String> hardValidationErrors = new HashMap<>();
        List<DisplayItemBeanWrapper> wrappers;
        try {
            wrappers = dataService.lookupValidationErrors(request, odm, me, totalValidationErrors,
                    hardValidationErrors, permittedEventCRFIds);
        } catch (OpenClinicaException oce) {
            return refuse(attempt, 422, oce.getOpenClinicaMessage(), List.of());
        } catch (NullPointerException npe) {
            LOG.warn("CRF import validation threw (token={})", token, npe);
            return refuse(attempt, 422,
                    ImportCRFDataService.respage.getString("an_error_was_thrown_while_validation_errors"), List.of());
        }
        if (!hardValidationErrors.isEmpty()) {
            List<String> rejected = new ArrayList<>();
            hardValidationErrors.forEach((value, message) -> rejected.add(value + ": " + message));
            return refuse(attempt, 422, "The item definitions reject some values; nothing was imported.",
                    rejected);
        }
        if ("skip".equals(overwriteMode)) {
            leaveStoredValues(wrappers);
        }

        ImportCRFDataPersistenceService persistence = new ImportCRFDataPersistenceService(dataSource);
        ImportCRFDataPersistenceService.SaveResult saved;
        List<String> ruleWarnings;
        try {
            List<ImportDataRuleRunnerContainer> containers =
                    persistence.ruleRunSetup(odm, currentStudy, me, ruleSetService);
            saved = persistence.save(wrappers, importedCRFStatuses, me, currentStudy);
            ruleWarnings = persistence.runRules(currentStudy, me, containers, ruleSetService);
        } catch (Exception e) {
            LOG.error("CRF import commit failed while saving (token={} study={} user={})",
                    token, currentStudy.getOid(), me.getName(), e);
            writeImportAudit(attempt, "outcome=failed error=" + e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(Map.of("message",
                    "The import stopped with an error and may be incomplete; check the imported "
                            + "subjects' CRFs before importing again. See the server log."));
        }

        writeReasonForChange(me, currentStudy, saved.overwrites(), reason);
        int skipped = countAction(parked.allRows(), ImportRowProjection.ACTION_SKIP)
                + ("skip".equals(overwriteMode) ? overwriteCount : 0);
        writeImportAudit(attempt, "outcome=committed inserted=" + saved.inserted()
                + " overwritten=" + saved.overwritten() + " unchanged=" + saved.unchanged()
                + " skipped=" + skipped + " notes=" + saved.discrepancyNotes());
        LOG.info("CRF import committed: token={} study={} user={} inserted={} overwritten={} skipped={} notes={}",
                token, currentStudy.getOid(), me.getName(), saved.inserted(), saved.overwritten(),
                skipped, saved.discrepancyNotes());

        return ResponseEntity.ok(new ImportCrfCommitResult(
                saved.inserted(), saved.overwritten(), skipped, saved.discrepancyNotes(),
                Instant.now().toString(), currentStudy.getId(), ruleWarnings));
    }

    /** One commit attempt, for its audit row. */
    private record Attempt(UserAccountBean me, StudyBean study, String token, ImportPreviewSession parked,
                           String overwriteMode, String reason) {}

    /** Refuse the attempt, recording the refusal in its audit row. Nothing has been written. */
    private ResponseEntity<?> refuse(Attempt attempt, int status, String message, List<String> errors) {
        LOG.info("CRF import commit refused ({}): token={} study={} user={} — {}", status, attempt.token(),
                attempt.study().getOid(), attempt.me().getName(), message);
        writeImportAudit(attempt, "outcome=refused status=" + status);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("message", message);
        List<Map<String, String>> items = new ArrayList<>();
        for (String e : errors) items.add(Map.of("field", "file", "message", e));
        resp.put("errors", items);
        return ResponseEntity.status(status).body(resp);
    }

    /**
     * Overwrite mode {@code skip}: leave every value that is stored
     * already as it is, so only values with no row yet are written.
     */
    private void leaveStoredValues(List<DisplayItemBeanWrapper> wrappers) {
        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
        for (DisplayItemBeanWrapper wrapper : wrappers) {
            List<DisplayItemBean> beans = wrapper.getDisplayItemBeans();
            if (beans == null) continue;
            beans.removeIf(dib -> itemDataDao.findByItemIdAndEventCRFIdAndOrdinal(
                    dib.getItem().getId(), dib.getData().getEventCRFId(), dib.getData().getOrdinal())
                    .getStatus() != null);
        }
    }

    /**
     * The same values, subjects, visits and actions as the preview; the
     * diagnostics may be worded in another language.
     */
    private static boolean sameOutcome(List<ImportCrfPreviewDto.PreviewRowDto> shown,
                                       List<ImportCrfPreviewDto.PreviewRowDto> now) {
        if (shown.size() != now.size()) return false;
        for (int i = 0; i < shown.size(); i++) {
            ImportCrfPreviewDto.PreviewRowDto a = shown.get(i);
            ImportCrfPreviewDto.PreviewRowDto b = now.get(i);
            if (!Objects.equals(a.status(), b.status()) || !Objects.equals(a.action(), b.action())
                    || !Objects.equals(a.before(), b.before()) || !Objects.equals(a.after(), b.after())
                    || !Objects.equals(a.subjectOid(), b.subjectOid()) || !Objects.equals(a.eventOid(), b.eventOid())
                    || !Objects.equals(a.crfOid(), b.crfOid()) || !Objects.equals(a.itemOid(), b.itemOid())) {
                return false;
            }
        }
        return true;
    }

    private static int countAction(List<ImportCrfPreviewDto.PreviewRowDto> rows, String action) {
        return (int) rows.stream().filter(r -> action.equals(r.action())).count();
    }

    /* ----------------------------------------------------------------- */
    /* Auth + plumbing                                                    */
    /* ----------------------------------------------------------------- */

    /**
     * 401 / 400 / 403 preflight for upload + commit + rows. Mirrors
     * {@link BulkImportAuthorization#roleMayImport}.
     */
    private static ResponseEntity<?> preflightWrite(HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean study = (StudyBean) session.getAttribute("study");
        if (study == null || study.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!BulkImportAuthorization.roleMayImport(me, currentRole)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit importing CRF data — sysadmin or Director/Coordinator/Investigator/RA on the active study only"));
        }
        return null;
    }

    /**
     * {@code 409} when the active study is locked or frozen (or removed):
     * legacy {@code ImportCRFDataServlet.mayProceed} refuses those with
     * {@code checkStudyLocked} and {@code checkStudyFrozen} before anything
     * else. Read from the database, not the session, which holds the study
     * as it was when it was chosen.
     */
    private ResponseEntity<?> refuseIfStudyClosed(HttpSession session) {
        StudyBean active = (StudyBean) session.getAttribute("study");
        StudyBean study = new StudyDAO(dataSource).findByPK(active.getId());
        if (StudyAdminAuthorization.studyAcceptsWrites(study)) return null;
        return ResponseEntity.status(409).body(Map.of("message",
                "The study is locked or frozen; no CRF data can be imported into it."));
    }

    /**
     * Persist the upload to {@code <filePath>/crf/original/<ts>_<safe>}.
     * Mirrors {@code ImportCRFDataServlet.processRequest #confirm}.
     */
    private static Path persistUploadedFile(MultipartFile file, String originalFilename) throws IOException {
        String filePath = CoreResources.getField("filePath");
        if (filePath == null || filePath.isBlank()) {
            filePath = System.getProperty("java.io.tmpdir");
        }
        Path baseDir = Paths.get(filePath, "crf", "original");
        Files.createDirectories(baseDir);
        String safeName = originalFilename.replaceAll("[^A-Za-z0-9._-]", "_");
        String stamped = System.currentTimeMillis() + "_" + safeName;
        Path target = baseDir.resolve(stamped);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

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

    private static ImportPreviewSession pullParked(HttpSession session, String token) {
        Object o = session.getAttribute(SESSION_PREFIX + token);
        return (o instanceof ImportPreviewSession s) ? s : null;
    }

    private static String nullToBlank(String s) { return s == null ? "" : s; }

    /* ----------------------------------------------------------------- */
    /* Preview counts                                                     */
    /* ----------------------------------------------------------------- */

    private static int countDistinctSubjects(ODMContainer odm) {
        if (odm == null || odm.getCrfDataPostImportContainer() == null
                || odm.getCrfDataPostImportContainer().getSubjectData() == null) return 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SubjectDataBean s : odm.getCrfDataPostImportContainer().getSubjectData()) {
            if (s != null && s.getSubjectOID() != null) seen.add(s.getSubjectOID());
        }
        return seen.size();
    }

    private static int countDistinctEvents(ODMContainer odm) {
        if (odm == null || odm.getCrfDataPostImportContainer() == null
                || odm.getCrfDataPostImportContainer().getSubjectData() == null) return 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SubjectDataBean s : odm.getCrfDataPostImportContainer().getSubjectData()) {
            if (s == null || s.getStudyEventData() == null) continue;
            for (StudyEventDataBean e : s.getStudyEventData()) {
                if (e != null && e.getStudyEventOID() != null) {
                    String key = e.getStudyEventOID()
                            + ":" + (e.getStudyEventRepeatKey() == null ? "1" : e.getStudyEventRepeatKey());
                    seen.add(key);
                }
            }
        }
        return seen.size();
    }

    private static int countDistinctCrfs(ODMContainer odm) {
        if (odm == null || odm.getCrfDataPostImportContainer() == null
                || odm.getCrfDataPostImportContainer().getSubjectData() == null) return 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SubjectDataBean s : odm.getCrfDataPostImportContainer().getSubjectData()) {
            if (s == null || s.getStudyEventData() == null) continue;
            for (StudyEventDataBean e : s.getStudyEventData()) {
                if (e == null || e.getFormData() == null) continue;
                for (FormDataBean f : e.getFormData()) {
                    if (f != null && f.getFormOID() != null) seen.add(f.getFormOID());
                }
            }
        }
        return seen.size();
    }

    /* ----------------------------------------------------------------- */
    /* Audit                                                              */
    /* ----------------------------------------------------------------- */

    /**
     * One {@link AuditTypeIds#BULK_IMPORT_ATTEMPTED} row per commit attempt
     * past the token check, with its outcome. It sits on the study
     * ({@code audit_table = 'study'}, the file name as entity name) so the
     * study's own audit log shows who imported which file, and carries the
     * operator's reason for change. The values the import wrote have their
     * own rows from the item_data triggers.
     */
    private void writeImportAudit(Attempt attempt, String outcome) {
        if (dataSource == null) return; // test-only path
        String summary = "token=" + attempt.token()
                + " rows=" + attempt.parked().allRows().size()
                + " overwriteMode=" + attempt.overwriteMode()
                + " " + outcome;
        String file = nullToBlank(attempt.parked().originalFilename());
        // 9-column INSERT — bulk import is the one site that carries
        // operator-supplied reason_for_change (21 CFR Part 11 §11.10),
        // so it goes inline rather than through the shared 8-column
        // writer used by the other audit-unification sweep sites.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, "
                             + "reason_for_change, old_value, new_value) "
                             + "VALUES (?, now(), ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, AuditTypeIds.BULK_IMPORT_ATTEMPTED);
            ps.setInt(2, attempt.me().getId());
            ps.setString(3, "study");
            ps.setInt(4, attempt.study().getId());
            ps.setString(5, file.length() > 500 ? file.substring(0, 500) : file);
            ps.setString(6, attempt.reason() == null ? "" : attempt.reason());
            ps.setString(7, "");
            ps.setString(8, summary);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Audit write failed for bulk_import_attempt (token={}, continuing): {}",
                    attempt.token(), e.getMessage());
        }
    }

    /**
     * The reason for change of each overwritten value, as the CRF entry
     * records it for a changed value: an
     * {@link AuditTypeIds#ITEM_DATA_REASON_FOR_CHANGE} row on the item data
     * next to the trigger's value-change row.
     */
    private void writeReasonForChange(UserAccountBean me, StudyBean study,
                                      List<ImportCRFDataPersistenceService.OverwrittenValue> overwrites,
                                      String reason) {
        if (reason == null || reason.isEmpty() || overwrites.isEmpty()) return;
        AuditEventDAO auditDao = new AuditEventDAO(dataSource);
        for (ImportCRFDataPersistenceService.OverwrittenValue o : overwrites) {
            EventCrfsApiController.writeAuditEvent(auditDao, AuditTypeIds.ITEM_DATA_REASON_FOR_CHANGE,
                    me, study, null, "item_data_import_rfc", "item_data", o.itemDataId(),
                    o.itemOid(), o.oldValue(), o.newValue(), reason, o.studyEventId());
        }
    }

    /* ----------------------------------------------------------------- */
    /* Commit request                                                     */
    /* ----------------------------------------------------------------- */

    /**
     * Commit request body.
     *
     * @param previewToken     UUID returned by the upload step.
     *                         Required; missing/blank → 400.
     * @param reasonForChange  Free-text justification; required when
     *                         the preview projected overwrites and
     *                         {@code overwriteMode == "replace"}. Per
     *                         21 CFR Part 11 §11.10.
     * @param overwriteMode    {@code "replace"} (default; existing
     *                         item_data rows are overwritten + RFC
     *                         recorded) or {@code "skip"} (overwrite
     *                         rows are dropped; no RFC required).
     */
    public record CommitRequest(
            @Schema(description = "Preview token returned by /import.") String previewToken,
            @Schema(description = "Operator-supplied reason; required when overwrites apply.") String reasonForChange,
            @Schema(description = "\"replace\" (default) or \"skip\".") String overwriteMode) {}
}
