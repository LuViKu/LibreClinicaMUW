/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 2026-06-22 — load a CRF-version-style binary segmentation envelope
 * directly from the persisted runner artifact dir.
 *
 * <p>Architectural intent: the SPA's B-scan viewer no longer
 * consumes per-slice PNGs. It fetches a single binary envelope
 * via {@code GET /api/v1/retinal-jobs/{id}/segmentation} and
 * renders the per-A-scan overlay client-side on a 2D canvas. This
 * loader translates each task's canonical artifact
 * ({@code fluidseg.npz} for fluid, {@code 001-RPEL.csv} for GA,
 * the layer-interface CSVs for ONL/PR) into a uniform
 * {@link SegmentationEnvelope} shape so the SPA decoder is task-
 * agnostic.
 *
 * <p>Implementation notes:
 *
 * <ul>
 *   <li><b>fluid</b>: open {@code fluidseg.npz} (zip), find the
 *       {@code segmentation.npy} entry, parse the numpy v1.0 header,
 *       stream the raw uint8 data bytes verbatim.</li>
 *   <li><b>ga / onl / pr</b>: stub-only in this PR — returns
 *       {@code null} so the controller surfaces 501. The CSV
 *       loaders land in a follow-up alongside the SPA renderers
 *       for surface_y / binary_2d kinds.</li>
 * </ul>
 */
public final class SegmentationEnvelopeLoader {

    private static final Logger LOG = LoggerFactory.getLogger(SegmentationEnvelopeLoader.class);

    /** Numpy .npy magic — first 6 bytes of every file. */
    private static final byte[] NPY_MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};

    private SegmentationEnvelopeLoader() {}

    public record SegmentationEnvelope(
            String kind,
            String dtype,
            int[] shape,
            List<String> labels,
            String task,
            byte[] data
    ) {}

    /**
     * Load the segmentation envelope for the job's task from
     * {@code bscanMasksDir}, or {@code null} when the task isn't
     * yet wired (controller maps null → 501 Not Implemented).
     */
    public static SegmentationEnvelope load(String task, Path bscanMasksDir) throws IOException {
        if (task == null || bscanMasksDir == null) return null;
        switch (task) {
            case "fluid" -> {
                return loadFluid(bscanMasksDir);
            }
            case "ga", "onl", "pr" -> {
                LOG.debug("segmentation envelope: task '{}' not yet implemented", task);
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** fluid: read {@code fluidseg.npz/segmentation.npy} and emit a uint8 volume envelope. */
    private static SegmentationEnvelope loadFluid(Path bscanMasksDir) throws IOException {
        Path npz = bscanMasksDir.resolve("fluidseg.npz");
        if (!Files.isRegularFile(npz)) {
            LOG.warn("fluidseg.npz missing under {}", bscanMasksDir);
            return null;
        }
        try (InputStream raw = Files.newInputStream(npz);
             ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.getName().equals("segmentation.npy")) continue;
                NpyHeader hdr = parseNpyHeader(zin);
                if (!"uint8".equals(hdr.dtype) && !"|u1".equals(hdr.rawDescr)) {
                    LOG.warn("fluidseg.npz/segmentation.npy: expected uint8, got {}", hdr.rawDescr);
                }
                if (hdr.shape.length != 3) {
                    LOG.warn("fluidseg.npz/segmentation.npy: expected 3D shape, got {} dims",
                            hdr.shape.length);
                }
                // Stream the remaining body bytes into memory. Typical
                // volume size: 97 × 496 × 512 = ~24 MB raw; fits.
                ByteArrayOutputStream sink = new ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = zin.read(buf)) > 0) sink.write(buf, 0, n);
                return new SegmentationEnvelope(
                        "volume",
                        "uint8",
                        hdr.shape,
                        List.of("IRF", "SRF", "PED"),
                        "fluid",
                        sink.toByteArray()
                );
            }
        }
        LOG.warn("fluidseg.npz at {} did not contain segmentation.npy entry", npz);
        return null;
    }

    /**
     * Parse the numpy v1.0 .npy header off the stream cursor and
     * leave the stream positioned at the first data byte. Tolerates
     * v1 and v2 headers (v2 uses a 4-byte length instead of 2 —
     * indicated by the version bytes after the magic).
     */
    static NpyHeader parseNpyHeader(InputStream in) throws IOException {
        byte[] magic = readExact(in, NPY_MAGIC.length);
        for (int i = 0; i < NPY_MAGIC.length; i++) {
            if (magic[i] != NPY_MAGIC[i]) {
                throw new IOException("not a .npy stream: magic mismatch");
            }
        }
        int major = in.read();
        int minor = in.read();
        if (major < 0 || minor < 0) {
            throw new IOException("npy header truncated at version bytes");
        }
        int headerLen;
        if (major == 1) {
            byte[] lenBytes = readExact(in, 2);
            headerLen = (lenBytes[0] & 0xff) | ((lenBytes[1] & 0xff) << 8);
        } else if (major == 2 || major == 3) {
            byte[] lenBytes = readExact(in, 4);
            headerLen =
                    (lenBytes[0] & 0xff)
                            | ((lenBytes[1] & 0xff) << 8)
                            | ((lenBytes[2] & 0xff) << 16)
                            | ((lenBytes[3] & 0xff) << 24);
        } else {
            throw new IOException("unsupported npy version " + major + "." + minor);
        }
        byte[] header = readExact(in, headerLen);
        String headerStr = new String(header, java.nio.charset.StandardCharsets.US_ASCII).trim();
        // Header is a Python literal-ish dict, e.g.
        // {'descr': '|u1', 'fortran_order': False, 'shape': (97, 496, 512), }
        return new NpyHeader(
                extractField(headerStr, "descr"),
                extractDtype(extractField(headerStr, "descr")),
                extractShape(headerStr),
                headerStr.contains("'fortran_order': True")
        );
    }

    private static String extractField(String header, String name) {
        // Match 'name': 'value' OR "name": "value".
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "['\"]" + name + "['\"]\\s*:\\s*['\"]([^'\"]*)['\"]");
        java.util.regex.Matcher m = p.matcher(header);
        if (m.find()) return m.group(1);
        return "";
    }

    /** Map a numpy descr token like {@code '|u1'} or {@code '<f4'} to a friendlier name. */
    private static String extractDtype(String descr) {
        if (descr == null) return "";
        return switch (descr) {
            case "|u1", "<u1", ">u1", "u1" -> "uint8";
            case "<f4", ">f4" -> "float32";
            case "<i4", ">i4" -> "int32";
            case "<u4", ">u4" -> "uint32";
            default -> descr;
        };
    }

    private static int[] extractShape(String header) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "['\"]shape['\"]\\s*:\\s*\\(([^)]*)\\)");
        java.util.regex.Matcher m = p.matcher(header);
        if (!m.find()) return new int[0];
        String body = m.group(1).trim();
        if (body.isEmpty()) return new int[0];
        String[] parts = body.split(",");
        java.util.List<Integer> dims = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                dims.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignore) {
                /* skip */
            }
        }
        int[] out = new int[dims.size()];
        for (int i = 0; i < dims.size(); i++) out[i] = dims.get(i);
        return out;
    }

    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("truncated stream at byte " + off + " of " + n);
            off += r;
        }
        return buf;
    }

    /** Parsed .npy header — package-private so the controller can read shape/dtype if needed. */
    record NpyHeader(
            String rawDescr,
            String dtype,
            int[] shape,
            boolean fortranOrder
    ) {}
}
