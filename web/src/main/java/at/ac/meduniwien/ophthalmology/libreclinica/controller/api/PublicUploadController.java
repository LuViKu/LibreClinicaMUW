/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import io.swagger.v3.oas.annotations.tags.Tag;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.FileKindSniffer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * DR-029 — one public front door for every file a device exports.
 *
 * <p>There were two: an OCT page that took {@code .e2e} and nothing else, and
 * an image page that took JPEG/PNG and nothing else. A Clarus or PlexElite
 * export — DICOM — fitted neither, and an operator standing at a device had
 * to know which page a file belonged to before they could hand it in. This
 * page takes any of them, reads what the file is off its bytes, and sends it
 * down the route that kind already has:
 *
 * <ul>
 *   <li>{@code .e2e} → the OCT route, unchanged — per-scan rows, retinal jobs,
 *       the async pipeline. Delegated to {@link PublicOctUploadController}
 *       rather than moved, so what that route does is exactly what it did.</li>
 *   <li>DICOM → stored, pseudonymised and described by the sidecar, filed.</li>
 *   <li>JPEG/PNG → stored and filed.</li>
 * </ul>
 *
 * <p>Same posture as the pages it replaces: no account, {@code permitAll} in
 * SecurityConfig, the institutional reverse proxy is the only access gate
 * (must NOT be exposed to the public internet), throttled per client by
 * {@code PublicOctUploadRateLimitFilter}. Operator-typed strings are never
 * logged. The two older pages stay mounted for one release and redirect.
 */
@RestController
@RequestMapping("/api/v1/public/upload")
@Tag(name = "Public upload",
     description = "One unauthenticated upload page for OCT (.e2e), DICOM and JPEG/PNG exports (DR-029).")
public class PublicUploadController {

    // Method names double as OpenAPI operationIds (springdoc). They are
    // deliberately distinct from the older controllers' so adding this one
    // does not renumber the ids the SPA's api.ts already carries.

    private static final Logger LOG = LoggerFactory.getLogger(PublicUploadController.class);

    private final DataSource dataSource;
    private final StudySubjectFinder studySubjectFinder;
    private final PublicOctUploadController oct;
    private final PublicImageUploadController images;
    private final IngestUploadService uploads;

    @Autowired
    public PublicUploadController(@Qualifier("dataSource") DataSource dataSource,
                                  StudySubjectFinder studySubjectFinder,
                                  PublicOctUploadController oct,
                                  PublicImageUploadController images) {
        this(dataSource, studySubjectFinder, oct, images, new IngestUploadService(dataSource));
    }

    PublicUploadController(DataSource dataSource, StudySubjectFinder studySubjectFinder,
                           PublicOctUploadController oct, PublicImageUploadController images,
                           IngestUploadService uploads) {
        this.dataSource = dataSource;
        this.studySubjectFinder = studySubjectFinder;
        this.oct = oct;
        this.images = images;
        this.uploads = uploads;
    }

    /* ---------------- identification: the same lookups the two pages had ---------------- */

    /** Label-prefix subject lookup — see {@link PublicSubjectSearch} for the narrowing. */
    @GetMapping(value = "/patients/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> searchPatientsForUpload(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return PublicSubjectSearch.search(studySubjectFinder, q, limit,
                StudyScopeConfig.studyIdsFor(dataSource, StudyScopeConfig.PORTAL_KEY));
    }

    /** A subject's visits, for the picker. */
    @GetMapping(value = "/patients/{studySubjectId:[0-9]+}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listPatientEventsForUpload(@PathVariable("studySubjectId") int studySubjectId) {
        return oct.listPatientEventsPublic(studySubjectId);
    }

    /** Today's scheduled visits — off until the institution enables the list (P1-4 checkpoint C4). */
    @GetMapping(value = "/visits", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadVisits(@RequestParam(value = "date", required = false) String date) {
        return images.visits(date);
    }

    /** Per-row resolution: {@code {scans:[{patientId, scanDate, laterality}]}}, the OCT page's shape. */
    @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolveUpload(@RequestBody PublicOctUploadController.ResolveRequest request) {
        return oct.resolve(request);
    }

    /**
     * Is this file already here? Answered before the bytes travel.
     *
     * @param scanIndex the volume inside a multi-acquisition .e2e; omit for a
     *                  file that is one acquisition
     */
    @GetMapping(value = "/preflight", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preflightUpload(@RequestParam("sha256") String sha256,
                                       @RequestParam(value = "scanIndex", required = false) Integer scanIndex) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            return ResponseEntity.badRequest().body(Map.of("message", "sha256 must be 64 hex characters"));
        }
        return ResponseEntity.ok(uploads.preflight(sha256, scanIndex));
    }

    /* ---------------- the upload ---------------- */

    /**
     * One file of any supported kind.
     *
     * <p>The kind is read off the bytes; the browser's content type and the
     * file's name do not decide. Parameters the OCT route needs ({@code
     * scanIndex}, {@code park}, {@code eventCrfId}) are passed through to it
     * and ignored by the others; {@code studyDate} is accepted as an alias of
     * {@code scanDate} because the image page called it that.
     */
    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> commitUpload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "patientId", required = false) String patientId,
            @RequestParam(value = "scanDate", required = false) String scanDate,
            @RequestParam(value = "studyDate", required = false) String studyDate,
            @RequestParam(value = "laterality", required = false) String laterality,
            @RequestParam(value = "scanIndex", defaultValue = "0") int scanIndex,
            @RequestParam(value = "eventCrfId", required = false) Integer eventCrfId,
            @RequestParam(value = "studyEventId", required = false) Integer studyEventId,
            @RequestParam(value = "park", defaultValue = "false") boolean park,
            @RequestParam(value = "device", required = false) String device,
            @RequestParam(value = "disambiguated", defaultValue = "false") boolean disambiguated,
            @RequestParam(value = "candidateCount", defaultValue = "0") int candidateCount) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file is required"));
        }
        FileKindSniffer.Sniffed sniffed = UploadRoute.sniff(file);
        if (sniffed == null) {
            return ResponseEntity.badRequest().body(Map.of("message", UploadRoute.UNSUPPORTED_MESSAGE));
        }
        String date = UploadRoute.isBlank(scanDate) ? studyDate : scanDate;

        if (sniffed.kind() == IngestArtifactStore.Kind.E2E) {
            String pid = patientId;
            if (UploadRoute.isBlank(pid) && studyEventId != null) {
                pid = UploadRoute.subjectLabelForStudyEvent(dataSource, studyEventId);
            }
            if (UploadRoute.isBlank(pid) && eventCrfId != null) {
                Integer se = UploadRoute.studyEventIdForEventCrf(dataSource, eventCrfId);
                if (se != null) pid = UploadRoute.subjectLabelForStudyEvent(dataSource, se);
            }
            return UploadRoute.asE2e(oct.commit(file, pid == null ? "" : pid, date == null ? "" : date,
                    laterality == null ? "" : laterality, scanIndex, eventCrfId, studyEventId, park,
                    disambiguated, candidateCount));
        }

        // The image and DICOM routes file against a visit; an open CRF the
        // picker handed over names its visit.
        Integer visit = studyEventId;
        if (visit == null && eventCrfId != null) {
            visit = UploadRoute.studyEventIdForEventCrf(dataSource, eventCrfId);
            if (visit == null) {
                return ResponseEntity.status(404).body(Map.of("message", "No event_crf with id " + eventCrfId));
            }
        }
        IngestUploadService.Outcome outcome = uploads.commit(new IngestUploadService.Upload(
                sniffed, file, patientId, UploadRoute.parseIsoDateOrNull(date), laterality, visit, device,
                IngestUploadService.Channel.PORTAL, IngestBindService.Actor.system(),
                StudyScopeConfig.studyIdsFor(dataSource, StudyScopeConfig.PORTAL_KEY)));
        if (outcome instanceof IngestUploadService.Created c) {
            LOG.info("public upload: ingest_item {} ({}) {}", c.ingestItemId(), c.format(), c.status());
        }
        return UploadRoute.respond(outcome);
    }

    /* ---------------- undo ---------------- */

    /** Take back an image or DICOM upload within the window — the OCT route's {@code DELETE /jobs/{id}} covers scans. */
    @DeleteMapping(value = "/items/{ingestItemId:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> undoUploadItem(@PathVariable("ingestItemId") long ingestItemId) {
        return UploadRoute.respond(uploads.undo(ingestItemId, IngestBindService.Actor.system()));
    }

    /** Take back an OCT scan's job within the window. */
    @DeleteMapping(value = "/jobs/{jobId:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> undoUploadJob(@PathVariable("jobId") long jobId) {
        return oct.undo(jobId);
    }
}
