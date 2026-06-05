/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crf;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * Phase E.6 -- store CRF item-file uploads on disk.
 *
 * <p>Storage layout mirrors the legacy {@code UploadFileServlet} pattern
 * so the read-back path can keep using the absolute path written into
 * {@code item_data.value}:
 *
 * <pre>
 * &lt;attached_file_location&gt;/&lt;crfOid&gt;/&lt;crfVersionOid&gt;/&lt;sha1+name&gt;
 * </pre>
 *
 * <p>Validation runs at the service boundary:
 * <ul>
 *   <li>{@link #checkSize(MultipartFile)} -- server-side enforcement of
 *       {@code crf.file.maxBytes}. The SPA also pre-checks, but the
 *       server is the source of truth.</li>
 *   <li>{@link #checkExtension(MultipartFile)} -- server-side check of
 *       {@code crf.file.extensions} (csv allowlist).</li>
 * </ul>
 *
 * <p>The service is stateless + injectable; the controller drives the
 * happy-path. The on-disk write uses {@code Files.copy} with
 * {@code REPLACE_EXISTING} -- a duplicate sha1 prefix is rare but the
 * caller (rather than the storage layer) is the one with audit context
 * to decide whether to over-write or fail. Today the controller follows
 * a "replace silently" policy and writes a separate item_data_file_*
 * audit row.
 */
@Service
public class CrfFileStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(CrfFileStorageService.class);

    /** Default 50 MiB, mirrors {@code EventCrfsApiController}. */
    public static final long DEFAULT_MAX_BYTES = 52_428_800L;
    /** Default extension allowlist. */
    public static final String DEFAULT_EXTENSIONS = "pdf,jpg,jpeg,png,tif,tiff";

    public long maxBytes() {
        try {
            String raw = CoreResources.getField("crf.file.maxBytes");
            if (raw == null || raw.isBlank()) return DEFAULT_MAX_BYTES;
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_BYTES;
        } catch (Exception e) {
            return DEFAULT_MAX_BYTES;
        }
    }

    public Set<String> allowedExtensions() {
        String raw;
        try {
            raw = CoreResources.getField("crf.file.extensions");
            if (raw == null || raw.isBlank()) raw = DEFAULT_EXTENSIONS;
        } catch (Exception e) {
            raw = DEFAULT_EXTENSIONS;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Pretty-printable representation of {@link #allowedExtensions()}. */
    public String allowedExtensionsCsv() {
        List<String> exts = allowedExtensions().stream().sorted().toList();
        return String.join(",", exts);
    }

    /** Base directory; falls back to {@code filePath + attached_files}. */
    public Path baseDir() {
        String raw = safe("attached_file_location");
        if (raw == null || raw.isBlank()) {
            String fp = safe("filePath");
            if (fp == null) fp = "";
            return Paths.get(fp, "attached_files");
        }
        return Paths.get(raw);
    }

    /**
     * Validate {@code file.size}. Returns {@code false} when the upload
     * is too big; controller surfaces a 413.
     */
    public boolean checkSize(MultipartFile file) {
        if (file == null) return false;
        long cap = maxBytes();
        return cap <= 0 || file.getSize() <= cap;
    }

    /**
     * Validate {@code file.originalFilename}'s extension is on the
     * allowlist. An empty allowlist disables the check. Case-insensitive.
     */
    public boolean checkExtension(MultipartFile file) {
        if (file == null) return false;
        Set<String> allowed = allowedExtensions();
        if (allowed.isEmpty()) return true;
        String name = file.getOriginalFilename();
        if (name == null) return false;
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) return false;
        String ext = name.substring(idx + 1).toLowerCase(Locale.ROOT);
        return allowed.contains(ext);
    }

    /** Reject executables no matter what (defence-in-depth). */
    public boolean isPotentiallyExecutable(MultipartFile file) {
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".sh")
                || lower.endsWith(".jsp") || lower.endsWith(".php");
    }

    /**
     * Persist {@code file} on disk under
     * {@code baseDir/crfOid/crfVersionOid/}. The on-disk filename is the
     * sha1 hex prefix (length 8) concatenated with the original filename
     * to keep the audit trail readable and avoid collisions across
     * uploads with the same filename.
     *
     * @return the absolute path written to disk
     */
    public Path store(String crfOid, String crfVersionOid, MultipartFile file) throws IOException {
        Path dir = baseDir()
                .resolve(sanitiseSegment(crfOid))
                .resolve(sanitiseSegment(crfVersionOid));
        Files.createDirectories(dir);
        String origName = file.getOriginalFilename() == null ? "upload.bin"
                : sanitiseFilename(file.getOriginalFilename());
        String sha1Prefix = sha1HexPrefix(file);
        Path target = dir.resolve(sha1Prefix + "-" + origName);
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toAbsolutePath();
    }

    /**
     * Delete the on-disk file at {@code absolutePath} if it sits inside
     * {@link #baseDir()}; defensive against a corrupted item_data.value
     * pointing outside the allowed directory.
     */
    public boolean delete(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return false;
        Path target = Paths.get(absolutePath).toAbsolutePath().normalize();
        Path root = baseDir().toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            LOG.warn("Refusing to delete file outside base dir: {} vs {}", target, root);
            return false;
        }
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            LOG.warn("Failed to delete {}: {}", target, e.getMessage());
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static String sha1HexPrefix(MultipartFile file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = new DigestInputStream(file.getInputStream(), md)) {
                byte[] buf = new byte[8192];
                while (in.read(buf) >= 0) {
                    // streaming digest only
                }
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** Strip path separators so a malicious OID can't break out of the base dir. */
    static String sanitiseSegment(String s) {
        if (s == null || s.isBlank()) return "_";
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    /** Strip path separators + control chars; preserve the readable basename. */
    static String sanitiseFilename(String s) {
        // Drop path separators + control chars; reject ".." sequences.
        // Keep dots otherwise so the extension survives for the
        // controller's response payload + the on-disk audit.
        String stripped = s.replace("/", "_").replace("\\", "_")
                .replace("..", "_")
                .replaceAll("\\p{Cntrl}+", "");
        if (stripped.isBlank()) return "upload.bin";
        return stripped.length() > 200 ? stripped.substring(0, 200) : stripped;
    }

    private static String safe(String key) {
        try { return CoreResources.getField(key); }
        catch (Exception e) { return null; }
    }
}
