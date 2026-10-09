/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * DR-039 — where a scan's preprocess companions live, named once.
 *
 * <p>The preprocess sidecar writes {@code bscan.dcm} and {@code geometry.json}
 * (and, for an {@code .e2e}, {@code fundus.png}) under
 * {@code <bscanStorePath>/<key>/}. The key used to be "the stored file's
 * name without {@code .e2e}", spelled out separately in the client, the job
 * endpoints, the artifact endpoint and the CRT service. A DICOM OCT volume is
 * stored as {@code <uuid>.dcm}, or under whatever path the C-STORE hand-off
 * chose, so that rule does not give it a key the sidecar accepts (a strict
 * lower-case UUID).
 *
 * <ul>
 *   <li>{@code <name>.e2e}: {@code <name>}, exactly as before, so every
 *       artifact already on disk keeps its directory.</li>
 *   <li>Any other path: a name-based UUID of the normalised path. The same
 *       stored file always gets the same key, in every process, and the key
 *       is a lower-case UUID.</li>
 * </ul>
 *
 * <p>The scan-index layout is the sidecar's: index 0 in the key's directory
 * itself, index {@code i > 0} in {@code scan-<i>/}
 * ({@code retinal_inference/api/preprocess.py}).
 */
public final class RetinalArtifactKey {

    /** Salt, so a key never collides with a name-based UUID minted elsewhere for a path. */
    private static final String NAMESPACE = "muw-retinal-artifact-key:";

    private RetinalArtifactKey() {}

    /**
     * The companion-directory key of a stored scan file, or null when there is
     * no usable path.
     */
    public static String of(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) return null;
        Path p;
        try {
            p = Path.of(storedPath);
        } catch (InvalidPathException bad) {
            return null;
        }
        Path name = p.getFileName();
        if (name == null) return null;
        String base = name.toString();
        if (base.toLowerCase(Locale.ROOT).endsWith(".e2e")) {
            return base.substring(0, base.length() - 4);
        }
        String normalised = p.normalize().toString();
        return UUID.nameUUIDFromBytes((NAMESPACE + normalised).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The sub-directory of the key's directory that holds one scan index:
     * empty for index 0 (and for a negative "don't know"), {@code scan-<i>}
     * otherwise.
     */
    public static String scanSubdir(int scanIndex) {
        return scanIndex > 0 ? "scan-" + scanIndex : "";
    }

    /** {@code <base>/<key>/<scanSubdir>}. */
    public static Path scanDir(Path base, String key, int scanIndex) {
        Path dir = base.resolve(key);
        String sub = scanSubdir(scanIndex);
        return sub.isEmpty() ? dir : dir.resolve(sub);
    }
}
