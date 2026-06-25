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
            // Write atomically — create + truncate + replace existing.
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
     * 2026-06-19 — multi-volume-aware overloads. The preprocess
     * sidecar writes companion files for multi-volume {@code .e2e}
     * uploads under per-scan subdirectories named {@code scan-{N+1}/}
     * (1-indexed, sidecar convention) instead of the legacy root
     * layout {@code <e2eUuid>/<name>}. The resolvers below look in
     * {@code scan-{scanIndex+1}/} first and fall back to the root,
     * so legacy single-volume uploads + tests against the older
     * sidecar continue to work.
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

    private Path resolveCompanion(String e2eUuid, String name, int scanIndex) throws IOException {
        if (e2eUuid == null || e2eUuid.isBlank()) {
            throw new IllegalArgumentException("e2eUuid required to resolve " + name);
        }
        // Defence-in-depth: the UUID must look like a UUID (no path traversal).
        if (!e2eUuid.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("e2eUuid contains disallowed chars: " + e2eUuid);
        }
        // 2026-06-19 — multi-volume layout fallback ladder. The
        // preprocess sidecar's exact subdir naming convention isn't
        // fully consistent: for some uploads it honours the
        // {@code scan_index} form-field and writes to
        // {@code scan-{scanIndex+1}/}; for others (notably the
        // 2026-06-19 smoke run on jobs 49–51) it writes to
        // {@code scan-1/} regardless of the requested index. Try the
        // matching subdir first, then the conservative {@code scan-1/}
        // single-scan fallback, then the legacy root layout. {@code -1}
        // skips the subdir probes entirely (IT tests, parked-list).
        Path base = Path.of(bscanStorePath(), e2eUuid);
        if (scanIndex >= 0) {
            Path withSubdir = base.resolve("scan-" + (scanIndex + 1)).resolve(name);
            if (Files.exists(withSubdir)) return withSubdir;
            // Sidecar quirk: writes scan-1/ even when scan_index > 0.
            if (scanIndex != 0) {
                Path scan1 = base.resolve("scan-1").resolve(name);
                if (Files.exists(scan1)) return scan1;
            }
        }
        Path direct = base.resolve(name);
        if (!Files.exists(direct)) {
            throw new NoSuchFileException(direct.toString());
        }
        return direct;
    }
}