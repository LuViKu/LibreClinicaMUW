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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.E2eSourceRetentionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestResolutionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.OctVolumes;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.E2eSidecarClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;

/**
 * DR-041 (amendment 2026-10-13 b) - one upload of a multimodal Spectralis {@code .e2e}, converted on
 * the server into one DICOM per series, each an ordinary DICOM {@code ingest_item}.
 *
 * <p>The file is streamed to the E2E upload directory once (digest on the way), and the converter
 * sidecar lists its series ({@link E2eSidecarClient#describe}). The client may leave series out
 * ({@code excludeSeries}): every key is checked against that listing, an unknown key or an
 * exclusion of everything is a 400 and nothing is stored. The kept series are converted by the
 * sidecar in one pass ({@link E2eSidecarClient#convert}); each resulting DICOM is stored in the
 * DICOM ingest store, described and pseudonymised by the DICOM sidecar like any uploaded DICOM, and
 * filed as a row of kind {@code dicom} that keeps {@code e2e_series_key}, {@code series_modality},
 * {@code series_frames} and {@code e2e_source_sha256}. OCT volumes get their analyses through the
 * DICOM OCT path ({@link RetinalJobFollower#ensure}, DR-039), exactly as a DICOM OCT upload does.
 *
 * <p>The original {@code .e2e} is not kept in the upload directory: after the rows are committed it
 * moves to the time-limited source area ({@link E2eSourceRetentionService}), and a nightly job
 * deletes it after {@code core.retinalInference.e2eSourceRetentionDays}. The converted temp
 * directory is removed after the import. Duplicate detection and the preflight use
 * {@code e2e_source_sha256}, so they work after the source file is gone.
 *
 * <p>The subject is the study label in the E2E patient chunk's surname slot ({@code headerLabel} of
 * the sidecar's answer). The optional {@code patientId} is the folder the client found the file in:
 * when both exist they must agree, and when the file carries no usable label the folder is the
 * label. The first-name and patient-id slots are never read, so they cannot reach this class. The
 * DICOMs carry the matched subject's label as their only identity, or none while parked.
 *
 * <p>Binding: a header label that differs from the folder, an explicit {@code park}, no label at
 * all, or a label that resolves to no single visible subject parks every item. Otherwise
 * {@code studyEventId} binds every item to that visit, and without one each series resolves its own
 * visit from label and date; a series with no visit on its date is parked on its own.
 *
 * <p>A label read from the file is stored, audited and logged only when it resolves to exactly one
 * visible subject (then as that subject's own label). An unmatched header label is dropped: it can
 * be a clinic number or another person's identifier.
 */
final class E2eSplitUpload {

    private static final Logger LOG = LoggerFactory.getLogger(E2eSplitUpload.class);

    static final String MESSAGE_DUPLICATE = "Diese .e2e-Datei wurde bereits hochgeladen.";

    /** {@code ingest_item.source_kind} of the split items (the OCT upload route's). */
    static final String SOURCE_KIND = "portal-oct";

    private static final Pattern SERIES_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,32}");
    private static final Pattern CONVERTED_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,40}\\.dcm");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Set<String> HEADER_STATES = Set.of("none", "empty", "matches", "differs");
    /** A study label as the converter vets it: printable ASCII, no space, 1-24 characters. */
    private static final Pattern LABEL_TOKEN = Pattern.compile("[\\x21-\\x7E]{1,24}");
    private static final Set<String> SERIES_KINDS =
            Set.of("OCT", "OCT_LINE", "IR", "BAF", "BR", "IRAF", "QAF", "ICGA", "FA", "OTHER");
    private static final int MAX_EXCLUDED = 2000;

    private final DataSource dataSource;
    private final PublicOctUploadController oct;
    private final E2eSidecarClient sidecar;
    private final DicomDescribeClient dicom;
    private final IngestArtifactStore store;
    private final RetinalJobFollower follower;

    E2eSplitUpload(DataSource dataSource, PublicOctUploadController oct, E2eSidecarClient sidecar,
                   DicomDescribeClient dicom, IngestArtifactStore store,
                   RemoteRetinalInferenceClient remoteClient, RetinalInferenceApiController inferenceController) {
        this.dataSource = dataSource;
        this.oct = oct;
        this.sidecar = sidecar;
        this.dicom = dicom;
        this.store = store;
        this.follower = new RetinalJobFollower(dataSource, remoteClient, inferenceController);
    }

    /* ------------------------------------------------------------------ */
    /* preflight                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * The items already made from an export with this digest, ids only. Items of the previous
     * layout (one stored file, digest in {@code sha256}) count too.
     */
    static List<Long> existingItems(DataSource dataSource, String sha256) {
        List<Long> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id FROM ingest_item "
                             + " WHERE e2e_source_sha256 = ? OR (sha256 = ? AND e2e_series_key IS NOT NULL) "
                             + " ORDER BY ingest_item_id")) {
            ps.setString(1, sha256);
            ps.setString(2, sha256);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            LOG.warn("split duplicate lookup failed: {}", e.getMessage());
        }
        return ids;
    }

    /**
     * Preflight for a split file: is it already here? Only items the caller may name are listed
     * ({@code visible} null = all); {@code exists} is true when any item exists, so a hidden
     * duplicate still stops the upload.
     */
    static E2eSplitPreflightResponse preflight(DataSource dataSource, String sha256, LongPredicate visible) {
        List<Long> all = existingItems(dataSource, sha256);
        return new E2eSplitPreflightResponse(!all.isEmpty(), true, visibleOnly(all, visible));
    }

    private static List<Long> visibleOnly(List<Long> ids, LongPredicate visible) {
        if (visible == null) return ids;
        List<Long> out = new ArrayList<>();
        for (long id : ids) if (visible.test(id)) out.add(id);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* commit                                                              */
    /* ------------------------------------------------------------------ */

    /** What one kept series turned into. */
    private record Planned(E2eSidecarClient.Series series, E2eSidecarClient.ConvertedFile converted,
                           IngestArtifactStore.Stored stored, DicomDescribeClient.Description desc,
                           String key, String kind, LocalDate date, String laterality, boolean park,
                           Integer studyEventId, Integer eventCrfId, Integer subjectId,
                           IngestTwins.Twin twin, Integer modalityId) {}

    /**
     * @param excludeSeries comma-separated seriesKeys to leave out (null or blank: none)
     * @param resolveScope  studies the label may resolve against, null for all
     * @param visible       which existing items a duplicate answer may name, null for all
     * @param source        the uploader's source for a tokened request, else null
     * @param actor         who the follow-up work (analyses) is audited as
     * @throws SplitUploadRejection for every outcome that is not the 201
     */
    E2eSplitCommitResponse commit(MultipartFile file, String patientId, Integer studyEventId, Integer eventCrfId,
                                  boolean park, String excludeSeries, Set<Integer> resolveScope,
                                  LongPredicate visible, IngestSourceService.Source source,
                                  IngestBindService.Actor actor) {
        if (file == null || file.isEmpty()) {
            throw SplitUploadRejection.of(400, "file part is required");
        }
        // The folder the file was found in; absent for a file in a shared watch folder.
        String folder = patientId == null || patientId.isBlank() ? null : patientId.trim();
        if (eventCrfId != null) {
            throw SplitUploadRejection.of(400, "eventCrfId is not supported with split=true; send studyEventId or park");
        }
        if (park && studyEventId != null) {
            throw SplitUploadRejection.of(400, "park and studyEventId are mutually exclusive");
        }
        Set<String> excluded = parseExcluded(excludeSeries);

        Set<Integer> atLocation = DeviceScope.atLocation(dataSource, source);
        Integer pickedSubject = null;
        if (studyEventId != null) {
            pickedSubject = oct.resolveStudySubjectFromStudyEvent(studyEventId);
            if (pickedSubject == null || !oct.subjectAtLocation(atLocation, pickedSubject)) {
                throw SplitUploadRejection.of(404, "No study_event with id " + studyEventId);
            }
        }

        // ---- 1. stage once, digest on the way ----
        Path staged;
        String sha256;
        try {
            Path dir = Paths.get(PublicOctUploadController.uploadsDir());
            Files.createDirectories(dir);
            staged = dir.resolve(UUID.randomUUID() + ".e2e");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = file.getInputStream(); DigestInputStream dis = new DigestInputStream(in, md)) {
                Files.copy(dis, staged);
            }
            sha256 = HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.error("Failed to stage a split E2E upload: {}", e.getClass().getSimpleName());
            throw SplitUploadRejection.of(500, "Failed to persist E2E");
        }

        // The staged file, the stored DICOMs and the conversion directory belong to this request until
        // the rows are committed; whatever happens before that, none of them may be left behind.
        List<Path> written = new ArrayList<>();
        Path convertDir = null;
        boolean committed = false;
        try {
            // ---- 2. already split? (also after the source file was deleted) ----
            List<Long> existing = existingItems(dataSource, sha256);
            if (!existing.isEmpty()) {
                throw SplitUploadRejection.duplicate(MESSAGE_DUPLICATE, visibleOnly(existing, visible));
            }

            // ---- 3. describe, then check what the client wants left out ----
            E2eSidecarClient.Description described = describe(staged, folder);
            String problem = validate(described);
            if (problem != null) {
                LOG.warn("Split E2E upload refused: {}", problem);
                throw SplitUploadRejection.of(502, "The image converter's answer was not usable: " + problem);
            }
            if (described.series().isEmpty()) {
                throw SplitUploadRejection.of(422, "Die .e2e-Datei enthaelt keine Bildserie.");
            }
            Set<String> known = new HashSet<>();
            for (E2eSidecarClient.Series s : described.series()) known.add(s.seriesKey());
            for (String key : excluded) {
                if (!known.contains(key)) {
                    throw SplitUploadRejection.of(400, "excludeSeries names a series the file does not have");
                }
            }
            List<E2eSidecarClient.Series> kept = new ArrayList<>();
            for (E2eSidecarClient.Series s : described.series()) {
                if (!excluded.contains(s.seriesKey())) kept.add(s);
            }
            if (kept.isEmpty()) {
                throw SplitUploadRejection.of(400, "excludeSeries leaves out every series; nothing to upload");
            }

            // ---- 4. who the file belongs to ----
            // The file's label against the folder: a disagreement parks everything and names no subject.
            String headerLabel = described.headerLabel();
            boolean differs = "differs".equals(described.headerPatient())
                    || (folder != null && headerLabel != null && !headerLabel.equalsIgnoreCase(folder));
            String headerPatient = differs ? "differs" : described.headerPatient();
            // The label the subject is looked up by: the file's own, else the folder's.
            String label = differs ? null : (headerLabel != null ? headerLabel : folder);

            IngestResolutionService.ResolveCandidate matched = label == null ? null
                    : oct.resolution().resolve(label, (LocalDate) null, resolveScope).single().orElse(null);
            boolean allParked = park || differs || label == null
                    || (studyEventId == null && matched == null)
                    // a visit picked for another subject than the label names is not guessed at
                    || (pickedSubject != null && matched != null && matched.studySubjectId() != pickedSubject);
            Integer labelSubject = pickedSubject != null ? pickedSubject
                    : matched == null ? null : matched.studySubjectId();
            // What may be written down. A label the file supplied is kept only when it names a visible
            // subject; the folder is the client's own word and is kept as before.
            String pid = matched != null ? matched.subjectLabel() : folder;
            String subjectLabel = matched != null ? matched.subjectLabel()
                    : pickedSubject != null && !allParked ? UploadRoute.subjectLabelForStudyEvent(dataSource, studyEventId)
                    : null;
            // The only identity a DICOM carries: the matched subject's label; blank while the file is parked.
            String pseudonym = allParked ? null : subjectLabel;

            // ---- 5. convert the kept series in one pass, import each DICOM ----
            List<String> keptKeys = new ArrayList<>();
            for (E2eSidecarClient.Series s : kept) keptKeys.add(s.seriesKey());
            E2eSidecarClient.Conversion conversion = convert(staged, pseudonym, keptKeys);
            convertDir = conversion.directory();
            Map<String, E2eSidecarClient.ConvertedFile> convertedByKey = checkConversion(conversion, keptKeys);

            List<Planned> plan = new ArrayList<>();
            try (Connection c = dataSource.getConnection()) {
                Map<LocalDate, IngestResolutionService.Resolution> byDate = new HashMap<>();
                for (E2eSidecarClient.Series s : kept) {
                    E2eSidecarClient.ConvertedFile cf = convertedByKey.get(s.seriesKey());
                    IngestArtifactStore.Stored stored = importConverted(conversion.directory(), cf, written);
                    DicomDescribeClient.Description desc = describeDicom(stored.path(), pseudonym, written);
                    if (desc.sopInstanceUid() != null) {
                        Long dup = findBySopInstanceUid(c, desc.sopInstanceUid());
                        if (dup != null) {
                            throw SplitUploadRejection.duplicate(MESSAGE_DUPLICATE, visibleOnly(List.of(dup), visible));
                        }
                    }

                    String kind = s.seriesModality();
                    LocalDate date = dateOf(s.acquiredAt());
                    String lat = lateralityOf(s.laterality());
                    IngestTwins.Twin twin = IngestTwins.find(c, desc.pixelSha256());
                    boolean parkThis = allParked || twin != null;
                    Integer seriesEvent = null;
                    Integer seriesCrf = null;
                    Integer seriesSubject = labelSubject;
                    if (!parkThis) {
                        if (studyEventId != null) {
                            seriesEvent = studyEventId;
                        } else {
                            IngestResolutionService.Resolution r = byDate.computeIfAbsent(date,
                                    d -> oct.resolution().resolve(label, d, resolveScope));
                            if (r.isSuggested() && r.single().isPresent()
                                    && r.single().get().matchingEvent() != null) {
                                var ev = r.single().get().matchingEvent();
                                seriesEvent = ev.studyEventId();
                                seriesCrf = ev.eventCrfId();
                                seriesSubject = r.single().get().studySubjectId();
                            } else {
                                parkThis = true;
                            }
                        }
                    }

                    Integer modalityId = null;
                    if (!parkThis) {
                        Integer studyId = VisitImagingPlan.studyOfBinding(c, seriesCrf, seriesEvent);
                        if (studyId != null) {
                            modalityId = VisitImagingPlan.modalityByE2eSeries(c, studyId, kind);
                            if (modalityId == null && "OCT".equals(kind)) {
                                modalityId = VisitImagingPlan.octModalityOf(c, studyId, "dicom", deviceOf(desc));
                                if (modalityId == null) modalityId = VisitImagingPlan.e2eModalityOf(c, studyId);
                            }
                        }
                    }
                    plan.add(new Planned(s, cf, stored, desc, s.seriesKey(), kind, date, lat, parkThis,
                            seriesEvent, seriesCrf, seriesSubject, twin, modalityId));
                }
            } catch (SQLException e) {
                LOG.error("Split E2E upload - planning failed: {}", e.getMessage());
                throw SplitUploadRejection.of(500, "Failed to plan the upload");
            }

            // ---- 6. write: the items in one transaction ----
            List<Long> itemIds = new ArrayList<>();
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try {
                    for (Planned p : plan) {
                        DeviceScope.Origin origin = DeviceScope.Origin.of(c, source);
                        var item = IngestItemRepository
                                .newItem(IngestArtifactStore.Kind.DICOM, SOURCE_KIND, p.stored().path().toString())
                                .previewPngPath(p.desc().previewPngPath())
                                // The E2E's own file name is derived from the patient's name: not kept.
                                .originalFilename(seriesFileName(p))
                                .contentType("application/dicom")
                                .digest(p.stored().sha256(), p.stored().byteSize())
                                .pixelSha256(p.desc().pixelSha256())
                                .e2eSourceSha256(sha256)
                                .e2eSeriesKey(p.key())
                                .seriesModality(p.kind())
                                .seriesFrames(p.series().frames())
                                .device(deviceOf(p.desc()))
                                .sopInstanceUid(p.desc().sopInstanceUid())
                                .sopClassUid(p.desc().sopClassUid())
                                .studyInstanceUid(p.desc().studyInstanceUid())
                                .seriesInstanceUid(p.desc().seriesInstanceUid())
                                .modality(p.desc().modality())
                                // DR-039 - an OCT volume is analysed whatever its format.
                                .octVolume(OctVolumes.of(p.desc()))
                                .manufacturer(p.desc().manufacturer(), p.desc().manufacturerModelName())
                                .deidentifiedAt(p.desc().identityRemoved() ? Instant.now() : null)
                                .laterality(p.laterality())
                                .acquisitionDate(p.date())
                                .acquisitionDateSource(p.date() == null ? null : IngestItemRepository.ACQ_SOURCE_FILE)
                                .patientId(pid)
                                .imagingModalityId(p.modalityId())
                                .candidateStudySubjectId(p.subjectId())
                                .originLocationId(origin.locationId())
                                .ingestSourceId(origin.ingestSourceId());
                        if (!p.park()) {
                            ImageIngestBinding.EventTarget target =
                                    PublicOctUploadController.resolveBindTarget(c, p.eventCrfId(), p.studyEventId());
                            if (target != null) {
                                item.boundTo(target.studySubjectId(), target.studyEventId(), target.eventCrfId(),
                                        IngestBindService.POLICY_VISIT_PICKED);
                            }
                        }
                        itemIds.add(item.insert(c));
                    }
                    c.commit();
                    committed = true;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    throw SplitUploadRejection.duplicate(MESSAGE_DUPLICATE,
                            visibleOnly(existingItems(dataSource, sha256), visible));
                }
                LOG.error("Split E2E upload - could not write the items: {}", e.getMessage());
                throw SplitUploadRejection.of(500, "Failed to file the upload");
            } catch (SplitUploadRejection e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.error("Split E2E upload - could not write the items: {}", e.getClass().getSimpleName());
                throw SplitUploadRejection.of(500, "Failed to file the upload");
            }

            // ---- 7. audit, held-back trail, analyses, response ----
            List<E2eSplitItemDto> items = new ArrayList<>();
            boolean anyBound = false;
            for (int i = 0; i < plan.size(); i++) {
                Planned p = plan.get(i);
                long id = itemIds.get(i);
                String status = p.park() ? "UNBOUND" : "BOUND";
                anyBound |= !p.park();
                oct.writePublicOctUploadAuditRow("ingest_item", id, p.subjectId(), pid == null ? "" : pid,
                        p.laterality(), status);
                if (p.twin() != null) IngestTwins.writeHeldAudit(dataSource, id, p.twin(), pid, null, SOURCE_KIND);
                List<E2eSplitJobDto> jobs = startAnalyses(id, p, actor);
                boolean held = p.twin() != null;
                Long same = held && (visible == null || visible.test(p.twin().ingestItemId()))
                        ? p.twin().ingestItemId() : null;
                items.add(new E2eSplitItemDto(id, p.key(), p.kind(), p.laterality(),
                        p.date() == null ? null : p.date().toString(), p.series().frames(), p.modalityId(),
                        status, jobs, held, same));
            }
            LOG.info("Split E2E upload - {} item(s) from one file, {} left out, headerPatient={}, bound={}",
                    plan.size(), excluded.size(), headerPatient, anyBound);

            return new E2eSplitCommitResponse(sha256, headerPatient, subjectLabel, !anyBound, items,
                    new ArrayList<>(excluded), "e2e", "e2e");
        } finally {
            if (convertDir != null) deleteTree(convertDir);
            if (committed) {
                // The original moves to the time-limited source area; it is not kept where the sidecar reads.
                if (E2eSourceRetentionService.moveIn(staged, E2eSourceRetentionService.sourceRoot()) == null) {
                    discard(staged);
                }
            } else {
                discard(staged);
                for (Path p : written) discard(p);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* sidecars                                                            */
    /* ------------------------------------------------------------------ */

    private E2eSidecarClient.Description describe(Path staged, String folder) {
        try {
            return sidecar.describe(staged.getFileName().toString(), folder);
        } catch (E2eSidecarClient.SidecarException e) {
            throw SplitUploadRejection.of(e.httpStatus(), e.getMessage());
        }
    }

    private E2eSidecarClient.Conversion convert(Path staged, String pseudonym, List<String> keys) {
        try {
            return sidecar.convert(staged.getFileName().toString(), pseudonym, keys);
        } catch (E2eSidecarClient.SidecarException e) {
            throw SplitUploadRejection.of(e.httpStatus(), e.getMessage());
        }
    }

    /** The conversion must hold exactly the kept series, each once, under plain names. */
    private static Map<String, E2eSidecarClient.ConvertedFile> checkConversion(
            E2eSidecarClient.Conversion conversion, List<String> keptKeys) {
        Map<String, E2eSidecarClient.ConvertedFile> byKey = new HashMap<>();
        for (E2eSidecarClient.ConvertedFile f : conversion.files()) {
            if (f == null || f.seriesKey() == null || f.file() == null || f.sha256() == null
                    || !CONVERTED_NAME.matcher(f.file()).matches() || !SHA256.matcher(f.sha256()).matches()
                    || byKey.put(f.seriesKey(), f) != null) {
                LOG.warn("Split E2E upload refused: the converter listed an unusable file");
                throw SplitUploadRejection.of(502, "The image converter's conversion answer was not usable.");
            }
        }
        if (!byKey.keySet().equals(new HashSet<>(keptKeys))) {
            LOG.warn("Split E2E upload refused: the converter did not return exactly the kept series");
            throw SplitUploadRejection.of(502, "The image converter did not return the requested series.");
        }
        return byKey;
    }

    /** Copies one converted DICOM into the ingest store and checks it is the file the converter wrote. */
    private IngestArtifactStore.Stored importConverted(Path dir, E2eSidecarClient.ConvertedFile cf, List<Path> written) {
        Path src = dir.resolve(cf.file()).normalize();
        if (!src.startsWith(dir.normalize()) || !Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) {
            LOG.warn("Split E2E upload refused: a converted file is missing or outside its directory");
            throw SplitUploadRejection.of(502, "The image converter's output could not be read.");
        }
        IngestArtifactStore.Stored stored;
        try (InputStream in = Files.newInputStream(src)) {
            stored = store.store(IngestArtifactStore.Kind.DICOM, in, cf.file());
        } catch (IOException e) {
            LOG.error("Split E2E upload - could not store a converted DICOM: {}", e.getClass().getSimpleName());
            throw SplitUploadRejection.of(500, "could not store the file");
        }
        written.add(stored.path());
        if (!stored.sha256().equalsIgnoreCase(cf.sha256()) || (cf.bytes() != null && cf.bytes() != stored.byteSize())) {
            LOG.warn("Split E2E upload refused: a converted file changed on its way into the store");
            throw SplitUploadRejection.of(502, "The image converter's output did not arrive intact.");
        }
        return stored;
    }

    /** The DICOM sidecar's description of a stored DICOM; it also rewrites the file's identity in place. */
    private DicomDescribeClient.Description describeDicom(Path stored, String pseudonym, List<Path> written) {
        try {
            DicomDescribeClient.Description desc = dicom.describe(stored, pseudonym);
            if (desc.previewPngPath() != null) written.add(Path.of(desc.previewPngPath()));
            return desc;
        } catch (DicomDescribeClient.DescribeException e) {
            LOG.warn("Split E2E upload - the DICOM service refused a converted file: {}", e.reason());
            switch (e.reason()) {
                case UNCONFIGURED, UNREACHABLE -> throw SplitUploadRejection.of(503,
                        "DICOM uploads are not available right now - the DICOM service is not reachable");
                case NOT_DICOM -> throw SplitUploadRejection.of(502, "The converter wrote a file that is not DICOM.");
                default -> throw SplitUploadRejection.of(502, "the DICOM service could not process the file");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Starts what the visit's imaging plan wants for a bound OCT volume, through the DICOM OCT path
     * (the same call a DICOM OCT upload and an inbox bind make); returns the jobs it created.
     * An OCT volume with no eye in the file gets none: the preprocess step needs the eye.
     */
    private List<E2eSplitJobDto> startAnalyses(long itemId, Planned p, IngestBindService.Actor actor) {
        if (p.park() || !"OCT".equals(p.kind()) || p.laterality() == null
                || !Boolean.TRUE.equals(OctVolumes.of(p.desc()))) {
            return List.of();
        }
        try {
            RetinalJobFollower.Ensured e = follower.ensure(itemId, actor, false);
            LOG.info("Split E2E upload - ingest_item {} is a DICOM OCT volume: {} analysis(es) started{}",
                    itemId, e.started(), e.skippedBecause() == null ? "" : " (" + e.skippedBecause() + ")");
        } catch (SQLException | RuntimeException e) {
            LOG.error("Split E2E upload - ingest_item {} is filed, but its analyses could not start: {}",
                    itemId, e.getMessage());
        }
        List<E2eSplitJobDto> jobs = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT job_id, task, status FROM retinal_inference_job "
                             + " WHERE ingest_item_id = ? AND status <> 'cancelled' ORDER BY job_id")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) jobs.add(new E2eSplitJobDto(rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            LOG.warn("Split E2E upload - could not list the jobs of ingest_item {}: {}", itemId, e.getMessage());
        }
        return jobs;
    }

    private Long findBySopInstanceUid(Connection c, String sop) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ingest_item_id FROM ingest_item WHERE sop_instance_uid = ? LIMIT 1")) {
            ps.setString(1, sop);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** The catalogue's device key for a described DICOM; the export is a Spectralis one when it says nothing. */
    private static String deviceOf(DicomDescribeClient.Description desc) {
        String key = IngestUploadService.deviceKeyFromModel(desc.manufacturerModelName(), desc.manufacturer());
        return key != null ? key : "spectralis";
    }

    private static String seriesFileName(Planned p) {
        return p.kind() + "_" + (p.laterality() == null ? "NA" : p.laterality()) + "_" + p.key() + ".dcm";
    }

    /** {@code excludeSeries}: comma-separated keys; a malformed one is a 400 before anything is stored. */
    static Set<String> parseExcluded(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String key = part.trim();
            if (key.isEmpty()) continue;
            if (!SERIES_KEY.matcher(key).matches()) {
                throw SplitUploadRejection.of(400, "excludeSeries holds a malformed series key");
            }
            out.add(key);
            if (out.size() > MAX_EXCLUDED) {
                throw SplitUploadRejection.of(400, "excludeSeries names too many series");
            }
        }
        return out;
    }

    private static void discard(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // an orphan file is harmless; ops can clear it
        }
    }

    /** Depth-first delete of the conversion directory; never follows a link. */
    private static void deleteTree(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("could not remove a conversion directory: {}", e.getClass().getSimpleName());
        }
    }

    /** A reason the sidecar's answer cannot be filed, or null. */
    static String validate(E2eSidecarClient.Description d) {
        if (d == null || d.headerPatient() == null || !HEADER_STATES.contains(d.headerPatient())) {
            return "headerPatient";
        }
        if (d.headerLabel() != null && !LABEL_TOKEN.matcher(d.headerLabel()).matches()) {
            return "headerLabel";
        }
        Set<String> keys = new HashSet<>();
        for (E2eSidecarClient.Series s : d.series()) {
            if (s == null || s.seriesKey() == null || !SERIES_KEY.matcher(s.seriesKey()).matches()) {
                return "seriesKey";
            }
            if (!keys.add(s.seriesKey())) return "duplicate seriesKey";
            if (s.seriesModality() == null || !SERIES_KINDS.contains(s.seriesModality())) {
                return "seriesModality";
            }
        }
        return null;
    }

    /** The date part of an ISO local date-time (or date); null when absent or unreadable. */
    static LocalDate dateOf(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try {
            return LocalDate.parse(iso.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String lateralityOf(String raw) {
        if (raw == null) return null;
        String l = raw.trim().toUpperCase(Locale.ROOT);
        return "OD".equals(l) || "OS".equals(l) ? l : null;
    }
}
