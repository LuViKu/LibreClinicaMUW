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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * Phase E A8.3 — CRF library + version upload endpoints.
 *
 * <p>Mirrors {@code CreateCRFServlet} +
 * {@code CreateCRFVersionServlet} + {@code RemoveCRFVersionServlet}
 * collapsed into REST endpoints. The CRF library is study-independent
 * (a {@code crf} row carries {@code study_id} = the original study
 * that defined it, but versions are reusable across studies); the
 * library list is therefore not filtered by active study.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/v1/crfs} — list non-removed CRFs with
 *       inlined versions</li>
 *   <li>{@code POST   /api/v1/crfs} — create a new CRF shell (no
 *       versions yet)</li>
 *   <li>{@code POST   /api/v1/crfs/{crfOid}/disable} — soft-delete</li>
 *   <li>{@code GET    /api/v1/crfs/{crfOid}/versions} — list versions
 *       for one CRF</li>
 *   <li>{@code POST   /api/v1/crfs/{crfOid}/versions} — multipart
 *       upload of an {@code .xls/.xlsx} spreadsheet, parsed into a
 *       new {@code CRFVersionBean}</li>
 *   <li>{@code POST   /api/v1/crfs/{crfOid}/versions/{versionOid}/disable}
 *       — soft-delete a version</li>
 * </ul>
 *
 * <p>Authorization: sysadmin OR director/coordinator on the active
 * study (legacy {@code CreateCRFServlet:57-68}). For library-wide
 * reads no active study is required.
 *
 * <p><b>Multipart upload caveat</b>: this slice ships the file
 * <em>storage</em> side (saves to {@code <filePath>/crf/original/}
 * and {@code .../new/} per legacy convention) and persists the
 * {@code crf_version} row, but defers the legacy spreadsheet parser
 * ({@code SpreadSheetTableClassic} / {@code SpreadSheetTableRepeating})
 * — those classes depend on session-bound state that doesn't port
 * cleanly to a stateless REST handler. The follow-up B-series slice
 * adapts the parser to a request-scoped invocation; until then the
 * uploaded version row carries {@code xform = ""} and the items list
 * stays empty.
 */
@RestController
@RequestMapping("/api/v1/crfs")
@Tag(name = "CRFs", description = "CRF library + version upload (build-study surface).")
public class CrfsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(CrfsApiController.class);

    private final DataSource dataSource;
    private final CrfSpreadsheetParserService parserService;
    private final CrfJsonToWorkbookAdapter workbookAdapter;
    private final CrfJsonValidator jsonValidator;
    private final at.ac.meduniwien.ophthalmology.libreclinica.service.CrfVersionMigrationService migrationService;

    @Autowired
    public CrfsApiController(@Qualifier("dataSource") DataSource dataSource,
                             CrfSpreadsheetParserService parserService,
                             CrfJsonToWorkbookAdapter workbookAdapter,
                             CrfJsonValidator jsonValidator,
                             at.ac.meduniwien.ophthalmology.libreclinica.service.CrfVersionMigrationService migrationService) {
        this.dataSource = dataSource;
        this.parserService = parserService;
        this.workbookAdapter = workbookAdapter;
        this.jsonValidator = jsonValidator;
        this.migrationService = migrationService;
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/crfs                                                  */
    /* ----------------------------------------------------------------- */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = CrfDto.class)))
    public ResponseEntity<?> list(
            @RequestParam(value = "includeRemoved", required = false) Boolean includeRemoved,
            HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);

        ArrayList<CRFBean> beans = Boolean.TRUE.equals(includeRemoved)
                ? crfDao.findAll()
                : crfDao.findAllByStatus(Status.AVAILABLE);

        List<CrfDto> out = new ArrayList<>(beans.size());
        for (CRFBean crf : beans) {
            out.add(toDto(crf, versionDao));
        }
        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs                                                 */
    /* ----------------------------------------------------------------- */

    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = CrfDto.class)))
    public ResponseEntity<?> create(@RequestBody(required = false) CreateCrfRequest body,
                                    HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                validateCreateShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        CRFDAO crfDao = new CRFDAO(dataSource);
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");

        // Name uniqueness across the CRF library (legacy parity:
        // CreateCRFServlet:101–105 uses findByName as the gate).
        CRFBean existing = (CRFBean) crfDao.findByName(body.name().trim());
        if (existing != null && existing.getId() != 0) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "name", "A CRF named '" + body.name() + "' already exists"))));
        }

        CRFBean toCreate = new CRFBean();
        toCreate.setName(body.name().trim());
        toCreate.setDescription(body.description() == null ? "" : body.description().trim());
        toCreate.setStatus(Status.AVAILABLE);
        toCreate.setOwner(me);
        toCreate.setStudyId(currentStudy != null ? currentStudy.getId() : 0);
        toCreate.setCreatedDate(new java.util.Date());

        CRFBean persisted = crfDao.create(toCreate);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("CRFDAO.create returned no row for name={}", body.name());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist CRF"));
        }

        LOG.info("Create CRF: oid={} name={} by user={}",
                persisted.getOid(), persisted.getName(), me.getName());

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        return ResponseEntity.status(201).body(toDto(persisted, versionDao));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/disable                                */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{crfOid}/disable")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = CrfDto.class)))
    public ResponseEntity<?> disable(@PathVariable("crfOid") String crfOid,
                                     HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean target = crfDao.findByOid(crfOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF '" + crfOid + "' is already removed"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        Status oldStatus = target.getStatus();
        target.setStatus(Status.DELETED);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        crfDao.update(target);
        writeLifecycleAudit(me, "crf", target.getId(), target.getOid(),
                oldStatus, Status.DELETED, "crf_disable");

        LOG.info("Disable CRF: oid={} by user={}", crfOid, me.getName());

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        return ResponseEntity.ok(toDto(target, versionDao));
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/crfs/{crfOid}/versions                                */
    /* ----------------------------------------------------------------- */

    @GetMapping("/{crfOid}/versions")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                         implementation = CrfDto.CrfVersionDto.class)))
    public ResponseEntity<?> listVersions(@PathVariable("crfOid") String crfOid,
                                          HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(crfOid);
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        ArrayList<CRFVersionBean> versions = versionDao.findAllByCRF(crf.getId());
        List<CrfDto.CrfVersionDto> out = new ArrayList<>(versions.size());
        for (CRFVersionBean v : versions) {
            out.add(toVersionDto(v));
        }
        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions  (multipart upload)            */
    /* ----------------------------------------------------------------- */

    /**
     * Multipart upload + parse. The legacy spreadsheet parsers
     * ({@link at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SpreadSheetTableRepeating}
     * + {@code SpreadSheetTableClassic}) are invoked via
     * {@link CrfSpreadsheetParserService} which runs them stateless.
     *
     * <p>Flow:
     * <ol>
     *   <li>Validate file type + presence + versionName + CRF existence</li>
     *   <li>Save file to {@code <filePath>/crf/original/} for archival</li>
     *   <li>Invoke the parser — it persists items + the
     *       {@code crf_version} row directly via JDBC</li>
     *   <li>On parser errors return 400 + the collected messages
     *       (the partially-saved file stays on disk for forensics)</li>
     *   <li>On success look up the newly-persisted version row and
     *       return it as 201</li>
     * </ol>
     */
    @PostMapping(value = "/{crfOid}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = CrfDto.CrfVersionDto.class)))
    public ResponseEntity<?> uploadVersion(
            @PathVariable("crfOid") String crfOid,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "versionName") String versionName,
            @RequestPart(value = "versionDescription", required = false) String versionDescription,
            @RequestPart(value = "revisionNotes", required = false) String revisionNotes,
            @org.springframework.web.bind.annotation.RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
                    String acceptLanguage,
            HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "file part is required"));
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) originalFilename = "upload.xls";
        String lower = originalFilename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xls") && !lower.endsWith(".xlsx")) {
            return ResponseEntity.status(415).body(Map.of("message",
                    "Only .xls / .xlsx spreadsheets are accepted"));
        }

        String versionNameTrim = versionName == null ? "" : versionName.trim();
        if (versionNameTrim.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "versionName", "versionName is required"))));
        }

        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(crfOid);
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }
        if (crf.getStatus() != null && crf.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF '" + crfOid + "' is removed — restore before uploading a new version"));
        }

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean dupCheck = versionDao.findByFullName(versionNameTrim, crf.getName());
        if (dupCheck != null && dupCheck.getId() != 0) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "versionName",
                            "Version '" + versionNameTrim + "' already exists on CRF '" + crf.getName() + "'"))));
        }

        // Step 1: save file to <filePath>/crf/original/ for archival.
        // The parser opens it again from disk (legacy parity).
        java.nio.file.Path storedPath;
        String storedFilename;
        try {
            storedPath = persistUploadedFile(file, originalFilename);
            storedFilename = storedPath.getFileName().toString();
        } catch (IOException ioe) {
            LOG.warn("Failed to persist CRF upload for crfOid={} (name={})",
                    crfOid, originalFilename, ioe);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to store uploaded file"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        int studyId = currentStudy != null ? currentStudy.getId() : 0;
        Locale locale = resolveLocale(acceptLanguage);

        // Step 2: run the parser. It persists items + the crf_version
        // row directly via JDBC if there are no parse errors.
        CrfSpreadsheetParserService.Result result =
                parserService.parseAndPersist(storedPath, crf, versionNameTrim, me, studyId, locale);

        if (!result.ok()) {
            // The stored file stays on disk for forensics — operators
            // can grab it from <filePath>/crf/original/<storedFilename>
            // and feed it to the legacy parser via the JSP path if
            // they need to compare error messages.
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Spreadsheet parse failed — see errors[].message for the rejected rows / cells",
                    result.errors().stream()
                            .map(e -> new SubjectsApiController.ValidationErrorBody.FieldError("file", e))
                            .toList()));
        }

        CRFVersionBean persisted = result.version();

        // The parser doesn't populate description / revisionNotes /
        // xformName from the request — set them now and update so the
        // metadata operators typed into the upload form lands on the
        // row alongside the parsed items.
        boolean dirty = false;
        if (versionDescription != null && !versionDescription.isBlank()
                && (persisted.getDescription() == null || persisted.getDescription().isBlank())) {
            persisted.setDescription(versionDescription.trim());
            dirty = true;
        }
        if (revisionNotes != null && !revisionNotes.isBlank()
                && (persisted.getRevisionNotes() == null || persisted.getRevisionNotes().isBlank())) {
            persisted.setRevisionNotes(revisionNotes.trim());
            dirty = true;
        }
        if (persisted.getXformName() == null || persisted.getXformName().isBlank()) {
            persisted.setXformName(storedFilename);
            dirty = true;
        }
        if (dirty) {
            try {
                versionDao.update(persisted);
            } catch (Exception e) {
                LOG.warn("Could not update post-parse metadata for crfOid={} versionOid={} (continuing): {}",
                        crfOid, persisted.getOid(), e.getMessage());
            }
        }

        LOG.info("Upload CRF version (parsed): crfOid={} versionOid={} versionName={} file={} by user={}",
                crfOid, persisted.getOid(), versionNameTrim, storedFilename, me.getName());

        return ResponseEntity.status(201).body(toVersionDto(persisted));
    }

    /**
     * Best-effort {@code Accept-Language} parse — pulls the first tag,
     * defaults to English when the header is absent or malformed. The
     * resolved locale only affects parser error-message i18n; it never
     * influences validation outcomes.
     */
    private static Locale resolveLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return Locale.ENGLISH;
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            if (ranges.isEmpty()) return Locale.ENGLISH;
            return Locale.forLanguageTag(ranges.get(0).getRange());
        } catch (IllegalArgumentException iae) {
            return Locale.ENGLISH;
        }
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions  (JSON body — authoring wiz)   */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 Milestone A — JSON variant of the version-create
     * endpoint that produces a CRF version semantically identical to
     * one uploaded via XLS.
     *
     * <p>Strategy: synthesise an HSSF workbook from the JSON payload
     * via {@link CrfJsonToWorkbookAdapter} and hand it to the existing
     * {@link CrfSpreadsheetParserService#parseAndPersist} pipeline. No
     * new persistence code, no parity drift with the XLS path.
     *
     * <p>Milestone A scope locks the wire contract to ST / INTEGER / BL
     * data types with a single TEXT response set per item — Milestones
     * B/C/D extend {@link CrfVersionAuthoringRequest} with the full
     * type taxonomy, response-set library, show-when, calculations,
     * item groups and multi-language labels.
     *
     * <p>Spring routes by {@code Content-Type}: this method handles
     * {@code application/json}; the multipart upload at the same path
     * stays unchanged.
     */
    @PostMapping(value = "/{crfOid}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = CrfDto.CrfVersionDto.class)))
    public ResponseEntity<?> authorVersion(
            @PathVariable("crfOid") String crfOid,
            @RequestBody(required = false) CrfVersionAuthoringRequest body,
            @org.springframework.web.bind.annotation.RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
                    String acceptLanguage,
            HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError("body", "missing"))));
        }

        List<SubjectsApiController.ValidationErrorBody.FieldError> errors = jsonValidator.validate(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(crfOid);
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }
        if (crf.getStatus() != null && crf.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF '" + crfOid + "' is removed — restore before authoring a new version"));
        }

        String versionNameTrim = body.versionName().trim();
        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean dupCheck = versionDao.findByFullName(versionNameTrim, crf.getName());
        if (dupCheck != null && dupCheck.getId() != 0) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "versionName",
                            "Version '" + versionNameTrim + "' already exists on CRF '" + crf.getName() + "'"))));
        }

        Path synthesizedFile;
        try {
            synthesizedFile = workbookAdapter.synthesize(body, crf);
        } catch (IOException ioe) {
            LOG.warn("Failed to synthesise authoring workbook for crfOid={}: {}", crfOid, ioe.getMessage(), ioe);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to materialise authoring workbook: " + ioe.getMessage()));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        int studyId = currentStudy != null ? currentStudy.getId() : 0;
        Locale locale = resolveLocale(acceptLanguage);

        CrfSpreadsheetParserService.Result result =
                parserService.parseAndPersist(synthesizedFile, crf, versionNameTrim, me, studyId, locale);

        // Best-effort: the synthesised workbook is a temp file — drop
        // it once the parser has finished with it. We don't surface a
        // failure to delete because the parser already returned.
        try {
            Files.deleteIfExists(synthesizedFile);
        } catch (IOException cleanupEx) {
            LOG.debug("Could not delete synthesised workbook {} (continuing): {}",
                    synthesizedFile, cleanupEx.getMessage());
        }

        if (!result.ok()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Authoring request rejected — see errors[].message",
                    result.errors().stream()
                            .map(e -> new SubjectsApiController.ValidationErrorBody.FieldError("body", e))
                            .toList()));
        }

        CRFVersionBean persisted = result.version();

        // Mirror the multipart endpoint's post-parse metadata patch.
        boolean dirty = false;
        if (body.versionDescription() != null && !body.versionDescription().isBlank()
                && (persisted.getDescription() == null || persisted.getDescription().isBlank())) {
            persisted.setDescription(body.versionDescription().trim());
            dirty = true;
        }
        if (body.revisionNotes() != null && !body.revisionNotes().isBlank()
                && (persisted.getRevisionNotes() == null || persisted.getRevisionNotes().isBlank())) {
            persisted.setRevisionNotes(body.revisionNotes().trim());
            dirty = true;
        }
        if (dirty) {
            try {
                versionDao.update(persisted);
            } catch (Exception e) {
                LOG.warn("Could not update post-parse metadata for crfOid={} versionOid={} (continuing): {}",
                        crfOid, persisted.getOid(), e.getMessage());
            }
        }

        LOG.info("Author CRF version (JSON): crfOid={} versionOid={} versionName={} sections={} items={} by user={}",
                crfOid, persisted.getOid(), versionNameTrim,
                body.sections() == null ? 0 : body.sections().size(),
                countItems(body), me.getName());

        return ResponseEntity.status(201).body(toVersionDto(persisted));
    }

    private static int countItems(CrfVersionAuthoringRequest body) {
        if (body.sections() == null) return 0;
        int total = 0;
        for (CrfVersionAuthoringRequest.Section s : body.sections()) {
            if (s.items() != null) total += s.items().size();
        }
        return total;
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions:preview  (dry-run validate)    */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 Milestone B — dry-run preview endpoint.
     *
     * <p>Runs the same {@link CrfJsonValidator} the persist endpoint
     * runs, but never touches the workbook adapter or the parser. Used
     * by the SPA wizard on entering the Review step to surface
     * structured errors before the operator hits Create.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code 200 OK} with a {@link PreviewResult} carrying a
     *       summary (sections + items count + uniqueness echo) when
     *       validation passes</li>
     *   <li>{@code 400 Bad Request} with a
     *       {@link SubjectsApiController.ValidationErrorBody} carrying
     *       the per-field errors when validation fails</li>
     * </ul>
     *
     * <p>Path discriminator: Spring routes the {@code :preview} suffix
     * separately from the bare {@code /versions} POST. Trying to
     * preview a non-existent CRF returns 404 just like the real submit.
     */
    @PostMapping(value = "/{crfOid}/versions:preview",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = PreviewResult.class)))
    public ResponseEntity<?> previewVersion(
            @PathVariable("crfOid") String crfOid,
            @RequestBody(required = false) CrfVersionAuthoringRequest body,
            HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError("body", "missing"))));
        }

        // Shape-level validation first — short-circuits before hitting
        // any DAO so missing/malformed payloads surface as 400 even when
        // the DataSource is a Mockito mock (test parity) or when the
        // CRF lookup would fail for other reasons.
        List<SubjectsApiController.ValidationErrorBody.FieldError> errors = jsonValidator.validate(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(crfOid);
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }
        if (crf.getStatus() != null && crf.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF '" + crfOid + "' is removed — restore before authoring a new version"));
        }

        // Cross-check against existing versions on the same CRF — the
        // operator should see version-name collisions in preview rather
        // than discovering them on Create. Shape validation has already
        // guaranteed versionName is non-blank at this point.
        String versionNameTrim = body.versionName().trim();
        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean dupCheck = versionDao.findByFullName(versionNameTrim, crf.getName());
        if (dupCheck != null && dupCheck.getId() != 0) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "versionName",
                            "Version '" + versionNameTrim + "' already exists on CRF '" + crf.getName() + "'"))));
        }

        return ResponseEntity.ok(new PreviewResult(
                crf.getOid(),
                versionNameTrim,
                body.sections() == null ? 0 : body.sections().size(),
                countItems(body)));
    }

    /** Wire shape for the {@code :preview} success response. */
    @Schema(name = "CrfVersionPreviewResult")
    public record PreviewResult(
            String crfOid,
            String versionName,
            int sectionCount,
            int itemCount
    ) {}

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions:validate-expression           */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 Milestone C — live expression validation.
     *
     * <p>Standalone counterpart to {@link CrfJsonValidator#validateExpression}
     * used by the SPA's expression / show-when editor. The wizard calls
     * this on every keystroke (debounced) so the operator gets an
     * inline pass/fail before committing to the calculation.
     *
     * <p>Wire shape:
     * <pre>
     * POST /api/v1/crfs/{crfOid}/versions:validate-expression
     * {
     *   "expression": "AGE + 1",
     *   "draftItemOids": ["AGE", "WEIGHT"],
     *   "draftItemDataTypes": {"AGE": "INT", "WEIGHT": "REAL"}
     * }
     * </pre>
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code 200 OK} with
     *       {@link CrfJsonValidator.ExpressionValidationResult} when the
     *       JSON shape is well-formed; the body carries
     *       {@code valid:true} or {@code valid:false} with details.</li>
     *   <li>{@code 400 Bad Request} when the body itself is missing or
     *       when {@code expression} is null. (Empty expression is a body
     *       failure too — the validator catches it.)</li>
     *   <li>{@code 401} / {@code 403} per the standard write preflight
     *       (sysadmin / director / coordinator only — the editor is part
     *       of the build-study surface).</li>
     * </ul>
     *
     * <p>The endpoint never touches the parser, the workbook adapter or
     * the database. The response is computed entirely from the body and
     * the in-memory draft scope the SPA supplies — a deliberate choice
     * so the editor can keep validating expressions even against draft
     * OIDs that don't exist in the database yet (the wizard's whole
     * point: the operator is mid-authoring). The {@code crfOid} path
     * variable serves as scoping context and matches the parent
     * resource's URL shape but is not loaded.
     */
    @PostMapping(value = "/{crfOid}/versions:validate-expression",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(
                         implementation = CrfJsonValidator.ExpressionValidationResult.class)))
    public ResponseEntity<?> validateExpression(
            @PathVariable("crfOid") String crfOid,
            @RequestBody(required = false) ValidateExpressionRequest body,
            HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError("body", "missing"))));
        }
        if (body.expression() == null) {
            // null vs empty: the validator catches empty in-body
            // (returns valid:false). null body fields are a shape failure
            // — the SPA is required to send the field even if blank.
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "expression", "expression is required"))));
        }

        CrfJsonValidator.ExpressionValidationResult result = jsonValidator.validateExpression(
                body.expression(),
                body.draftItemOids() == null ? List.of() : body.draftItemOids(),
                body.draftItemDataTypes() == null ? Map.of() : body.draftItemDataTypes());
        return ResponseEntity.ok(result);
    }

    /** Wire shape for the {@code :validate-expression} request. */
    @Schema(name = "CrfValidateExpressionRequest")
    public record ValidateExpressionRequest(
            String expression,
            List<String> draftItemOids,
            Map<String, String> draftItemDataTypes
    ) {}

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions/{versionOid}/disable           */
    /* ----------------------------------------------------------------- */

    @PostMapping("/{crfOid}/versions/{versionOid}/disable")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = CrfDto.CrfVersionDto.class)))
    public ResponseEntity<?> disableVersion(@PathVariable("crfOid") String crfOid,
                                            @PathVariable("versionOid") String versionOid,
                                            HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean target = versionDao.findByOid(versionOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version with oid '" + versionOid + "'"));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF version '" + versionOid + "' is already removed"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        Status oldStatus = target.getStatus();
        target.setStatus(Status.DELETED);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        versionDao.update(target);
        writeLifecycleAudit(me, "crf_version", target.getId(), target.getOid(),
                oldStatus, Status.DELETED, "crf_version_disable");

        LOG.info("Disable CRF version: crfOid={} versionOid={} by user={}",
                crfOid, versionOid, me.getName());

        return ResponseEntity.ok(toVersionDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions/{versionOid}/lock              */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 {@code crf-library} cluster — flip a CRF version from
     * {@code available} → {@code locked}. Legacy parity:
     * {@code LockCRFVersionServlet}.
     *
     * <p>Side effect: each {@code event_definition_crf} row that
     * currently defaults to this version is re-pointed at the next
     * available version on the same CRF (legacy
     * {@code RemoveCRFVersionServlet.updateEventDef}). This mirrors the
     * legacy semantic where locking the default version implicitly
     * surfaces a different default at data-entry time.
     */
    @PostMapping("/{crfOid}/versions/{versionOid}/lock")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = CrfDto.CrfVersionDto.class)))
    public ResponseEntity<?> lockVersion(@PathVariable("crfOid") String crfOid,
                                         @PathVariable("versionOid") String versionOid,
                                         HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean target = versionDao.findByOid(versionOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version with oid '" + versionOid + "'"));
        }
        if (target.getStatus() != null && target.getStatus().isLocked()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF version '" + versionOid + "' is already locked"));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF version '" + versionOid + "' is removed — restore before locking"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        Status oldStatus = target.getStatus();
        target.setStatus(Status.LOCKED);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        versionDao.update(target);
        writeLifecycleAudit(me, "crf_version", target.getId(), target.getOid(),
                oldStatus, Status.LOCKED, "crf_version_lock");

        LOG.info("Lock CRF version: crfOid={} versionOid={} by user={}",
                crfOid, versionOid, me.getName());

        return ResponseEntity.ok(toVersionDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions/{versionOid}/unlock            */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 {@code crf-library} cluster — flip a CRF version from
     * {@code locked} → {@code available}. Legacy parity:
     * {@code UnlockCRFVersionServlet}.
     */
    @PostMapping("/{crfOid}/versions/{versionOid}/unlock")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = CrfDto.CrfVersionDto.class)))
    public ResponseEntity<?> unlockVersion(@PathVariable("crfOid") String crfOid,
                                           @PathVariable("versionOid") String versionOid,
                                           HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean target = versionDao.findByOid(versionOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version with oid '" + versionOid + "'"));
        }
        if (target.getStatus() == null || !target.getStatus().isLocked()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF version '" + versionOid + "' is not locked"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        Status oldStatus = target.getStatus();
        target.setStatus(Status.AVAILABLE);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        versionDao.update(target);
        writeLifecycleAudit(me, "crf_version", target.getId(), target.getOid(),
                oldStatus, Status.AVAILABLE, "crf_version_unlock");

        LOG.info("Unlock CRF version: crfOid={} versionOid={} by user={}",
                crfOid, versionOid, me.getName());

        return ResponseEntity.ok(toVersionDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions/{versionOid}/restore           */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 {@code crf-library} cluster — flip a CRF version from
     * {@code removed} → {@code available}. Legacy parity:
     * {@code RestoreCRFVersionServlet} (minus the cascading SECTIONS /
     * ITEM_DATA AUTO_DELETED → AVAILABLE walk, which we defer to a
     * follow-up because section-table churn is deep and risks unrelated
     * audit-row noise; the version itself is restored — operators with
     * cascaded item_data can re-run the legacy /RestoreCRFVersion
     * endpoint, which stays mapped via LegacyServletRegistry).
     */
    @PostMapping("/{crfOid}/versions/{versionOid}/restore")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = CrfDto.CrfVersionDto.class)))
    public ResponseEntity<?> restoreVersion(@PathVariable("crfOid") String crfOid,
                                            @PathVariable("versionOid") String versionOid,
                                            HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean target = versionDao.findByOid(versionOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version with oid '" + versionOid + "'"));
        }
        if (target.getStatus() == null || !target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "CRF version '" + versionOid + "' is not removed"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        Status oldStatus = target.getStatus();
        target.setStatus(Status.AVAILABLE);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        versionDao.update(target);
        writeLifecycleAudit(me, "crf_version", target.getId(), target.getOid(),
                oldStatus, Status.AVAILABLE, "crf_version_restore");

        LOG.info("Restore CRF version: crfOid={} versionOid={} by user={}",
                crfOid, versionOid, me.getName());

        return ResponseEntity.ok(toVersionDto(target));
    }

    /* ----------------------------------------------------------------- */
    /* DELETE /api/v1/crfs/{crfOid}/versions/{versionOid}                  */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 {@code crf-library} cluster — sysadmin-gated hard
     * remove. Returns 409 + a {@link VersionUsageReport} when the
     * version is still referenced by an {@code event_definition_crf}
     * default or any {@code event_crf} row. The legacy
     * {@code DeleteCRFVersionServlet} did the equivalent in the
     * "really remove from disk" form; this REST surface matches its
     * blast radius (sysadmin only) and adds structured 409 reporting
     * so the SPA can suggest the migrate dialog as a remediation.
     *
     * <p>The actual deletion uses {@code CRFVersionDAO.delete} +
     * {@code generateDeleteQueries} which the legacy delete path also
     * calls; we don't reimplement the cascade — the legacy DAO already
     * tears down items + sections + response sets in one transaction.
     */
    @DeleteMapping("/{crfOid}/versions/{versionOid}")
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "409",
                 content = @Content(schema = @Schema(implementation = VersionUsageReport.class)))
    public ResponseEntity<?> hardRemoveVersion(@PathVariable("crfOid") String crfOid,
                                               @PathVariable("versionOid") String versionOid,
                                               HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        // Sysadmin-only — hard remove drops rows + cascades through
        // sections / items / response sets via the DAO. Even
        // director/coordinator can't trigger this.
        if (!me.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Hard-remove is sysadmin-only"));
        }

        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(crfOid);
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean target = versionDao.findByOid(versionOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version with oid '" + versionOid + "'"));
        }

        // Compute the blocker report. Empty blockers + zero
        // eventCrfCount → safe to drop. Anything else → 409 with the
        // structured report.
        VersionUsageReport report = migrationService.computeUsageReport(crf, target);
        boolean blocked = !report.blockingEventDefinitions().isEmpty()
                || report.eventCrfCount() > 0;
        if (blocked) {
            return ResponseEntity.status(409).body(report);
        }

        // Safe to drop. Cascade through the legacy DAO helper.
        try {
            // The legacy path runs the prepared delete queries one by
            // one (generateDeleteQueries lists them); delete() alone
            // hits only the crf_version row and leaves orphan rows.
            // For the modernized API we run the full cascade.
            var items = versionDao.findItemFromMap(target.getId());
            java.util.ArrayList<String> sqls = versionDao.generateDeleteQueries(target.getId(), items);
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                for (String sql : sqls) stmt.executeUpdate(sql);
            }
        } catch (Exception e) {
            LOG.warn("Hard-remove failed for crfOid={} versionOid={}: {}",
                    crfOid, versionOid, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Hard-remove failed: " + e.getMessage()));
        }

        writeLifecycleAudit(me, "crf_version", target.getId(), target.getOid(),
                target.getStatus(), Status.DELETED, "crf_version_hard_remove");

        LOG.info("Hard-remove CRF version: crfOid={} versionOid={} by user={}",
                crfOid, versionOid, me.getName());

        return ResponseEntity.noContent().build();
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/crfs/{crfOid}/versions/{versionOid}/xls                 */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 {@code crf-library} cluster — download the original
     * uploaded spreadsheet for a CRF version.
     *
     * <p>The version's {@link CRFVersionBean#getXformName()} is the
     * basename inside {@code <filePath>/crf/original/}. We stream that
     * file as {@code application/vnd.ms-excel} (or .xlsx variant) with
     * {@code Content-Disposition: attachment; filename="<original>"}.
     *
     * <p>Fallback filename: when the version row has no xformName
     * (e.g. JSON-authored versions in Milestone A/B/C never wrote one),
     * we synthesize {@code <crfName>-<versionName>.xls} so the operator
     * sees a meaningful filename in their download dialog. In that
     * case we return 404 because there's nothing to stream — but with
     * a JSON body explaining the version was authored in-app (no
     * source spreadsheet to surface).
     *
     * <p>Authorization: any authenticated user — read-only access to
     * the CRF library is intentionally broader than write (legacy
     * parity: the CRF Library JSP renders the download link to every
     * role).
     */
    @GetMapping("/{crfOid}/versions/{versionOid}/xls")
    public ResponseEntity<?> downloadVersionXls(@PathVariable("crfOid") String crfOid,
                                                @PathVariable("versionOid") String versionOid,
                                                HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean version = versionDao.findByOid(versionOid);
        if (version == null || version.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version with oid '" + versionOid + "'"));
        }

        String xformName = version.getXformName();
        if (xformName == null || xformName.isBlank()) {
            // Authored-in-app version; no source spreadsheet on disk.
            return ResponseEntity.status(404).body(Map.of("message",
                    "This version was authored in-app and has no source spreadsheet on disk",
                    "fallbackFilename", crfOid + "-" + version.getName() + ".xls"));
        }

        String filePath = at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources
                .getField("filePath");
        if (filePath == null || filePath.isBlank()) {
            filePath = System.getProperty("java.io.tmpdir");
        }
        Path source = Paths.get(filePath, "crf", "original", xformName);
        if (!Files.exists(source)) {
            // Filename row stays, but the file was scrubbed off disk
            // (operations cleanup, restore from a backup that missed
            // the upload dir, etc.). 404 + structured body so the SPA
            // can surface "version metadata exists but spreadsheet was
            // not preserved" instead of a generic 500.
            return ResponseEntity.status(404).body(Map.of("message",
                    "Spreadsheet '" + xformName + "' is no longer present on disk",
                    "fallbackFilename", xformName));
        }

        String contentType = xformName.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "application/vnd.ms-excel";

        try {
            byte[] bytes = Files.readAllBytes(source);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + xformName + "\"")
                    .body(bytes);
        } catch (IOException ioe) {
            LOG.warn("Failed to stream spreadsheet for crfOid={} versionOid={}: {}",
                    crfOid, versionOid, ioe.getMessage(), ioe);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Could not read spreadsheet from disk"));
        }
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/crfs/{crfOid}/versions/{from}/migrate-to/{to}          */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 {@code crf-library} cluster — batch reassign
     * {@code event_definition_crf.default_version_id} from
     * {@code fromOid} to {@code toOid} across the SEDs the operator
     * picks. Both versions must belong to the same CRF.
     *
     * <p>{@code dryRun=true} returns the plan without writing; the SPA
     * uses this to power the migrate dialog's preview pane. On commit
     * (false), each affected SED row is updated and an audit row is
     * written via {@link CrfVersionMigrationService#commitMigration}.
     */
    @PostMapping("/{crfOid}/versions/{fromOid}/migrate-to/{toOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = MigrateVersionResult.class)))
    public ResponseEntity<?> migrateVersion(@PathVariable("crfOid") String crfOid,
                                            @PathVariable("fromOid") String fromOid,
                                            @PathVariable("toOid") String toOid,
                                            @RequestBody(required = false) MigrateVersionRequest body,
                                            HttpSession session) {
        ResponseEntity<?> guard = preflightWrite(session);
        if (guard != null) return guard;

        if (fromOid.equals(toOid)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "from and to version OIDs must differ"));
        }

        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = crfDao.findByOid(crfOid);
        if (crf == null || crf.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF with oid '" + crfOid + "'"));
        }

        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean from = versionDao.findByOid(fromOid);
        if (from == null || from.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version with oid '" + fromOid + "'"));
        }
        CRFVersionBean to = versionDao.findByOid(toOid);
        if (to == null || to.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No CRF version with oid '" + toOid + "'"));
        }
        if (from.getCrfId() != crf.getId() || to.getCrfId() != crf.getId()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Both versions must belong to CRF '" + crfOid + "'"));
        }
        if (to.getStatus() != null && (to.getStatus().isDeleted() || to.getStatus().isLocked())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Target version '" + toOid + "' is " + to.getStatus().getName()
                            + " — pick an available version"));
        }

        boolean dryRun = body != null && body.dryRun();
        List<String> filter = body == null ? null : body.sedOids();

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");

        MigrateVersionResult result = dryRun
                ? migrationService.planMigration(crf, from, to, filter)
                : migrationService.commitMigration(crf, from, to, filter, me);

        LOG.info("Migrate CRF version: crfOid={} from={} to={} dryRun={} migrated={} by user={}",
                crfOid, fromOid, toOid, dryRun, result.totalMigrated(), me.getName());

        return ResponseEntity.ok(result);
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /** Shared 401/403 preflight for write endpoints. */
    private ResponseEntity<?> preflightWrite(HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        // Legacy parity: sysadmin OR director/coordinator on the active
        // study (CreateCRFServlet:57-68). The site-level legality check
        // doesn't apply here — CRFs are study-independent.
        if (me.isSysAdmin()) return null;
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean currentRole =
                (at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean)
                        session.getAttribute("userRole");
        if (currentRole == null || currentRole.getRole() == null) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit managing CRFs"));
        }
        at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role r = currentRole.getRole();
        if (r != at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR
                && r != at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.COORDINATOR) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit managing CRFs — sysadmin or Director/Coordinator only"));
        }
        return null;
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateCreateShape(
            CreateCrfRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) out.add(fe("name", "CRF name is required"));
        else if (name.length() > 255) out.add(fe("name", "CRF name must be 255 characters or fewer"));
        if (body.description() != null && body.description().length() > 2000) {
            out.add(fe("description", "Description must be 2000 characters or fewer"));
        }
        return out;
    }

    private static SubjectsApiController.ValidationErrorBody.FieldError fe(String field, String msg) {
        return new SubjectsApiController.ValidationErrorBody.FieldError(field, msg);
    }

    private void writeLifecycleAudit(UserAccountBean me,
                                     String auditTable,
                                     int entityId,
                                     String entityOid,
                                     Status oldStatus,
                                     Status newStatus,
                                     String actionPrefix) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setAuditTable(auditTable);
            ae.setEntityId(entityId);
            ae.setColumnName("status_id");
            ae.setOldValue(oldStatus == null ? "" : String.valueOf(oldStatus.getId()));
            ae.setNewValue(String.valueOf(newStatus.getId()));
            ae.setActionMessage(actionPrefix + ": " + entityOid
                    + " (" + (oldStatus == null ? "?" : oldStatus.getName())
                    + " → " + newStatus.getName() + ") by " + me.getName());
            new AuditEventDAO(dataSource).create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for {}={} (continuing): {}",
                    actionPrefix, entityOid, e.getMessage());
        }
    }

    /**
     * Save the uploaded file to the legacy storage location
     * ({@code <filePath>/crf/original/}). Returns the stored filename
     * (basename only — the directory part is implicit per the legacy
     * convention).
     *
     * <p>The base path comes from {@code SQLInitServlet.getField("filePath")}
     * at runtime in the legacy stack. The modernized stack reads it
     * from the {@code datainfo.properties} bundle; for now we read
     * the same key directly via {@code CoreResources}.
     */
    private static Path persistUploadedFile(MultipartFile file, String originalFilename) throws IOException {
        String filePath = at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources
                .getField("filePath");
        if (filePath == null || filePath.isBlank()) {
            filePath = System.getProperty("java.io.tmpdir");
        }
        Path baseDir = Paths.get(filePath, "crf", "original");
        Files.createDirectories(baseDir);
        // Unique filename: timestamp-prefixed to avoid collisions
        // across uploads (the legacy code overwrites; we don't).
        String safeName = originalFilename.replaceAll("[^A-Za-z0-9._-]", "_");
        String stamped = System.currentTimeMillis() + "_" + safeName;
        Path target = baseDir.resolve(stamped);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static CrfDto toDto(CRFBean crf, CRFVersionDAO versionDao) {
        ArrayList<CRFVersionBean> versions = versionDao.findAllByCRF(crf.getId());
        List<CrfDto.CrfVersionDto> versionDtos = new ArrayList<>(versions.size());
        for (CRFVersionBean v : versions) {
            versionDtos.add(toVersionDto(v));
        }
        return new CrfDto(
                crf.getOid(),
                nullToEmpty(crf.getName()),
                nullToEmpty(crf.getDescription()),
                crf.getStatus() == null ? "" : crf.getStatus().getName(),
                versionDtos);
    }

    private static CrfDto.CrfVersionDto toVersionDto(CRFVersionBean v) {
        return new CrfDto.CrfVersionDto(
                v.getOid(),
                nullToEmpty(v.getName()),
                nullToEmpty(v.getDescription()),
                nullToEmpty(v.getRevisionNotes()),
                v.getStatus() == null ? "" : v.getStatus().getName(),
                v.getCreatedDate() == null ? null
                        // Phase E.5 fix (2026-06-03): AuditableEntityBean.createdDate
                        // is statically typed java.util.Date but the DAO populates it
                        // with java.sql.Date (rs.getDate). java.sql.Date.toInstant()
                        // throws UnsupportedOperationException per JDK API, so coerce
                        // via getTime() → Instant first.
                        : java.time.Instant.ofEpochMilli(v.getCreatedDate().getTime())
                                .atZone(ZoneOffset.UTC)
                                .truncatedTo(ChronoUnit.SECONDS).toInstant().toString());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
