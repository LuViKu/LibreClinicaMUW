/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;

/**
 * P3.7 — a subject's whole record, files included.
 *
 * <p>Until now a subject could be exported as ODM, CSV or PDF, and every one
 * of those is text. The OCT volumes, the fundus photographs, the segmentation
 * masks and the files attached to CRF items never left the server — a FILE
 * item exported as a <em>server path</em>, which is both useless to the
 * recipient and a disclosure of the filesystem layout. For a study whose
 * primary endpoint is imaging, "export the subject" did not export the
 * subject.
 *
 * <p>This writes a zip: the casebook as ODM and CSV, then every file the
 * subject actually has, then a manifest describing what is inside.
 *
 * <p><strong>Streamed, never buffered.</strong> An OCT volume runs to a couple
 * of hundred megabytes and a subject may have several; {@code Files.copy(path,
 * zos)} moves each one through without it ever being a {@code byte[]}. Already
 * compressed kinds are stored rather than deflated — spending CPU to grow a
 * JPEG is not a trade worth making.
 *
 * <p><strong>The manifest is written last</strong>, on purpose. It records
 * what was actually written, including what was skipped and why, so a truncated
 * download is detectable: a bundle without a manifest is an incomplete bundle.
 *
 * <p><strong>Every path is confined before it is read.</strong> These paths
 * come from database rows that an unauthenticated ingress can write, so each is
 * resolved against its store's known roots — symlinks followed — and refused
 * otherwise. Without that, one bad row turns an export into an arbitrary file
 * read.
 */
public final class BundleExportWriter {

    private static final Logger LOG = LoggerFactory.getLogger(BundleExportWriter.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Kinds already compressed; deflating them costs CPU and gains nothing. */
    private static final List<String> STORE_AS_IS =
            List.of(".e2e", ".dcm", ".jpg", ".jpeg", ".png", ".zip", ".gz");

    private BundleExportWriter() {}

    /**
     * What the requester may see.
     *
     * @param maskAi when true, AI-derived output is left out and listed in
     *               {@code omitted} — a treating clinician on a blinded
     *               subject must not receive by export what the screen
     *               withholds. The raw scan is not AI output and is kept.
     */
    public record Policy(boolean maskAi) {}

    /** One file that could have been in the bundle and is not, and why. */
    public record Omission(String ref, String reason) {}

    public record Result(int filesWritten, long bytesWritten, List<Omission> omitted,
                         Map<String, Object> manifest) {}

    /**
     * Write the bundle.
     *
     * @param casebookXml the annotated ODM the text export already produces
     * @param casebookCsv the flat rendering, for a reader without an ODM tool
     * @param dryRun      build the manifest and skip the files, so a requester
     *                    can see what a bundle would contain — and how large it
     *                    would be — before asking for gigabytes
     */
    public static Result write(OutputStream out, DataSource dataSource,
                               int studySubjectId, String subjectLabel, String studyOid,
                               byte[] casebookXml, byte[] casebookCsv,
                               Policy policy, String generatedBy, boolean dryRun)
            throws IOException {

        ZipOutputStream zos = dryRun ? null : new ZipOutputStream(out);
        try {
            Subject s = addSubject(zos, dataSource, studySubjectId, subjectLabel, "",
                    casebookXml, casebookCsv, policy);

            Map<String, Object> manifest = manifest(subjectLabel, studyOid, generatedBy,
                    policy, s.acquisitions(), s.crfFiles(), s.inference(), s.omitted(),
                    s.filesWritten(), s.bytesWritten());

            if (zos != null) {
                // Last, deliberately: a bundle without a manifest is an
                // incomplete bundle, so a truncated download is detectable.
                writeBytes(zos, "manifest.json",
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zos.finish();
            }
            return new Result(s.filesWritten(), s.bytesWritten(), s.omitted(), manifest);
        } finally {
            if (zos != null) zos.flush();
        }
    }

    /**
     * One subject of a dataset bundle: who, and the casebook already rendered
     * for them.
     *
     * @param label the subject label, which also names its folder in the zip
     */
    public record DatasetSubject(int studySubjectId, String label,
                                 byte[] casebookXml, byte[] casebookCsv, Policy policy) {}

    /**
     * Where a subject's folder sits inside a dataset bundle. One rule, shared
     * with whoever renders the casebook first: the {@code muw:ManifestPath}
     * annotations have to name the same entries the zip will contain, and the
     * casebook is rendered before the zip exists.
     */
    public static String subjectPrefix(String label) {
        return "subjects/" + safeFolder(label) + "/";
    }

    /**
     * P3.8 — a whole dataset as one archive.
     *
     * <p>One zip, one manifest, a folder per subject. The alternative — a zip
     * of per-subject zips — would have been less work here and more work for
     * every recipient, who would have to unpack twice before finding anything
     * and would have no single place to read what the export contains or what
     * it withheld.
     *
     * <p>Subjects are written in the order given and each is independent: one
     * subject whose files have gone missing is reported in {@code omitted} and
     * does not stop the rest. A dataset export that aborts partway because of
     * one bad row would be worse than one that says which row was bad.
     *
     * @param dryRun describe the bundle without writing it — for a dataset
     *               this matters more than for one subject, because the answer
     *               can run to tens of gigabytes
     */
    public static Result writeDataset(OutputStream out, DataSource dataSource,
                                      List<DatasetSubject> subjects, String studyOid,
                                      String generatedBy, boolean dryRun)
            throws IOException {

        List<Omission> omitted = new ArrayList<>();
        List<Map<String, Object>> subjectSections = new ArrayList<>();
        int written = 0;
        long bytes = 0;

        ZipOutputStream zos = dryRun ? null : new ZipOutputStream(out);
        try {
            int masked = 0;
            for (DatasetSubject ds : subjects) {
                String prefix = subjectPrefix(ds.label());
                // Blinding is per subject, not per bundle: two subjects of the
                // same dataset can be in different arms, and the requester is
                // blinded to one and not the other.
                Subject s = addSubject(zos, dataSource, ds.studySubjectId(), ds.label(),
                        prefix, ds.casebookXml(), ds.casebookCsv(), ds.policy());
                if (ds.policy().maskAi()) masked++;

                Map<String, Object> section = new LinkedHashMap<>();
                section.put("label", ds.label());
                section.put("path", prefix);
                section.put("masking", ds.policy().maskAi() ? "ai-withheld" : "none");
                section.put("acquisitions", s.acquisitions());
                section.put("crfFiles", s.crfFiles());
                section.put("inference", s.inference());
                section.put("fileCount", s.filesWritten());
                section.put("byteSize", s.bytesWritten());
                subjectSections.add(section);

                omitted.addAll(s.omitted());
                written += s.filesWritten();
                bytes += s.bytesWritten();
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("generatedAt", Instant.now().toString());
            manifest.put("generatedBy", generatedBy);
            manifest.put("study", studyOid);
            manifest.put("scope", "dataset");
            // "mixed" tells a reader to look at each subject's own line rather
            // than assume one answer for the whole archive.
            manifest.put("masking", masked == 0 ? "none"
                    : masked == subjects.size() ? "ai-withheld" : "mixed");
            manifest.put("subjects", subjectSections);
            manifest.put("omitted", omitted.stream()
                    .map(o -> Map.of("ref", o.ref(), "reason", o.reason()))
                    .toList());
            manifest.put("fileCount", written);
            manifest.put("byteSize", bytes);

            if (zos != null) {
                writeBytes(zos, "manifest.json",
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zos.finish();
            }
            return new Result(written, bytes, omitted, manifest);
        } finally {
            if (zos != null) zos.flush();
        }
    }

    /**
     * A subject label as a folder name.
     *
     * <p>Labels are operator-typed and end up as zip entry names, so anything
     * that could climb out of the archive on extraction is replaced rather
     * than escaped — a path separator in a label must not become one in the
     * archive.
     */
    private static String safeFolder(String label) {
        if (label == null || label.isBlank()) return "unknown";
        String cleaned = label.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        // Leading dots go too. Replacing the separators already makes the name
        // inert — "../.." becomes ".._..", one folder, nowhere to climb — but
        // extraction tools treat a leading dot inconsistently and a hidden
        // folder is a poor place to put somebody's export.
        cleaned = cleaned.replaceAll("^\\.+", "");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    /**
     * What one subject contributed: its manifest sections and its totals.
     */
    record Subject(List<Map<String, Object>> acquisitions,
                   List<Map<String, Object>> crfFiles,
                   List<Map<String, Object>> inference,
                   List<Omission> omitted,
                   int filesWritten, long bytesWritten) {}

    /**
     * Write one subject's casebook and files into a zip the caller owns.
     *
     * <p>Split out so a dataset of subjects lands in <em>one</em> archive with
     * <em>one</em> manifest, rather than a zip of zips. The manifest schema
     * was written around a {@code subjects[]} array from the start; this is
     * what fills it for more than one.
     *
     * @param zos    the open archive, or null for a dry run
     * @param prefix "" for a single-subject bundle, {@code subjects/<label>/}
     *               within a dataset — every path in the returned manifest
     *               sections already carries it, so a reader never has to
     *               reconstruct where a file sits
     */
    static Subject addSubject(ZipOutputStream zos, DataSource dataSource,
                              int studySubjectId, String subjectLabel, String prefix,
                              byte[] casebookXml, byte[] casebookCsv, Policy policy)
            throws IOException {

        List<Omission> omitted = new ArrayList<>();
        List<Map<String, Object>> acquisitions = new ArrayList<>();
        List<Map<String, Object>> crfFiles = new ArrayList<>();
        List<Map<String, Object>> inference = new ArrayList<>();
        int written = 0;
        long bytes = 0;

        {
            if (zos != null) {
                writeBytes(zos, prefix + "casebook.xml", casebookXml);
                writeBytes(zos, prefix + "casebook.csv", casebookCsv);
                written += 2;
                bytes += casebookXml.length + casebookCsv.length;
            }

            IngestArtifactStore store = new IngestArtifactStore();
            for (IngestedFile f : ingestedFiles(dataSource, studySubjectId)) {
                Map<String, Object> entry = describeAcquisition(f);
                Path resolved = store.resolveConfined(f.storedPath()).orElse(null);
                if (resolved == null) {
                    // The row says there is a file and the store disagrees.
                    // Recorded rather than dropped: a recipient must be able to
                    // tell "this subject had no scan" from "the scan is gone".
                    omitted.add(new Omission("ingest_item/" + f.id(), "file missing or outside the store"));
                    entry.put("omitted", true);
                    acquisitions.add(entry);
                    continue;
                }
                String path = prefix + acquisitionEntryName(f);
                entry.put("path", path);
                if (zos != null) {
                    long size = copyInto(zos, path, resolved);
                    written++;
                    bytes += size;
                    entry.put("byteSize", size);
                } else {
                    entry.put("byteSize", sizeOrNull(resolved));
                }
                acquisitions.add(entry);
            }

            for (CrfFile f : crfFileItems(dataSource, studySubjectId)) {
                Map<String, Object> entry = describeCrfFile(f);
                Path resolved = confinedCrfFile(f.value());
                if (resolved == null) {
                    omitted.add(new Omission("item_data/" + f.itemDataId(),
                            "file missing or outside the CRF file store"));
                    entry.put("omitted", true);
                    crfFiles.add(entry);
                    continue;
                }
                String path = prefix + "crf-files/" + f.itemDataId() + "_" + safeName(resolved);
                entry.put("path", path);
                if (zos != null) {
                    long size = copyInto(zos, path, resolved);
                    written++;
                    bytes += size;
                    entry.put("byteSize", size);
                } else {
                    entry.put("byteSize", sizeOrNull(resolved));
                }
                crfFiles.add(entry);
            }

            // The pipeline's own output: the segmentation masks and the
            // companions it rendered from the volume. Without these the bundle
            // has the scan but not what the platform made of it — which for an
            // AI study is most of the point of exporting at all.
            for (RetinalArtifact a : retinalArtifacts(dataSource, studySubjectId)) {
                if (policy.maskAi() && a.isAiOutput()) {
                    // Named rather than silently absent: a recipient who does
                    // not know something was withheld cannot ask for it, and a
                    // blinded export that looks complete is worse than one
                    // that says so.
                    omitted.add(new Omission("retinal_job/" + a.jobId() + "/" + a.name(),
                            "AI-derived output withheld: this subject is in the blinded arm "
                                    + "and the requester is a treating clinician"));
                    continue;
                }
                Path resolved = confinedArtifact(a.path());
                if (resolved == null) {
                    omitted.add(new Omission("retinal_job/" + a.jobId() + "/" + a.name(),
                            "file missing or outside the artifact store"));
                    continue;
                }
                String path = prefix + "inference/" + a.jobId() + "/" + a.name();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jobId", a.jobId());
                entry.put("task", a.task());
                entry.put("artifact", a.name());
                entry.put("aiDerived", a.isAiOutput());
                entry.put("path", path);
                if (zos != null) {
                    long size = copyInto(zos, path, resolved);
                    written++;
                    bytes += size;
                    entry.put("byteSize", size);
                } else {
                    entry.put("byteSize", sizeOrNull(resolved));
                }
                inference.add(entry);
            }

            return new Subject(acquisitions, crfFiles, inference, omitted, written, bytes);
        }
    }

    /* ------------------------------------------------------------------ */
    /* what goes in                                                        */
    /* ------------------------------------------------------------------ */

    private record IngestedFile(long id, String kind, String sourceKind, String device,
                                String storedPath, String laterality, String acquisitionDate,
                                String modalityCode, Integer scanIndex, String sha256,
                                Integer boundEventCrfId, Integer boundStudyEventId) {}

    /** Where a subject's acquisition sits inside the zip. One rule, one place. */
    private static String acquisitionEntryName(IngestedFile f) {
        return "acquisitions/" + f.id() + extensionOf(f.storedPath(), f.kind());
    }

    /**
     * The same names, for a caller that has to reference them before the zip
     * exists — the casebook is rendered first and annotates each auto-ticked
     * value with the file that produced it.
     *
     * <p>Only files that actually resolve are listed, so the casebook never
     * points at an entry the bundle does not contain: a dangling reference is
     * worse than none, because it reads as evidence that is merely misplaced.
     */
    public static Map<Long, String> acquisitionPaths(DataSource ds, int studySubjectId) {
        return acquisitionPaths(ds, studySubjectId, "");
    }

    /** @param prefix the subject's folder inside a dataset bundle, "" for a single-subject one */
    public static Map<Long, String> acquisitionPaths(DataSource ds, int studySubjectId, String prefix) {
        IngestArtifactStore store = new IngestArtifactStore();
        Map<Long, String> out = new LinkedHashMap<>();
        for (IngestedFile f : ingestedFiles(ds, studySubjectId)) {
            if (store.resolveConfined(f.storedPath()).isPresent()) {
                out.put(f.id(), prefix + acquisitionEntryName(f));
            }
        }
        return out;
    }

    /** Every file filed against this subject, newest last so order is stable. */
    private static List<IngestedFile> ingestedFiles(DataSource ds, int studySubjectId) {
        List<IngestedFile> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ii.ingest_item_id, ii.kind, ii.source_kind, "
                             + "       COALESCE(ii.device, ii.source_ae_title), ii.stored_path, "
                             + "       ii.laterality, ii.acquisition_date, im.code, ii.scan_index, "
                             + "       ii.sha256, ii.bound_event_crf_id, ii.bound_study_event_id "
                             + "  FROM ingest_item ii "
                             + "  LEFT JOIN imaging_modality im "
                             + "    ON im.imaging_modality_id = ii.imaging_modality_id "
                             + " WHERE ii.bound_study_subject_id = ? AND ii.status = 'BOUND' "
                             + " ORDER BY ii.received_at, ii.ingest_item_id")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int scan = rs.getInt(9);
                    boolean scanNull = rs.wasNull();
                    int ecrf = rs.getInt(11);
                    boolean ecrfNull = rs.wasNull();
                    int sev = rs.getInt(12);
                    boolean sevNull = rs.wasNull();
                    out.add(new IngestedFile(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), scanNull ? null : scan, rs.getString(10),
                            ecrfNull ? null : ecrf, sevNull ? null : sev));
                }
            }
        } catch (SQLException e) {
            LOG.error("bundle: could not list the subject's files: {}", e.getMessage());
        }
        return out;
    }

    private record CrfFile(int itemDataId, String itemOid, int eventCrfId, String value) {}

    /**
     * CRF items of the FILE type, whose stored value is a path.
     *
     * <p>{@code item_data_type_id = 11} is the heritage FILE type. The value
     * is what the upload wrote there, which is why it is confined before use.
     */
    private static List<CrfFile> crfFileItems(DataSource ds, int studySubjectId) {
        List<CrfFile> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id.item_data_id, i.oc_oid, id.event_crf_id, id.value "
                             + "  FROM item_data id "
                             + "  JOIN item i ON i.item_id = id.item_id "
                             + "  JOIN event_crf ec ON ec.event_crf_id = id.event_crf_id "
                             + " WHERE ec.study_subject_id = ? "
                             + "   AND i.item_data_type_id = 11 "
                             + "   AND COALESCE(id.deleted, false) = false "
                             + "   AND id.value IS NOT NULL AND id.value <> '' "
                             + " ORDER BY id.item_data_id")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CrfFile(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            LOG.error("bundle: could not list the subject's CRF files: {}", e.getMessage());
        }
        return out;
    }

    /**
     * One file the retinal pipeline produced for a job.
     *
     * @param aiOutput whether this is the model's reading of the eye rather
     *                 than a rendering of the eye itself. The distinction is
     *                 the blinding rule: a masked export withholds the AI's
     *                 answer and keeps the unannotated scan, because a
     *                 physician is blinded to the algorithm, not to their
     *                 patient.
     */
    private record RetinalArtifact(long jobId, String task, String name, String path,
                                   boolean aiOutput) {
        boolean isAiOutput() {
            return aiOutput;
        }
    }

    /** Companions the sidecar renders from the volume; not model output. */
    private static final List<String> COMPANIONS =
            List.of("bscan.dcm", "fundus.png", "geometry.json");

    /**
     * Everything the pipeline wrote for this subject's scans.
     *
     * <p>Two sources, because they are stored differently: the per-result mask
     * directory and en-face mask are columns, while the companions are files
     * the sidecar drops beside the job's artifacts under a conventional name.
     */
    private static List<RetinalArtifact> retinalArtifacts(DataSource ds, int studySubjectId) {
        List<RetinalArtifact> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT j.job_id, r.task, r.en_face_mask_path, r.bscan_masks_dir "
                             + "  FROM retinal_inference_job j "
                             + "  JOIN retinal_inference_result r ON r.job_id = j.job_id "
                             + "  LEFT JOIN ingest_item ii ON ii.ingest_item_id = j.ingest_item_id "
                             + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                             + "  LEFT JOIN study_event se ON se.study_event_id = j.study_event_id "
                             + " WHERE COALESCE(ii.bound_study_subject_id, ec.study_subject_id, "
                             + "                se.study_subject_id) = ? "
                             + "   AND j.status = 'done' "
                             + " ORDER BY j.job_id")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long jobId = rs.getLong(1);
                    String task = rs.getString(2);
                    String enFace = rs.getString(3);
                    String masksDir = rs.getString(4);
                    if (enFace != null && !enFace.isBlank()) {
                        out.add(new RetinalArtifact(jobId, task, "en-face-mask" + extOf(enFace),
                                enFace, true));
                    }
                    if (masksDir != null && !masksDir.isBlank()) {
                        collectDirectory(out, jobId, task, masksDir);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.error("bundle: could not list the subject's inference artifacts: {}", e.getMessage());
        }
        return out;
    }

    /**
     * The files in a job's artifact directory.
     *
     * <p>Listed rather than assumed: the sidecar's output has changed shape
     * more than once, and a hard-coded filename list would quietly export
     * nothing after the next change. The companions are recognised by name so
     * they can be kept in a blinded export; everything else in that directory
     * is the model's output.
     */
    private static void collectDirectory(List<RetinalArtifact> out, long jobId, String task,
                                         String dir) {
        Path root = confinedArtifact(dir);
        if (root == null || !Files.isDirectory(root)) return;
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                  .sorted()
                  .forEach(f -> {
                      String name = f.getFileName().toString();
                      out.add(new RetinalArtifact(jobId, task, name, f.toString(),
                              !COMPANIONS.contains(name.toLowerCase(Locale.ROOT))));
                  });
        } catch (IOException e) {
            LOG.warn("bundle: could not list the artifact directory for job {}: {}",
                    jobId, e.getMessage());
        }
    }

    /**
     * An artifact path, resolved under the retinal artifact store and nowhere
     * else. Same reasoning as every other path here: it comes from a database
     * row, so it is checked rather than trusted.
     */
    private static Path confinedArtifact(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Path root = retinalArtifactRoot();
            if (root == null) return null;
            Path candidate = Path.of(value).toAbsolutePath().normalize();
            if (!Files.exists(candidate)) return null;
            return candidate.toRealPath().startsWith(root.toRealPath()) ? candidate : null;
        } catch (Exception notResolvable) {
            return null;
        }
    }

    private static Path retinalArtifactRoot() {
        try {
            String raw = at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources
                    .getField("core.retinalInference.artifactStorePath");
            String root = (raw == null || raw.isBlank())
                    ? "/var/lib/libreclinica/retinal-artifacts"
                    : raw.trim();
            return Path.of(root).toAbsolutePath().normalize();
        } catch (Exception noContext) {
            return Path.of("/var/lib/libreclinica/retinal-artifacts");
        }
    }

    private static String extOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot) : "";
    }

    /* ------------------------------------------------------------------ */
    /* manifest                                                            */
    /* ------------------------------------------------------------------ */

    private static Map<String, Object> manifest(String subjectLabel, String studyOid,
                                                String generatedBy, Policy policy,
                                                List<Map<String, Object>> acquisitions,
                                                List<Map<String, Object>> crfFiles,
                                                List<Map<String, Object>> inference,
                                                List<Omission> omitted,
                                                int filesWritten, long bytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("generatedAt", Instant.now().toString());
        m.put("generatedBy", generatedBy);
        m.put("study", studyOid);
        m.put("subject", subjectLabel);
        m.put("masking", policy.maskAi() ? "ai-withheld" : "none");
        m.put("acquisitions", acquisitions);
        m.put("crfFiles", crfFiles);
        m.put("inference", inference);
        m.put("omitted", omitted.stream()
                .map(o -> Map.of("ref", o.ref(), "reason", o.reason()))
                .toList());
        m.put("fileCount", filesWritten);
        m.put("byteSize", bytes);
        return m;
    }

    private static Map<String, Object> describeAcquisition(IngestedFile f) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ingestItemId", f.id());
        e.put("kind", f.kind());
        e.put("sourceKind", f.sourceKind());
        if (f.device() != null) e.put("device", f.device());
        if (f.modalityCode() != null) e.put("modalityCode", f.modalityCode());
        if (f.laterality() != null) e.put("laterality", f.laterality());
        if (f.acquisitionDate() != null) e.put("acquisitionDate", f.acquisitionDate());
        if (f.scanIndex() != null) e.put("scanIndex", f.scanIndex());
        if (f.sha256() != null) e.put("sha256", f.sha256());
        if (f.boundStudyEventId() != null) e.put("studyEventId", f.boundStudyEventId());
        if (f.boundEventCrfId() != null) e.put("eventCrfId", f.boundEventCrfId());
        return e;
    }

    private static Map<String, Object> describeCrfFile(CrfFile f) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("itemDataId", f.itemDataId());
        e.put("itemOid", f.itemOid());
        e.put("eventCrfId", f.eventCrfId());
        return e;
    }

    /* ------------------------------------------------------------------ */
    /* writing                                                             */
    /* ------------------------------------------------------------------ */

    private static void writeBytes(ZipOutputStream zos, String name, byte[] body) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(body);
        zos.closeEntry();
    }

    /** Streams a file in without it ever being a byte[]. */
    private static long copyInto(ZipOutputStream zos, String name, Path source) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (storeAsIs(name)) {
            long size = Files.size(source);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(size);
            entry.setCompressedSize(size);
            entry.setCrc(crc32(source));
        }
        zos.putNextEntry(entry);
        long copied = Files.copy(source, zos);
        zos.closeEntry();
        return copied;
    }

    private static boolean storeAsIs(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return STORE_AS_IS.stream().anyMatch(lower::endsWith);
    }

    /** STORED entries need the CRC up front, so the file is read twice. */
    private static long crc32(Path p) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }

    /* ------------------------------------------------------------------ */
    /* confinement                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * A CRF file, resolved under the CRF file store and nowhere else.
     *
     * <p>The value comes from {@code item_data}, which a bulk import can
     * write. Compared after {@code toRealPath} on both sides so a symlink
     * inside the store cannot point out of it.
     */
    private static Path confinedCrfFile(String value) {
        try {
            Path root = crfFileRoot();
            if (root == null) return null;
            Path candidate = Path.of(value).isAbsolute()
                    ? Path.of(value)
                    : root.resolve(value);
            candidate = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(candidate)) return null;
            return candidate.toRealPath().startsWith(root.toRealPath()) ? candidate : null;
        } catch (Exception notResolvable) {
            return null;
        }
    }

    private static Path crfFileRoot() {
        try {
            String raw = at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources
                    .getField("filePath");
            if (raw == null || raw.isBlank()) return null;
            return Path.of(raw.trim()).toAbsolutePath().normalize();
        } catch (Exception noContext) {
            return null;
        }
    }

    /** The stored name, stripped of anything that could be a path. */
    private static String safeName(Path p) {
        String name = p.getFileName().toString();
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String extensionOf(String storedPath, String kind) {
        int dot = storedPath == null ? -1 : storedPath.lastIndexOf('.');
        if (dot > 0 && dot < storedPath.length() - 1) {
            String ext = storedPath.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{1,8}")) return ext;
        }
        return switch (kind == null ? "" : kind) {
            case "e2e" -> ".e2e";
            case "dicom" -> ".dcm";
            default -> "";
        };
    }

    private static Long sizeOrNull(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return null;
        }
    }
}
