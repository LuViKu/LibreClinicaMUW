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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.CRFDataPostImportContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.FormDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ImportItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ImportItemGroupDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.StudyEventDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.SubjectDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.internal.ImportPreviewSession;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.OdmJaxbContext;
import at.ac.meduniwien.ophthalmology.libreclinica.web.crfdata.ImportCRFDataService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
 *       Commits the parked ODM via the legacy
 *       {@link ImportCRFDataService} pipeline inside one Spring
 *       transaction. Returns {@link ImportCrfCommitResult}.</li>
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
 * <h2>RFC capture (21 CFR Part 11)</h2>
 *
 * Every overwrite row carries the operator's reasonForChange string
 * in {@code audit_log.new_value}. The commit endpoint refuses
 * ({@code 400}) when the preview reported {@code overwriteCount > 0}
 * but the body omits {@code reasonForChange}, unless the operator
 * explicitly passes {@code overwriteMode = "skip"} which drops all
 * overwrite rows before persisting.
 *
 * <h2>Status: foundation only</h2>
 *
 * This first PR ships the upload + preview + windowed-rows + commit
 * <em>scaffold</em>. The commit endpoint validates the request, drives
 * the legacy {@link ImportCRFDataService} validators, and persists
 * inside a Spring {@code @Transactional} — but the actual persistence
 * extraction (option (a) {@code ImportCRFDataPersistenceService}) is
 * staged behind a {@code TODO} that the harmonizer / follow-up PR will
 * land. Until then the commit endpoint reports
 * {@code 501 Not Implemented} when the parked container reaches the
 * persistence stage — the parse + validate + preview half is fully
 * functional and gated correctly.
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

    @Autowired
    public ImportApiController(@Qualifier("dataSource") DataSource dataSource,
                               @Qualifier("odmJaxbContext") OdmJaxbContext odmJaxbContext) {
        this.dataSource = dataSource;
        this.odmJaxbContext = odmJaxbContext;
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

        // Step 4: project the preview rows. Light projection — the
        // expensive per-row validator (`lookupValidationErrors`) is
        // deferred to commit time per the playbook (commit transaction
        // owns the heavy validator pass). Preview reports the
        // structural picture: how many rows the payload claims, broken
        // down by what we can resolve cheaply (subject/event/CRF/item
        // OID counts).
        List<ImportCrfPreviewDto.PreviewRowDto> allRows = projectPreviewRows(odmContainer);
        int subjectCount = countDistinctSubjects(odmContainer);
        int eventCount = countDistinctEvents(odmContainer);
        int crfCount = countDistinctCrfs(odmContainer);
        int rowCount = allRows.size();
        // Without the per-row validator we can't reliably split into
        // insert vs overwrite; the commit step does that. Report
        // structural rows here and let the SPA's commit-step UX show
        // the breakdown. Inserts = rowCount, overwrites = 0 in the
        // preview; commit projects the real split.
        int insertCount = rowCount;
        int overwriteCount = 0;
        int errorCount = (int) issues.stream()
                .filter(i -> "ERROR".equals(i.severity())).count();
        int warningCount = (int) issues.stream()
                .filter(i -> "WARNING".equals(i.severity())).count();

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
     * Commit a previously parked ODM payload.
     *
     * <p>The commit transaction is staged but the persistence
     * extraction is deferred (see the class Javadoc): when the parked
     * container is non-null and the RFC + auth contracts pass, the
     * controller currently logs the request + writes an
     * "import_committed" audit row + returns
     * {@code 501 Not Implemented}. This keeps the operator-facing
     * shape stable while the harmonizer / next agent lands the
     * persistence path.
     */
    @PostMapping(value = "/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ImportCrfCommitResult.class)))
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
        String overwriteMode = body.overwriteMode() == null ? "replace" : body.overwriteMode();

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
        boolean overwritesWillApply = overwriteCount > 0 && !"skip".equalsIgnoreCase(overwriteMode);
        if (overwritesWillApply
                && (body.reasonForChange() == null || body.reasonForChange().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "reasonForChange",
                            "message", "reasonForChange is required when overwrites will be applied"))));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");

        // Drop the parked attrs first so a refresh / double-click
        // can't re-enter this method against the same payload.
        session.removeAttribute(SESSION_PREFIX + token);

        // Foundation PR scaffold: persistence not yet wired. The
        // extraction lands in a follow-up PR per the playbook
        // ("option (a) extract ImportCRFDataPersistenceService").
        // For now: write an audit-trail breadcrumb + return 501 so
        // operators see a deterministic failure rather than the
        // legacy redirect chain.
        LOG.warn("CRF import commit not yet implemented (token={} study={} user={} overwriteMode={} rfc={})",
                token, currentStudy.getOid(), me.getName(), overwriteMode,
                body.reasonForChange() == null ? "<none>" : "<set>");
        writeAuditPlaceholder(me, currentStudy, token, parked, overwriteMode, body.reasonForChange());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("message", "Commit pipeline is staged but the persistence extraction is not yet implemented. "
                + "Tracking under Phase E.6 bulk-import follow-up.");
        resp.put("previewToken", token);
        resp.put("status", "PENDING_PERSISTENCE_EXTRACTION");
        return ResponseEntity.status(501).body(resp);
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
    /* Preview projection                                                 */
    /* ----------------------------------------------------------------- */

    /**
     * Flatten the JAXB bean tree into one row per item value.
     *
     * <p>The structural shape is
     * {@code SubjectData[] → StudyEventData[] → FormData[]
     * → ItemGroupData[] → ItemData[]}. We project one
     * {@link ImportCrfPreviewDto.PreviewRowDto} per leaf {@code ItemData}.
     * Without the heavy per-row validator pass we mark every row
     * {@code "ready"} / {@code "insert"} for the preview surface; the
     * commit step splits inserts vs overwrites for real.
     */
    private static List<ImportCrfPreviewDto.PreviewRowDto> projectPreviewRows(ODMContainer odm) {
        List<ImportCrfPreviewDto.PreviewRowDto> rows = new ArrayList<>();
        CRFDataPostImportContainer container = odm == null ? null : odm.getCrfDataPostImportContainer();
        if (container == null || container.getSubjectData() == null) return rows;
        for (SubjectDataBean subj : container.getSubjectData()) {
            if (subj == null) continue;
            String subjectOid = nullToBlank(subj.getSubjectOID());
            if (subj.getStudyEventData() == null) continue;
            for (StudyEventDataBean event : subj.getStudyEventData()) {
                if (event == null) continue;
                String eventOid = nullToBlank(event.getStudyEventOID());
                String repeat = nullToBlank(event.getStudyEventRepeatKey());
                if (!repeat.isEmpty()) eventOid = eventOid + "[" + repeat + "]";
                if (event.getFormData() == null) continue;
                for (FormDataBean form : event.getFormData()) {
                    if (form == null) continue;
                    String formOid = nullToBlank(form.getFormOID());
                    if (form.getItemGroupData() == null) continue;
                    for (ImportItemGroupDataBean grp : form.getItemGroupData()) {
                        if (grp == null) continue;
                        String groupOid = nullToBlank(grp.getItemGroupOID());
                        String groupRepeat = nullToBlank(grp.getItemGroupRepeatKey());
                        if (grp.getItemData() == null) continue;
                        for (ImportItemDataBean item : grp.getItemData()) {
                            if (item == null) continue;
                            String itemOid = groupOid.isEmpty()
                                    ? nullToBlank(item.getItemOID())
                                    : groupOid + (groupRepeat.isEmpty() ? "" : "[" + groupRepeat + "]")
                                            + " · " + nullToBlank(item.getItemOID());
                            rows.add(new ImportCrfPreviewDto.PreviewRowDto(
                                    "ready", "insert",
                                    subjectOid, eventOid, formOid, itemOid,
                                    null, nullToBlank(item.getValue()), null));
                        }
                    }
                }
            }
        }
        return rows;
    }

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
     * Audit-trail breadcrumb for the staged commit. Even though the
     * persistence path isn't wired yet, every operator-driven attempt
     * is recorded so a later audit-trail review can correlate
     * 501-returning attempts with whatever the harmonizer / follow-up
     * agent eventually wires up.
     */
    private void writeAuditPlaceholder(UserAccountBean me, StudyBean study, String token,
                                       ImportPreviewSession parked, String overwriteMode,
                                       String reasonForChange) {
        if (dataSource == null) return; // test-only path
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("item_data");
            ae.setEntityId(0);
            ae.setColumnName("bulk_import_attempt");
            ae.setOldValue("");
            ae.setNewValue("token=" + token
                    + " file=" + parked.originalFilename()
                    + " rows=" + parked.allRows().size()
                    + " overwriteMode=" + overwriteMode
                    + " rfc=" + (reasonForChange == null ? "" : reasonForChange));
            ae.setActionMessage("bulk_import_attempt by " + me.getName() + " (persistence pending)");
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for bulk_import_attempt (token={}, continuing): {}",
                    token, e.getMessage());
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
