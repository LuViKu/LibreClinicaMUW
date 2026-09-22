/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * P3.0 — where an ingested file goes, and how a stored path is read back safely.
 *
 * <p>Four controllers each carried their own copy of "which directory is this
 * kind of file stored in", reading a different configuration key and falling
 * back to a different default. Reading a file back was worse: the stored path
 * comes out of the database and was handed to {@code Files.copy} directly, so
 * confinement depended on every call site remembering to check — and the check
 * existed in one place out of several.
 *
 * <p>This is the single answer to both questions.
 *
 * <p><strong>Existing files do not move.</strong> New writes land under
 * {@code core.ingest.storePath}, in a subdirectory per kind. The legacy roots
 * stay readable, so paths already in the database keep resolving; nothing is
 * migrated on disk, which means a rollback loses nothing.
 */
public class IngestArtifactStore {

    private static final Logger LOG = LoggerFactory.getLogger(IngestArtifactStore.class);

    /** The unified root for new writes. */
    public static final String CONFIG_KEY_STORE_PATH = "core.ingest.storePath";
    public static final String DEFAULT_STORE_PATH = "/var/lib/libreclinica/ingest";

    /** Legacy roots, readable so paths already recorded keep working. */
    public static final String LEGACY_KEY_DICOM = "core.dicom.ingest.storePath";
    public static final String LEGACY_DEFAULT_DICOM = "/var/lib/libreclinica/dicom-ingest";
    public static final String LEGACY_KEY_E2E = "core.retinalInference.e2eUploadsPath";
    public static final String LEGACY_DEFAULT_E2E = "/var/lib/libreclinica/e2e-uploads";

    /** What sort of file this is — also the subdirectory it is written to. */
    public enum Kind {
        E2E("e2e", ".e2e"),
        DICOM("dicom", ".dcm"),
        IMAGE("image", ""),
        OTHER("other", "");

        private final String dir;
        private final String defaultExtension;

        Kind(String dir, String defaultExtension) {
            this.dir = dir;
            this.defaultExtension = defaultExtension;
        }

        public String dir() {
            return dir;
        }

        public static Kind of(String raw) {
            if (raw == null) return OTHER;
            for (Kind k : values()) {
                if (k.name().equalsIgnoreCase(raw.trim())) return k;
            }
            return OTHER;
        }
    }

    /** What was written: where it went, its digest, and how big it is. */
    public record Stored(Path path, String sha256, long byteSize) {}

    /**
     * Roots beyond the configured ones that this instance also accepts.
     *
     * <p>Exists for callers that are handed a root explicitly rather than
     * reading it from configuration — a test with a temporary directory, or a
     * service constructed with an override. It widens what may be read; it
     * never widens what an unconfigured caller may read.
     */
    private final List<Path> extraRoots;

    public IngestArtifactStore() {
        this(List.of());
    }

    public IngestArtifactStore(List<Path> extraRoots) {
        this.extraRoots = extraRoots == null ? List.of() : List.copyOf(extraRoots);
    }

    /**
     * Streams an upload to disk, hashing it on the way through.
     *
     * <p>The digest is computed during the copy rather than by re-reading the
     * file: an OCT volume runs to a couple of hundred megabytes, and the
     * original code held the whole thing in heap before writing it.
     *
     * @param originalName only its extension is used; the stored name is a UUID,
     *                     because an operator-supplied filename is untrusted text
     *                     and must never become a path
     */
    public Stored store(Kind kind, InputStream in, String originalName) throws IOException {
        Path dir = writeRoot().resolve(kind.dir());
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + extensionFor(kind, originalName));

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
        long size;
        try (DigestInputStream hashing = new DigestInputStream(in, digest)) {
            size = Files.copy(hashing, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Stored(target, hex(digest.digest()), size);
    }

    /**
     * Resolves a stored path for reading, refusing anything outside a known root.
     *
     * <p>The path comes from the database. Handing it to the filesystem without
     * this check makes every reader one bad row away from serving an arbitrary
     * file, and the row can be written by an ingest path that does not
     * authenticate.
     *
     * @return the resolved file, or empty when it escapes every root, does not
     *         exist, or is not a regular file
     */
    public java.util.Optional<Path> resolveConfined(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) return java.util.Optional.empty();
        Path candidate;
        try {
            candidate = Path.of(storedPath).toAbsolutePath().normalize();
        } catch (Exception notAPath) {
            return java.util.Optional.empty();
        }
        if (!Files.isRegularFile(candidate)) return java.util.Optional.empty();

        for (Path root : readRoots()) {
            if (isUnder(candidate, root)) return java.util.Optional.of(candidate);
        }
        // Deliberately not logging the path: it can carry an operator-supplied
        // filename fragment.
        LOG.warn("refused to read a stored file that lies outside every ingest root");
        return java.util.Optional.empty();
    }

    /** True when the candidate really lies under the root, symlinks resolved. */
    private static boolean isUnder(Path candidate, Path root) {
        try {
            Path realRoot = root.toAbsolutePath().normalize().toRealPath();
            return candidate.toRealPath().startsWith(realRoot);
        } catch (IOException rootMissing) {
            // A root that does not exist yet cannot contain anything, but a
            // lexical match still beats treating every path as acceptable.
            return candidate.startsWith(root.toAbsolutePath().normalize());
        }
    }

    /** Where new files are written. */
    public Path writeRoot() {
        return Path.of(cfg(CONFIG_KEY_STORE_PATH, DEFAULT_STORE_PATH));
    }

    /**
     * Every root a stored path may legitimately lie under: the unified one plus
     * the two the previous layout used.
     */
    public List<Path> readRoots() {
        List<Path> roots = new java.util.ArrayList<>(3 + extraRoots.size());
        roots.add(writeRoot());
        roots.add(Path.of(cfg(LEGACY_KEY_DICOM, LEGACY_DEFAULT_DICOM)));
        roots.add(Path.of(cfg(LEGACY_KEY_E2E, LEGACY_DEFAULT_E2E)));
        roots.addAll(extraRoots);
        return List.copyOf(roots);
    }

    private static String extensionFor(Kind kind, String originalName) {
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot > 0 && dot < originalName.length() - 1) {
                String ext = originalName.substring(dot).toLowerCase(Locale.ROOT);
                // Only a plain extension, never a path fragment.
                if (ext.matches("\\.[a-z0-9]{1,8}")) return ext;
            }
        }
        return kind.defaultExtension;
    }

    private static String cfg(String key, String fallback) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception noContext) {
            // CoreResources is not initialised in some test paths.
        }
        return fallback;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
