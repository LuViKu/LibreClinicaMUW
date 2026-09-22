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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.FileKindSniffer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.PerformedItemAutoTicker;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * DR-029 — what happens to an uploaded image or DICOM file, whichever door it
 * came through.
 *
 * <p>The public page and the staff page take the same file and want the same
 * things done with it: stored under the unified root, hashed, refused if it
 * is already there, filed against the visit the operator picked, the visit's
 * checklist ticked, an audit row left. What differs is who is asking — an
 * unauthenticated form, whose visit pick is trusted only when the visit is
 * live on the date given, or a session, whose reach is its site visibility
 * — and that difference is one parameter here rather than two
 * implementations that drift.
 *
 * <p>A DICOM file takes one extra step: before its row is written, the
 * sidecar rewrites its patient identity (see {@link DicomDescribeClient}). If
 * that step cannot happen the file is not kept, because a clinic camera's
 * export carries the hospital's patient and the platform must never hold it.
 *
 * <p>OCT volumes are not handled here. Their route — per-scan rows, the
 * retinal jobs, the async pipeline — lives in {@link PublicOctUploadController}
 * and is delegated to unchanged; this class covers what that route does not.
 */
final class IngestUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestUploadService.class);

    /** A fundus JPEG is a few megabytes; a widefield DICOM export, tens. */
    static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    static final long MAX_DICOM_BYTES = 200L * 1024 * 1024;

    /** Same window the OCT page has always given an operator to take an upload back. */
    static final Duration UNDO_WINDOW = Duration.ofSeconds(60);

    /** {@code ingest_item.source_kind} for anything that came in through a browser. */
    static final String SOURCE_KIND = "upload";

    /** Which door the file came through. */
    enum Channel {
        /** The unauthenticated page: a visit pick is honoured only for a visit live on the date given. */
        PORTAL,
        /** A logged-in operator: any live visit inside their site visibility. */
        STAFF
    }

    /**
     * One file and what the operator said about it.
     *
     * @param studyScope the studies this caller may file into — the portal's
     *                   configured scope (null = every study) or the session's
     *                   visible studies. A visit outside it is refused.
     */
    record Upload(FileKindSniffer.Sniffed sniffed, MultipartFile file, String patientId,
                  LocalDate acquisitionDate, String laterality, Integer studyEventId, String device,
                  Channel channel, IngestBindService.Actor actor, Set<Integer> studyScope) {}

    sealed interface Outcome permits Created, Duplicate, Rejected, Undone {}

    record Created(long ingestItemId, String kind, String format, String status, String laterality,
                   LocalDate acquisitionDate, String device, Integer imagingModalityId,
                   boolean deidentified) implements Outcome {}

    record Duplicate(long existingIngestItemId, Long existingJobId) implements Outcome {}

    record Rejected(int status, String message) implements Outcome {}

    record Undone(long ingestItemId) implements Outcome {}

    /** The visit a file is filed against, with what the row and the sidecar need to know about it. */
    private record Target(int studySubjectId, int studyEventId, Integer eventCrfId,
                          int studyId, String subjectLabel) {}

    private final DataSource dataSource;
    private final IngestArtifactStore store;
    private final DicomDescribeClient describe;
    private final StudySettingService settings;

    IngestUploadService(DataSource dataSource) {
        this(dataSource, new IngestArtifactStore(), new DicomDescribeClient());
    }

    IngestUploadService(DataSource dataSource, IngestArtifactStore store, DicomDescribeClient describe) {
        this.dataSource = dataSource;
        this.store = store;
        this.describe = describe;
        this.settings = new StudySettingService(dataSource);
    }

    /* ------------------------------------------------------------------ */
    /* commit                                                              */
    /* ------------------------------------------------------------------ */

    Outcome commit(Upload up) {
        IngestArtifactStore.Kind kind = up.sniffed().kind();
        if (kind != IngestArtifactStore.Kind.IMAGE && kind != IngestArtifactStore.Kind.DICOM) {
            return new Rejected(400, "only image and DICOM files take this route");
        }
        long max = kind == IngestArtifactStore.Kind.DICOM ? MAX_DICOM_BYTES : MAX_IMAGE_BYTES;
        if (up.file().getSize() > max) {
            return new Rejected(413, "the file exceeds the " + (max / (1024 * 1024)) + " MB limit");
        }

        // The stored name carries the sniffed extension, never the operator's:
        // a PNG called photo.jpg is stored as a PNG.
        IngestArtifactStore.Stored stored;
        try (InputStream in = up.file().getInputStream()) {
            stored = store.store(kind, in, "upload" + up.sniffed().extension());
        } catch (IOException e) {
            LOG.error("upload: could not store the file: {}", e.getMessage());
            return new Rejected(500, "could not store the file");
        }
        Path path = stored.path();
        String preview = null;

        try {
            Duplicate dup = findDuplicateBySha(stored.sha256());
            if (dup != null) return discard(path, null, dup);

            // The visit first: the label written into a DICOM file's patient
            // identity is the visit's subject and nothing else — an upload
            // nobody has filed yet is blanked, not labelled with a guess.
            Target target = null;
            if (up.studyEventId() != null) {
                target = resolveTarget(up);
                if (target == null) {
                    return discard(path, null, new Rejected(
                            up.channel() == Channel.STAFF ? 404 : 400,
                            up.channel() == Channel.STAFF
                                    ? "that visit does not exist or is no longer live"
                                    : "that visit is not scheduled for the submitted date"));
                }
                if (up.studyScope() != null && !up.studyScope().contains(target.studyId())) {
                    // Same wording as an unknown visit for the portal: an
                    // unauthenticated caller learns nothing from the difference.
                    return discard(path, null, new Rejected(
                            up.channel() == Channel.STAFF ? 403 : 400,
                            up.channel() == Channel.STAFF
                                    ? "the chosen visit belongs to a study you cannot access"
                                    : "that visit is not scheduled for the submitted date"));
                }
                String gate = kind == IngestArtifactStore.Kind.DICOM
                        ? StudySettingService.INGEST_DICOM_ENABLED
                        : StudySettingService.INGEST_IMAGE_ENABLED;
                // The legacy portal-scope key is the unauthenticated page's
                // business; a logged-in operator's reach is their site
                // visibility, checked just above, so behind a login only an
                // explicit per-study setting can refuse.
                String legacyScope = up.channel() == Channel.PORTAL ? StudyScopeConfig.PORTAL_KEY : null;
                if (!settings.ingressEnabled(target.studyId(), gate, legacyScope)) {
                    // The portal scope already folds ingest.image.enabled in
                    // (StudyScopeConfig), so an image reaches this only on the
                    // staff route; a DICOM file can reach it on either. The
                    // public page answers as discreetly as it does for a visit
                    // outside its scope — a form with no login does not get to
                    // learn which studies exist and what they refuse.
                    return discard(path, null, new Rejected(
                            up.channel() == Channel.STAFF ? 403 : 400,
                            up.channel() == Channel.STAFF
                                    ? "this study does not accept " + up.sniffed().format() + " uploads"
                                    : "that visit is not scheduled for the submitted date"));
                }
            }

            DicomDescribeClient.Description desc = null;
            if (kind == IngestArtifactStore.Kind.DICOM) {
                try {
                    desc = describe.describe(path, target == null ? null : target.subjectLabel());
                } catch (DicomDescribeClient.DescribeException e) {
                    return discard(path, null, rejectionFor(e));
                }
                preview = desc.previewPngPath();
                if (desc.sopInstanceUid() != null) {
                    Long existing = findBySopInstanceUid(desc.sopInstanceUid());
                    if (existing != null) return discard(path, preview, new Duplicate(existing, null));
                }
            }

            String device = deviceFor(up, desc);
            String laterality = desc != null && desc.laterality() != null
                    ? desc.laterality() : normaliseLaterality(up.laterality());
            // 2026-09-22 — a date read out of the file and a date typed by the
            // operator are not the same claim, and this column used to hold
            // both under one name. On the staff workbench the typed value is
            // the day the operator searched visits by, so it agrees with the
            // chosen visit no matter what the file says; stored bare, it reads
            // as corroboration. Record which kind it is.
            //
            // Only DICOM yields a file date here — `desc` is null for .e2e and
            // images, so an .e2e starts out operator-dated (or undated) and is
            // upgraded to source='file' once /preprocess reads the exam chunk
            // (PublicOctUploadController.persistAcquisitionDate).
            LocalDate fromFile = desc != null ? desc.acquisitionDate() : null;
            LocalDate acquisition = fromFile != null ? fromFile : up.acquisitionDate();
            String acquisitionSource = fromFile != null
                    ? IngestItemRepository.ACQ_SOURCE_FILE
                    : (up.acquisitionDate() != null ? IngestItemRepository.ACQ_SOURCE_OPERATOR : null);
            Integer modalityId = target == null ? null : suggestModality(target.studyId(), device, kind);
            String policy = up.channel() == Channel.PORTAL
                    ? IngestBindService.POLICY_PORTAL : IngestBindService.POLICY_VISIT_PICKED;

            long id;
            try (Connection c = dataSource.getConnection()) {
                var item = IngestItemRepository
                        .newItem(kind, SOURCE_KIND, path.toString())
                        .device(device)
                        .previewPngPath(kind == IngestArtifactStore.Kind.IMAGE ? path.toString() : preview)
                        .originalFilename(up.file().getOriginalFilename())
                        .contentType(up.sniffed().contentType())
                        .digest(stored.sha256(), stored.byteSize())
                        .patientId(blankToNull(up.patientId()))
                        .laterality(laterality)
                        .acquisitionDate(acquisition)
                        .acquisitionDateSource(acquisitionSource)
                        .imagingModalityId(modalityId);
                if (desc != null) {
                    item.sopInstanceUid(desc.sopInstanceUid())
                            .sopClassUid(desc.sopClassUid())
                            .studyInstanceUid(desc.studyInstanceUid())
                            .seriesInstanceUid(desc.seriesInstanceUid())
                            .modality(desc.modality())
                            .deidentifiedAt(desc.identityRemoved() ? Instant.now() : null);
                }
                if (target != null) {
                    item.boundTo(target.studySubjectId(), target.studyEventId(), target.eventCrfId(),
                            policy, up.actor().userId());
                }
                id = item.insert(c);
            }

            if (target != null) {
                writeBindAudit(id, target, policy, up.actor());
                // The file on the visit is the evidence that this device was
                // used on it; the checklist follows from the bind.
                ImageIngestBinding.tickPerformed(dataSource, id,
                        new ImageIngestBinding.EventTarget(target.studySubjectId(), target.studyEventId(),
                                target.eventCrfId()),
                        SOURCE_KIND, device, laterality, up.actor().userId());
            }
            LOG.info("upload: ingest_item {} ({}) landed {} via {}", id, up.sniffed().format(),
                    target == null ? "UNBOUND" : "BOUND", up.channel());
            return new Created(id, kind.dir(), up.sniffed().format(),
                    target == null ? "UNBOUND" : "BOUND", laterality, acquisition, device, modalityId,
                    desc != null && desc.identityRemoved());
        } catch (SQLException e) {
            // The race-safe dedup index fires here when two operators upload
            // the same bytes at once; the earlier row wins.
            if ("23505".equals(e.getSQLState())) {
                Duplicate raced = findDuplicateBySha(stored.sha256());
                return discard(path, preview, raced != null ? raced : new Rejected(409, "already uploaded"));
            }
            LOG.error("upload: INSERT failed: {}", e.getMessage());
            return discard(path, preview, new Rejected(500, "the upload could not be recorded"));
        }
    }

    /* ------------------------------------------------------------------ */
    /* undo                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Take an upload back within the window.
     *
     * <p>Only what this service created can be undone here: a row somebody
     * has since reconciled by hand, dismissed, or that carries inference
     * jobs is somebody's decision, and the OCT route owns the job form.
     */
    Outcome undo(long ingestItemId, IngestBindService.Actor actor) {
        Instant receivedAt;
        String storedPath;
        String previewPath;
        String status;
        String policy;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT received_at, stored_path, preview_png_path, status, match_policy, "
                             + "  (SELECT count(*) FROM retinal_inference_job j "
                             + "    WHERE j.ingest_item_id = ii.ingest_item_id) AS jobs "
                             + "  FROM ingest_item ii WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Rejected(404, "no upload " + ingestItemId);
                Timestamp ts = rs.getTimestamp("received_at");
                receivedAt = ts == null ? Instant.EPOCH : ts.toInstant();
                storedPath = rs.getString("stored_path");
                previewPath = rs.getString("preview_png_path");
                status = rs.getString("status");
                policy = rs.getString("match_policy");
                if (rs.getInt("jobs") > 0) {
                    return new Rejected(409, "this upload already has work attached to it");
                }
            }
        } catch (SQLException e) {
            LOG.error("undo lookup failed for ingest_item {}: {}", ingestItemId, e.getMessage());
            return new Rejected(500, "could not load the upload");
        }
        boolean boundAtUpload = "BOUND".equals(status)
                && (IngestBindService.POLICY_PORTAL.equals(policy)
                    || IngestBindService.POLICY_VISIT_PICKED.equals(policy));
        if (!"UNBOUND".equals(status) && !boundAtUpload) {
            return new Rejected(409, "this upload has already been reconciled");
        }
        if (Duration.between(receivedAt, Instant.now()).compareTo(UNDO_WINDOW) > 0) {
            return new Rejected(410, "undo window of " + UNDO_WINDOW.toSeconds() + "s elapsed");
        }
        if (boundAtUpload) {
            // The CRF value first: a row that vanishes while the form still
            // asserts the modality was performed is the state to avoid.
            int actorId = actor.userId() != null ? actor.userId() : systemActorId();
            PerformedItemAutoTicker.ClearOutcome cleared =
                    new PerformedItemAutoTicker(dataSource).clearPerformed(ingestItemId, actorId);
            if (cleared == PerformedItemAutoTicker.ClearOutcome.FAILED) {
                return new Rejected(500, "could not take back the checklist entry");
            }
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ingest_item WHERE ingest_item_id = ? "
                             + "  AND NOT EXISTS (SELECT 1 FROM retinal_inference_job j "
                             + "                   WHERE j.ingest_item_id = ingest_item.ingest_item_id)")) {
            ps.setLong(1, ingestItemId);
            if (ps.executeUpdate() == 0) {
                return new Rejected(409, "this upload already has work attached to it");
            }
        } catch (SQLException e) {
            LOG.error("undo DELETE failed for ingest_item {}: {}", ingestItemId, e.getMessage());
            return new Rejected(500, "could not remove the upload");
        }
        deleteQuietly(storedPath);
        if (previewPath != null && !previewPath.equals(storedPath)) deleteQuietly(previewPath);
        LOG.info("upload: ingest_item {} taken back within the undo window", ingestItemId);
        return new Undone(ingestItemId);
    }

    /* ------------------------------------------------------------------ */
    /* preflight                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * What is already in the queue for these bytes, so the page can say so
     * before the bytes travel.
     *
     * @param scanIndex the volume inside a multi-acquisition .e2e, or null to
     *                  match a file that is one acquisition
     */
    Map<String, Object> preflight(String sha256, Integer scanIndex) {
        Map<String, Object> body = new LinkedHashMap<>();
        Duplicate d = scanIndex == null ? findDuplicateBySha(sha256) : findDuplicateByShaAndScan(sha256, scanIndex);
        body.put("exists", d != null);
        body.put("ingestItemId", d == null ? null : d.existingIngestItemId());
        body.put("jobId", d == null ? null : d.existingJobId());
        return body;
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private Target resolveTarget(Upload up) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            ImageIngestBinding.EventTarget t = up.channel() == Channel.PORTAL
                    ? ImageIngestBinding.resolveEventTargetForPortal(c, up.studyEventId(), up.acquisitionDate())
                    : ImageIngestBinding.resolveEventTarget(c, up.studyEventId());
            if (t == null) return null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT study_id, label FROM study_subject WHERE study_subject_id = ?")) {
                ps.setInt(1, t.studySubjectId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new Target(t.studySubjectId(), t.studyEventId(), t.eventCrfId(),
                            rs.getInt("study_id"), rs.getString("label"));
                }
            }
        }
    }

    /**
     * The catalogue entry this file belongs under, when the study has exactly
     * one for this device and kind. A guess between two would be filed as a
     * fact, so two is none.
     */
    private Integer suggestModality(int studyId, String device, IngestArtifactStore.Kind kind) {
        if (device == null) return null;
        String sql = "SELECT im.imaging_modality_id FROM imaging_modality im "
                + " WHERE im.study_id = (SELECT CASE WHEN COALESCE(s.parent_study_id, 0) > 0 "
                + "                                  THEN s.parent_study_id ELSE s.study_id END "
                + "                        FROM study s WHERE s.study_id = ?) "
                + "   AND COALESCE(im.status_id, 1) NOT IN (5, 7) "
                + "   AND lower(COALESCE(im.device, '')) = ? "
                + "   AND (',' || lower(COALESCE(im.kinds_accepted, '')) || ',') LIKE ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studyId);
            ps.setString(2, device);
            ps.setString(3, "%," + kind.dir() + ",%");
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int id = rs.getInt(1);
                return rs.next() ? null : id;
            }
        } catch (SQLException e) {
            LOG.warn("modality suggestion failed for study {}: {}", studyId, e.getMessage());
            return null;
        }
    }

    /**
     * The device key a file is filed under.
     *
     * <p>A DICOM file names its own device; the vendor's spelling is folded
     * onto the keys the imaging catalogue uses, so a "CLARUS 700" and a
     * "CLARUS 500" tick the same box. An image file cannot say, so the page's
     * device parameter decides, with the Remidio as the default the image
     * page always had.
     */
    static String deviceFor(Upload up, DicomDescribeClient.Description desc) {
        if (desc != null) {
            String key = deviceKeyFromModel(desc.manufacturerModelName(), desc.manufacturer());
            if (key != null) return key;
        }
        String raw = up.device();
        if (raw == null || raw.isBlank()) {
            return desc != null ? null : PublicImageUploadController.DEFAULT_DEVICE;
        }
        return PerformedItemAutoTicker.normaliseDeviceKey(raw.length() > 64 ? raw.substring(0, 64) : raw);
    }

    /** Vendor model names folded onto catalogue device keys; null when nothing is recognisable. */
    static String deviceKeyFromModel(String model, String manufacturer) {
        String m = compact((model == null ? "" : model) + " " + (manufacturer == null ? "" : manufacturer));
        if (m.isEmpty()) return null;
        if (m.contains("clarus")) return "clarus";
        if (m.contains("plexelite") || m.contains("plex")) return "plexelite";
        if (m.contains("cirrus")) return "cirrus";
        if (m.contains("lumo") || m.contains("optomed")) return "optomed";
        if (m.contains("spectralis") || m.contains("heidelberg")) return "spectralis";
        if (m.contains("remidio")) return "remidio";
        String justModel = compact(model == null ? "" : model);
        if (justModel.isEmpty()) return null;
        return justModel.length() > 64 ? justModel.substring(0, 64) : justModel;
    }

    private static String compact(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }

    private static Rejected rejectionFor(DicomDescribeClient.DescribeException e) {
        return switch (e.reason()) {
            case NOT_DICOM -> new Rejected(400, "the file is not a DICOM object");
            case UNCONFIGURED, UNREACHABLE -> new Rejected(503,
                    "DICOM uploads are not available right now — the DICOM service is not reachable");
            case REJECTED -> new Rejected(502, "the DICOM service could not process the file");
        };
    }

    private Duplicate findDuplicateBySha(String sha256) {
        if (sha256 == null || sha256.isBlank()) return null;
        return findDuplicate("SELECT ii.ingest_item_id, "
                + "  (SELECT MIN(j.job_id) FROM retinal_inference_job j "
                + "    WHERE j.ingest_item_id = ii.ingest_item_id) AS job_id "
                + "  FROM ingest_item ii WHERE ii.sha256 = ? ORDER BY ii.ingest_item_id LIMIT 1",
                ps -> ps.setString(1, sha256));
    }

    private Duplicate findDuplicateByShaAndScan(String sha256, int scanIndex) {
        if (sha256 == null || sha256.isBlank()) return null;
        return findDuplicate("SELECT ii.ingest_item_id, "
                + "  (SELECT MIN(j.job_id) FROM retinal_inference_job j "
                + "    WHERE j.ingest_item_id = ii.ingest_item_id) AS job_id "
                + "  FROM ingest_item ii WHERE ii.sha256 = ? AND COALESCE(ii.scan_index, -1) = ? LIMIT 1",
                ps -> {
                    ps.setString(1, sha256);
                    ps.setInt(2, scanIndex);
                });
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Duplicate findDuplicate(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long job = rs.getLong(2);
                return new Duplicate(rs.getLong(1), rs.wasNull() ? null : job);
            }
        } catch (SQLException e) {
            LOG.warn("duplicate lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private Long findBySopInstanceUid(String sop) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id FROM ingest_item WHERE sop_instance_uid = ? LIMIT 1")) {
            ps.setString(1, sop);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            LOG.warn("SOP UID lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private void writeBindAudit(long id, Target target, String policy, IngestBindService.Actor actor) {
        if (actor.isSystem()) {
            ImageIngestBinding.writeSystemBindAudit(dataSource, id, policy, target.studyEventId());
            return;
        }
        try {
            EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), AuditTypeIds.IMAGE_BIND,
                    actor.user(), actor.study(), null, "uploaded file filed against a visit",
                    "ingest_item", (int) id, "status", "UNBOUND",
                    "BOUND;match_policy=" + policy + ";study_event_id=" + target.studyEventId());
        } catch (RuntimeException e) {
            LOG.warn("could not audit the bind of ingest_item {}: {}", id, e.getMessage());
        }
    }

    private int systemActorId() {
        Integer id = PerformedItemAutoTicker.systemUserId(dataSource);
        return id == null ? 0 : id;
    }

    private static Outcome discard(Path stored, String preview, Outcome outcome) {
        deleteQuietly(stored == null ? null : stored.toString());
        if (preview != null) deleteQuietly(preview);
        return outcome;
    }

    private static void deleteQuietly(String path) {
        if (path == null || path.isBlank()) return;
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException | RuntimeException ignored) {
            // Best-effort cleanup; an orphan file is the retention sweep's problem.
        }
    }

    private static String normaliseLaterality(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String up = raw.trim().toUpperCase(Locale.ROOT);
        return switch (up) {
            case "OD", "OS", "OU" -> up;
            default -> null;
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
