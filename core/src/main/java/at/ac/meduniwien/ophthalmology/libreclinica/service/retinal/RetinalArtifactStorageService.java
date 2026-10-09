/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * DR-022 — persists artifact bytes returned by the remote GPU sidecar to
 * the institutional artifact store.
 *
 * <p>Each call writes the artifacts under
 * {@code ${core.retinalInference.artifactStorePath}/<jobUuid>/<artifact-name>}
 * and returns the parent directory path so the
 * {@code retinal_inference_result.bscan_masks_dir} column lands the operator
 * on a browsable directory.
 *
 * <p>Filename collisions inside the same job are unexpected (the sidecar uses
 * basenames the runner picked), but if one occurs the write is atomic
 * (REPLACE_EXISTING) — the latest call wins.
 */
@Component
public class RetinalArtifactStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalArtifactStorageService.class);

    /** Default artifact store path when the property is blank. */
    public static final String DEFAULT_STORE_PATH = "/var/lib/libreclinica/retinal-artifacts";

    /**
     * Persist every artifact in {@code result} under a fresh per-job directory
     * and return the absolute directory path.
     *
     * <p>The directory is named after a randomly generated UUID rather than
     * the job id — protects against a controller that double-calls the
     * service (unlikely but cheap insurance) and matches the rest of the
     * institutional artifact-store conventions (UUID per upload).
     */
    public Path persist(long jobId, RemoteRunResult result) throws IOException {
        String storeRoot = storePath();
        Path jobDir = Path.of(storeRoot, UUID.randomUUID().toString());
        Files.createDirectories(jobDir);
        return persistInto(jobId, result.artifacts(), jobDir);
    }

    /** Persist into a specific directory — exposed for tests. */
    Path persistInto(long jobId,
                     List<RemoteRunResult.Artifact> artifacts,
                     Path jobDir) throws IOException {
        Files.createDirectories(jobDir);
        int written = 0;
        for (RemoteRunResult.Artifact a : artifacts) {
            if (a == null || a.name() == null || a.content() == null) continue;
            // Defence-in-depth: never let a runner name escape the job dir.
            String safeName = Path.of(a.name()).getFileName().toString();
            Path target = jobDir.resolve(safeName);
            Path tmp = Files.createTempFile(jobDir, safeName + ".", ".part");
            Files.write(tmp, a.content(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            written++;
        }
        LOG.info("Persisted {} retinal artifact(s) for job {} at {}", written, jobId, jobDir);
        return jobDir;
    }

    /** Resolve the artifact store base from the institutional config. */
    protected String storePath() {
        try {
            String raw = CoreResources.getField("core.retinalInference.artifactStorePath");
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable in some test contexts.
        }
        return DEFAULT_STORE_PATH;
    }

    /**
     * Resolve the per-source-scan bscan store base from
     * {@code core.retinalInference.bscanStorePath}, falling back to
     * {@code <artifactStorePath>/bscans} so a single config key suffices when
     * an operator hasn't split the two locations.
     *
     * <p>This must match {@code RETINAL_INFERENCE_BSCAN_STORE} on the
     * preprocess sidecar — the sidecar writes the companion files
     * ({@code bscan.dcm}, {@code fundus.png}, {@code geometry.json}) under
     * {@code <bscanStorePath>/<e2eUuid>/}; the resolvers below read them back.
     */
    protected String bscanStorePath() {
        try {
            String raw = CoreResources.getField("core.retinalInference.bscanStorePath");
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable in some test contexts.
        }
        return Path.of(storePath(), "bscans").toString();
    }

    /** Companion file: PHI-redacted DICOM the preprocess sidecar wrote. */
    public Path resolveBscanDcm(String e2eUuid) throws IOException {
        return resolveCompanion(e2eUuid, "bscan.dcm", -1);
    }

    /** Companion file: SLO en-face PNG the preprocess sidecar extracted. */
    public Path resolveFundus(String e2eUuid) throws IOException {
        return resolveCompanion(e2eUuid, "fundus.png", -1);
    }

    /** Companion file: fundus + bscan registration JSON. */
    public Path resolveGeometry(String e2eUuid) throws IOException {
        return resolveCompanion(e2eUuid, "geometry.json", -1);
    }

    /**
     * Multi-volume-aware overloads. The preprocess sidecar writes the
     * companions of scan index 0 to {@code <key>/} and those of index
     * {@code i > 0} to {@code <key>/scan-<i>/}; see
     * {@link RetinalArtifactKey#scanDir}.
     */
    public Path resolveBscanDcm(String e2eUuid, int scanIndex) throws IOException {
        return resolveCompanion(e2eUuid, "bscan.dcm", scanIndex);
    }
    public Path resolveFundus(String e2eUuid, int scanIndex) throws IOException {
        return resolveCompanion(e2eUuid, "fundus.png", scanIndex);
    }
    public Path resolveGeometry(String e2eUuid, int scanIndex) throws IOException {
        return resolveCompanion(e2eUuid, "geometry.json", scanIndex);
    }

    /**
     * 2026-06-26 — corrections subdir (one CSV per edited layer).
     *
     * <p>{@code bscanMasksDir} is the per-job artifact directory the
     * {@link #persist} call wrote ({@code <store>/<jobUuid>/}). Corrected
     * layers land at {@code <bscanMasksDir>/corrections/<csvBaseName>}. The
     * basename mirrors the original IOWA CSV name (e.g. {@code 001-ILM.csv})
     * so {@link SegmentationEnvelopeLoader} can substitute file-by-file
     * without touching the rest of the layers_csv directory.
     *
     * <p>Atomic via tempfile + REPLACE_EXISTING move, same as
     * {@link #persistInto}. Returns the relative path from
     * {@code bscanMasksDir} ({@code "corrections/001-ILM.csv"}) for storage
     * in {@code retinal_inference_correction.csv_relpath}.
     */
    public String persistCorrection(Path bscanMasksDir, String csvBaseName, byte[] csvBytes)
            throws IOException {
        if (bscanMasksDir == null) throw new IllegalArgumentException("bscanMasksDir is required");
        if (csvBaseName == null || csvBaseName.isBlank()) {
            throw new IllegalArgumentException("csvBaseName is required");
        }
        if (csvBytes == null) throw new IllegalArgumentException("csvBytes is required");
        // Defence-in-depth: the basename is operator-influenced (via the
        // layer_label → filename mapping in the controller). Reject any
        // path component or traversal segment outright — a buggy caller
        // that tries to write into a parent dir surfaces as a bad-request
        // instead of silently re-rooting under corrections/. The IOWA
        // CSV names never contain '/' or '..'; rejecting them is safe.
        if (csvBaseName.contains("/") || csvBaseName.contains("\\")
                || csvBaseName.contains("..")) {
            throw new IllegalArgumentException(
                    "csvBaseName must be a plain basename: " + csvBaseName);
        }
        String safeName = Path.of(csvBaseName).getFileName().toString();
        if (!safeName.matches("[A-Za-z0-9._()# -]+")) {
            throw new IllegalArgumentException("csvBaseName has disallowed chars: " + safeName);
        }
        Path corrDir = bscanMasksDir.resolve("corrections");
        Files.createDirectories(corrDir);
        Path target = corrDir.resolve(safeName);
        Path tmp = Files.createTempFile(corrDir, safeName + ".", ".part");
        Files.write(tmp, csvBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        LOG.info("Persisted retinal correction {} under {}", safeName, corrDir);
        return "corrections/" + safeName;
    }

    /**
     * 2026-06-26 — drop a previously-written correction CSV. No-op when
     * the file is already gone. Used by the
     * {@code DELETE /retinal-jobs/{id}/segmentation/corrections/{layerIndex}}
     * endpoint to restore the original AI output for one layer.
     */
    public void deleteCorrection(Path bscanMasksDir, String csvBaseName) throws IOException {
        if (bscanMasksDir == null || csvBaseName == null || csvBaseName.isBlank()) return;
        String safeName = Path.of(csvBaseName).getFileName().toString();
        Path target = bscanMasksDir.resolve("corrections").resolve(safeName);
        Files.deleteIfExists(target);
    }

    private Path resolveCompanion(String e2eUuid, String name, int scanIndex) throws IOException {
        if (e2eUuid == null || e2eUuid.isBlank()) {
            throw new IllegalArgumentException("e2eUuid required to resolve " + name);
        }
        // Defence-in-depth: the UUID must look like a UUID (no path traversal).
        if (!e2eUuid.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("e2eUuid contains disallowed chars: " + e2eUuid);
        }
        // DR-039 — the sidecar's layout, and only that: index 0 in the
        // key's directory, index i > 0 in scan-<i>/ (RetinalArtifactKey).
        // This used to try scan-(i+1)/, then scan-1/, then the root, on the
        // belief that the sidecar counted from 1. It never did
        // (retinal_inference/api/preprocess.py since scan_index existed), so
        // scan 0 of a two-volume .e2e was shown with scan 1's companions.
        // Uploads from before scan_index existed have everything at the root,
        // which is where index 0 is looked for. -1 = "don't know" = root.
        Path base = Path.of(bscanStorePath(), e2eUuid);
        if (scanIndex > 0) {
            Path inSubdir = RetinalArtifactKey.scanDir(Path.of(bscanStorePath()), e2eUuid, scanIndex).resolve(name);
            if (!Files.exists(inSubdir)) {
                throw new NoSuchFileException(inSubdir.toString());
            }
            return inSubdir;
        }
        Path direct = base.resolve(name);
        if (!Files.exists(direct)) {
            throw new NoSuchFileException(direct.toString());
        }
        return direct;
    }
}