/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.FileKindSniffer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestResolutionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Exam;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Image;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.RemidioException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * DR-031 — one pass of the Remidio cloud pull: list the window, file every
 * image the inbox has not seen, bind the ones that identify their visit.
 *
 * <p>This is the third ingress into {@code ingest_item}, beside the upload
 * page and the DICOM receiver, and it borrows their rules rather than
 * inventing its own: the file goes through {@link IngestArtifactStore}, the
 * row through {@link IngestItemRepository}, the visit through
 * {@link IngestResolutionService}, and a bind nobody performed is audited and
 * ticked exactly the way a worklist bind is.
 *
 * <p><b>What identifies the visit.</b> The MRN the photographer typed into the
 * Remidio app — by convention the study subject label — and the exam date the
 * camera stamped. One subject with one visit on that day binds; anything else
 * lands {@code UNBOUND} with the label and date pre-filled, which is the same
 * step an operator performs for a page upload without a visit. The API also
 * carries a name, a date of birth and a sex; they are read and dropped.
 *
 * <p><b>Idempotence.</b> The window is re-listed every poll. An exam already
 * in {@code remidio_exam} is skipped; an image already on an
 * {@code ingest_item} (by {@code remidio_image_id}) is skipped; the partial
 * unique index on {@code sha256} refuses the same bytes under a second id. An
 * exam is marked seen only after every one of its images was handled, so a
 * failure mid-exam (an expired download link, a full disk) is retried on the
 * next poll rather than lost.
 */
final class RemidioPullService {

    private static final Logger LOG = LoggerFactory.getLogger(RemidioPullService.class);

    /** {@code ingest_item.source_kind} for a file that came from the Remidio cloud. */
    static final String SOURCE_KIND = "remidio";

    /** The device key the imaging catalogue and the checklist use for this camera. */
    static final String DEVICE = "remidio";

    /**
     * {@code match_policy} of a pull auto-bind: the typed MRN matched exactly
     * one subject, who had exactly one visit on the exam date.
     */
    static final String POLICY_MRN = "mrn";

    /** The cloud stamps instants; a visit is a day in the clinic's zone. */
    static final ZoneId CLINIC_ZONE = ZoneId.of("Europe/Vienna");

    /** Enough of the file for {@link FileKindSniffer} to say what it is. */
    private static final int SNIFF_BYTES = 512;

    /** What one pass did, for the log and the status page. */
    record Summary(int exams, int newExams, int images, int bound, int unbound, int duplicates,
                   int skipped, int failed) {

        String line() {
            return "exams=" + exams + " new=" + newExams + " images=" + images + " bound=" + bound
                    + " unbound=" + unbound + " duplicates=" + duplicates + " skipped=" + skipped
                    + " failed=" + failed;
        }
    }

    private enum Outcome { BOUND, UNBOUND, DUPLICATE, SKIPPED, FAILED }

    private final DataSource dataSource;
    private final RemidioGatewayClient client;
    private final IngestArtifactStore store;
    private final IngestResolutionService resolution;
    private final StudySettingService settings;

    RemidioPullService(DataSource dataSource, RemidioGatewayClient client) {
        this(dataSource, client, new IngestArtifactStore(),
                new IngestResolutionService(new StudySubjectFinder(dataSource)),
                new StudySettingService(dataSource));
    }

    RemidioPullService(DataSource dataSource, RemidioGatewayClient client, IngestArtifactStore store,
                       IngestResolutionService resolution, StudySettingService settings) {
        this.dataSource = dataSource;
        this.client = client;
        this.store = store;
        this.resolution = resolution;
        this.settings = settings;
    }

    /* ------------------------------------------------------------------ */
    /* one pass                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Lists the window and files what is new.
     *
     * @throws RemidioException when the listing itself fails — nothing was
     *                          filed, the caller decides whether to back off
     */
    Summary pull(LocalDate from, LocalDate to) throws RemidioException {
        List<Exam> exams = client.examsBetween(from, to);
        int newExams = 0, images = 0, bound = 0, unbound = 0, duplicates = 0, skipped = 0, failed = 0;
        for (Exam exam : exams) {
            if (examSeen(exam.id())) continue;
            newExams++;
            boolean complete = true;
            int filed = 0;
            for (Image image : exam.standardImages()) {
                images++;
                Outcome o = file(exam, image);
                switch (o) {
                    case BOUND -> { bound++; filed++; }
                    case UNBOUND -> { unbound++; filed++; }
                    case DUPLICATE -> duplicates++;
                    case SKIPPED -> skipped++;
                    case FAILED -> { failed++; complete = false; }
                }
            }
            if (complete) markSeen(exam, filed);
        }
        return new Summary(exams.size(), newExams, images, bound, unbound, duplicates, skipped, failed);
    }

    /* ------------------------------------------------------------------ */
    /* one image                                                           */
    /* ------------------------------------------------------------------ */

    private Outcome file(Exam exam, Image image) {
        if (image.id() == null || image.path() == null) {
            LOG.debug("remidio: exam {} has an image without id or path — skipped", exam.id());
            return Outcome.SKIPPED;
        }
        if (imageFiled(image.id())) return Outcome.DUPLICATE;

        // Download, sniff, store — the stored name carries the sniffed
        // extension, the same rule the upload page applies.
        IngestArtifactStore.Stored stored;
        FileKindSniffer.Sniffed sniffed;
        try (InputStream raw = client.download(image.path());
             BufferedInputStream in = new BufferedInputStream(
                     new CappedInputStream(raw, IngestUploadService.MAX_IMAGE_BYTES), SNIFF_BYTES * 2)) {
            in.mark(SNIFF_BYTES);
            byte[] head = in.readNBytes(SNIFF_BYTES);
            in.reset();
            sniffed = FileKindSniffer.sniff(head);
            if (sniffed == null || sniffed.kind() != IngestArtifactStore.Kind.IMAGE) {
                LOG.warn("remidio: image {} of exam {} is not an image file — skipped", image.id(), exam.id());
                return Outcome.SKIPPED;
            }
            stored = store.store(IngestArtifactStore.Kind.IMAGE, in, "remidio" + sniffed.extension());
        } catch (RemidioException e) {
            LOG.warn("remidio: image {} of exam {} could not be fetched ({}): {}",
                    image.id(), exam.id(), e.reason(), e.getMessage());
            return Outcome.FAILED;
        } catch (IOException e) {
            LOG.warn("remidio: image {} of exam {} could not be stored: {}", image.id(), exam.id(), e.getMessage());
            return Outcome.FAILED;
        }
        Path path = stored.path();

        String laterality = lateralityOf(image.laterality());
        LocalDate date = dateOf(image.date(), exam.examDate());
        String mrn = blankToNull(exam.mrn());

        // The visit, if the label and the day name exactly one.
        ImageIngestBinding.EventTarget target = null;
        Integer candidateSubject = null;
        if (mrn != null) {
            try {
                IngestResolutionService.Resolution r = resolution.resolve(mrn, date, null);
                IngestResolutionService.ResolveCandidate c = r.single().orElse(null);
                if (c != null) {
                    candidateSubject = c.studySubjectId();
                    if (r.isSuggested() && c.matchingEvent() != null
                            && settings.ingressEnabled(c.studyId(), StudySettingService.INGEST_IMAGE_ENABLED, null)) {
                        target = new ImageIngestBinding.EventTarget(c.studySubjectId(),
                                c.matchingEvent().studyEventId(), c.matchingEvent().eventCrfId());
                    }
                }
            } catch (RuntimeException lookupFailed) {
                // Filing UNBOUND with the label is still right; only the
                // one-click bind is lost, and the operator sees the row.
                LOG.warn("remidio: visit lookup failed for image {} — filing unbound: {}",
                        image.id(), lookupFailed.getMessage());
            }
        }

        long id;
        try (Connection c = dataSource.getConnection()) {
            var item = IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.IMAGE, SOURCE_KIND, path.toString())
                    .device(DEVICE)
                    .previewPngPath(path.toString())
                    .originalFilename(filenameFor(exam, image, laterality, sniffed.extension()))
                    .contentType(sniffed.contentType())
                    .digest(stored.sha256(), stored.byteSize())
                    .patientId(mrn)
                    .laterality(laterality)
                    .acquisitionDate(date)
                    .acquisitionDateSource(date == null ? null : IngestItemRepository.ACQ_SOURCE_DEVICE)
                    .remidioImageId(image.id())
                    .candidateStudySubjectId(target == null ? candidateSubject : null);
            if (target != null) {
                item.boundTo(target.studySubjectId(), target.studyEventId(), target.eventCrfId(), POLICY_MRN);
            }
            id = item.insert(c);
        } catch (SQLException e) {
            deleteQuietly(path);
            if ("23505".equals(e.getSQLState())) {
                // Same bytes already filed (the sha256 index), or the same
                // cloud id raced in from a parallel pass.
                return Outcome.DUPLICATE;
            }
            LOG.error("remidio: INSERT failed for image {} of exam {}: {}", image.id(), exam.id(), e.getMessage());
            return Outcome.FAILED;
        }

        if (target != null) {
            ImageIngestBinding.writeSystemBindAudit(dataSource, id, POLICY_MRN, target.studyEventId());
            ImageIngestBinding.tickPerformed(dataSource, id, target, SOURCE_KIND, DEVICE, laterality, null);
        }
        LOG.info("remidio: ingest_item {} landed {} (exam {}, image {}, {})", id,
                target == null ? "UNBOUND" : "BOUND", exam.id(), image.id(), laterality == null ? "-" : laterality);
        return target == null ? Outcome.UNBOUND : Outcome.BOUND;
    }

    /* ------------------------------------------------------------------ */
    /* mapping                                                             */
    /* ------------------------------------------------------------------ */

    /** The API's {@code RIGHT}/{@code LEFT} onto the OD/OS the rest of the inbox speaks. */
    static String lateralityOf(String api) {
        if (api == null) return null;
        return switch (api.trim().toUpperCase(Locale.ROOT)) {
            case "RIGHT", "R", "OD" -> "OD";
            case "LEFT", "L", "OS" -> "OS";
            case "BOTH", "OU" -> "OU";
            default -> null;
        };
    }

    /** The image's own capture instant when it has one, else the exam's, as a clinic day. */
    static LocalDate dateOf(Instant imageDate, Instant examDate) {
        Instant at = imageDate != null ? imageDate : examDate;
        return at == null ? null : at.atZone(CLINIC_ZONE).toLocalDate();
    }

    /** A filename that says where it came from, and contains nothing a path could misread. */
    static String filenameFor(Exam exam, Image image, String laterality, String extension) {
        String base = safe(exam.id()) + "_" + safe(image.id()) + (laterality == null ? "" : "_" + laterality);
        if (base.length() > 200) base = base.substring(0, 200);
        return base + (extension == null ? "" : extension);
    }

    private static String safe(String s) {
        if (s == null) return "x";
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            sb.append(Character.isLetterOrDigit(ch) || ch == '-' ? ch : '_');
        }
        return sb.length() == 0 ? "x" : sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /* ------------------------------------------------------------------ */
    /* memory                                                              */
    /* ------------------------------------------------------------------ */

    private boolean examSeen(String examId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM remidio_exam WHERE remidio_exam_id = ?")) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // Treat a failed lookup as "seen": the alternative is to download
            // and file an exam we may already have, which the image-id and
            // sha256 guards would then refuse one by one.
            LOG.warn("remidio: could not check exam {}: {}", examId, e.getMessage());
            return true;
        }
    }

    private boolean imageFiled(String imageId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM ingest_item WHERE remidio_image_id = ?")) {
            ps.setString(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("remidio: could not check image {}: {}", imageId, e.getMessage());
            return true;
        }
    }

    private void markSeen(Exam exam, int imageCount) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO remidio_exam (remidio_exam_id, site_custom_id, exam_date, image_count) "
                             + "VALUES (?, ?, ?, ?) "
                             + "ON CONFLICT (remidio_exam_id) DO UPDATE SET image_count = EXCLUDED.image_count")) {
            ps.setString(1, exam.id());
            ps.setString(2, client.settings().siteCustomId());
            if (exam.examDate() == null) {
                ps.setNull(3, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(3, Timestamp.from(exam.examDate()));
            }
            ps.setInt(4, imageCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            // The next poll re-lists the exam; every image is guarded by its
            // id, so the cost is one extra round of lookups, not duplicates.
            LOG.warn("remidio: could not record exam {} as seen: {}", exam.id(), e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // an orphan file is the retention sweep's problem
        }
    }

    /** Refuses to read past a limit — a signed URL is not ours to trust blindly. */
    private static final class CappedInputStream extends FilterInputStream {
        private final long max;
        private long read;

        CappedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > max) throw new IOException("download exceeds " + (max / (1024 * 1024)) + " MB");
        }
    }
}
