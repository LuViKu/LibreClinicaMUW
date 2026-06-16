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
}