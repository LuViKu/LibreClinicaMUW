/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.File;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @Autowired
    public CrfsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
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

    @PostMapping(value = "/{crfOid}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = CrfDto.CrfVersionDto.class)))
    public ResponseEntity<?> uploadVersion(
            @PathVariable("crfOid") String crfOid,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "versionName") String versionName,
            @RequestPart(value = "versionDescription", required = false) String versionDescription,
            @RequestPart(value = "revisionNotes", required = false) String revisionNotes,
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

        // Persist file to disk under <crfStoragePath>/crf/original/ —
        // mirrors CreateCRFVersionServlet:125. The "new" subdirectory
        // is used by the legacy parser path; for now we only write the
        // original so the file is preserved for the future parser.
        String storedFilename;
        try {
            storedFilename = persistUploadedFile(file, originalFilename);
        } catch (IOException ioe) {
            LOG.warn("Failed to persist CRF upload for crfOid={} (name={})",
                    crfOid, originalFilename, ioe);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to store uploaded file"));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");

        CRFVersionBean toCreate = new CRFVersionBean();
        toCreate.setCrfId(crf.getId());
        toCreate.setName(versionNameTrim);
        toCreate.setDescription(versionDescription == null ? "" : versionDescription.trim());
        toCreate.setRevisionNotes(revisionNotes == null ? "" : revisionNotes.trim());
        toCreate.setStatus(Status.AVAILABLE);
        toCreate.setOwner(me);
        toCreate.setXform("");        // see class javadoc — parser deferred
        toCreate.setXformName(storedFilename);
        toCreate.setCreatedDate(new java.util.Date());

        CRFVersionBean persisted = versionDao.create(toCreate);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("CRFVersionDAO.create returned no row for crfOid={} versionName={}",
                    crfOid, versionNameTrim);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist CRF version"));
        }

        LOG.info("Upload CRF version: crfOid={} versionOid={} versionName={} file={} by user={}",
                crfOid, persisted.getOid(), versionNameTrim, storedFilename, me.getName());

        return ResponseEntity.status(201).body(toVersionDto(persisted));
    }

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
    private static String persistUploadedFile(MultipartFile file, String originalFilename) throws IOException {
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
        return stamped;
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
                        : v.getCreatedDate().toInstant().atZone(ZoneOffset.UTC)
                                .truncatedTo(ChronoUnit.SECONDS).toInstant().toString());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
