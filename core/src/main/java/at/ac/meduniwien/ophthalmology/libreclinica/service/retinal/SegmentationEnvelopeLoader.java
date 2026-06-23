/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
            case "ga" -> {
                return loadGa(bscanMasksDir);
            }
            case "onl" -> {
                // ONL is bounded by OPL-HFL (upper) + BMEIS (lower);
                // emit both surfaces so the operator can read the layer
                // thickness from the gap between the two polylines.
                return loadSurfacePair(
                        bscanMasksDir,
                        "onl",
                        new String[]{"OPL-HFL", "BMEIS"},
                        new String[]{"*OPL-HFL*.csv", "*BMEIS*.csv"});
            }
            case "pr" -> {
                // PR is bounded by BMEIS (upper) + OB-OPR (lower).
                return loadSurfacePair(
                        bscanMasksDir,
                        "pr",
                        new String[]{"BMEIS", "OB-OPR"},
                        new String[]{"*BMEIS*.csv", "*OB?OPR*.csv"});
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

    /** ga: read {@code *RPEL*.csv} → uint8 (z, cols) binary mask. */
    private static SegmentationEnvelope loadGa(Path bscanMasksDir) throws IOException {
        Path rpel = findFirst(bscanMasksDir, "RPEL", ".csv");
        if (rpel == null) {
            LOG.warn("ga segmentation envelope: no *RPEL*.csv under {}", bscanMasksDir);
            return null;
        }
        // 2026-06-23 — the GA runner's RPEL CSV has a specific layout:
        //
        //   row 0 (header): cols, n_bscans, n_rows  (e.g. 1024, 97, 496)
        //   row 1..N (data): 2 * cols entries each, where
        //     · cols 0       .. cols-1    are SYMBOLIC labels (e.g. 'U' for
        //       unaffected, '1' for GA-detected)
        //     · cols cols-1+1.. 2*cols-1  are NUMERIC classifications
        //       (0 = none, 100 = GA detected)
        //
        // The numeric half is what we want for the per-A-scan binary mask.
        // The symbolic half was previously being parsed via
        // Double.parseDouble("U") → NumberFormatException → defaulted to 0
        // → entire mask zeroed out → no overlay rendered anywhere.
        List<int[]> dataRows = readRpelCsv(rpel);
        if (dataRows.isEmpty()) {
            LOG.warn("ga segmentation envelope: empty RPEL CSV under {}", bscanMasksDir);
            return null;
        }
        int z = dataRows.size();
        int cols = dataRows.get(0).length;
        byte[] data = new byte[z * cols];
        for (int i = 0; i < z; i++) {
            int[] row = dataRows.get(i);
            for (int x = 0; x < cols && x < row.length; x++) {
                if (row[x] > 0) data[i * cols + x] = 1;
            }
        }
        return new SegmentationEnvelope(
                "binary_2d",
                "uint8",
                new int[]{z, cols},
                List.of("RPEL"),
                "ga",
                data
        );
    }

    /**
     * 2026-06-23 — Parse the GA runner's RPEL CSV into per-A-scan binary
     * GA-presence rows. Header row (3 fields: cols, n_bscans, n_rows) is
     * consumed first to learn the canonical {@code cols} count, then each
     * data row is collapsed by taking the SECOND half (when its width is
     * {@code 2 * cols}) so the symbolic-label half is discarded and only
     * the numeric classifications survive. Non-numeric tokens parse as 0.
     *
     * <p>Returns a List<int[]> where each int[] has exactly {@code cols}
     * entries (0 = no GA, > 0 = GA detected at that A-scan).
     */
    private static List<int[]> readRpelCsv(Path csv) throws IOException {
        List<int[]> out = new ArrayList<>();
        int cols = -1;
        try (BufferedReader rdr = Files.newBufferedReader(csv, StandardCharsets.US_ASCII)) {
            String line;
            boolean firstSeen = false;
            while ((line = rdr.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] tokens = line.split(",");
                if (!firstSeen) {
                    firstSeen = true;
                    // Treat row 0 as a dimensions header iff it has many fewer
                    // entries than a typical data row — the runner emits 3
                    // (cols, n_bscans, n_rows). Use field 0 as the canonical
                    // cols count for the rest of the file.
                    if (tokens.length <= 8) {
                        try { cols = Integer.parseInt(tokens[0].trim()); } catch (NumberFormatException ignore) { /* fall through */ }
                        continue;
                    }
                    // No header — use the row's width as cols.
                    cols = tokens.length;
                }
                if (cols <= 0) cols = tokens.length;
                // Take the SECOND half when the row carries the symbolic +
                // numeric pair; otherwise read straight from offset 0.
                int startOffset = (tokens.length == 2 * cols) ? cols : 0;
                int[] row = new int[cols];
                for (int x = 0; x < cols; x++) {
                    int src = startOffset + x;
                    if (src >= tokens.length) break;
                    String t = tokens[src].trim();
                    if (t.isEmpty()) continue;
                    try {
                        row[x] = (int) Math.round(Double.parseDouble(t));
                    } catch (NumberFormatException nfe) {
                        // Symbolic token (e.g. 'U'); treat as no detection.
                        row[x] = 0;
                    }
                }
                out.add(row);
            }
        }
        return out;
    }

    /**
     * onl/pr: read two layer-interface CSVs into a single float32
     * envelope shaped (n_surfaces, z, cols). Each row of the CSV is
     * the Y position of the layer interface in original-image rows
     * for that A-scan column; missing values map to 0.0 which the
     * SPA renderer treats as "no surface here, skip this segment".
     */
    private static SegmentationEnvelope loadSurfacePair(
            Path bscanMasksDir,
            String task,
            String[] labels,
            String[] globs
    ) throws IOException {
        if (labels.length != globs.length) {
            throw new IllegalArgumentException("labels + globs must have matching length");
        }
        List<double[][]> surfaces = new ArrayList<>(labels.length);
        int z = -1;
        int cols = -1;
        for (int i = 0; i < globs.length; i++) {
            Path csv = findFirstByGlob(bscanMasksDir, globs[i]);
            if (csv == null) {
                LOG.warn("{} segmentation envelope: no {} CSV under {} (glob {})",
                        task, labels[i], bscanMasksDir, globs[i]);
                return null;
            }
            double[][] rows = readCsvNumeric(csv);
            // 2026-06-23 — the sese_pr / sese_onl runner emits a 3-element
            // dimensions header as CSV row 0 ({@code cols, n_bscans, n_rows}).
            // The remaining rows are the per-(scan, A-scan) surface Y values.
            // If row 0 is narrower than row 1, drop it: the dims metadata is
            // not needed downstream (the segmentation envelope already
            // carries them) and treating it as scan 0 produced a near-flat
            // line drawn at row=cols/2 for every B-scan, with cols silently
            // clamped to 3 across the rest of the file.
            if (rows.length >= 2 && rows[0].length < rows[1].length) {
                double[][] trimmed = new double[rows.length - 1][];
                System.arraycopy(rows, 1, trimmed, 0, rows.length - 1);
                rows = trimmed;
            }
            if (rows.length == 0) return null;
            int zRows = rows.length;
            int cs = rows[0].length;
            if (z < 0) z = zRows;
            if (cols < 0) cols = cs;
            // Tolerate small mismatches by clamping to the smaller of
            // the two surfaces (sese_pr / sese_onl sometimes emit a
            // trailing blank row).
            if (zRows != z) {
                LOG.warn("{} surface {}: row count {} differs from baseline {} — clamping",
                        task, labels[i], zRows, z);
                if (zRows < z) z = zRows;
            }
            if (cs != cols) {
                LOG.warn("{} surface {}: col count {} differs from baseline {} — clamping",
                        task, labels[i], cs, cols);
                if (cs < cols) cols = cs;
            }
            surfaces.add(rows);
        }
        if (z <= 0 || cols <= 0) return null;
        // Pack as float32 little-endian, surface-major:
        // surface 0 row 0..z-1 col 0..cols-1, then surface 1, etc.
        int sliceLen = z * cols;
        ByteBuffer bb = ByteBuffer.allocate(surfaces.size() * sliceLen * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (double[][] surface : surfaces) {
            for (int rIdx = 0; rIdx < z; rIdx++) {
                double[] row = surface[rIdx];
                for (int x = 0; x < cols; x++) {
                    float v = x < row.length ? (float) row[x] : 0f;
                    bb.putFloat(v);
                }
            }
        }
        return new SegmentationEnvelope(
                "surface_y",
                "float32",
                new int[]{surfaces.size(), z, cols},
                List.of(labels),
                task,
                bb.array()
        );
    }

    /**
     * 2026-06-23 — Glob-based lookup for the surface-pair task. The
     * previous "strip {@code *} and {@code ?}, then substring-match"
     * approach broke for {@code *OB?OPR*.csv} because the literal
     * file names embed an underscore ({@code OB_OPR}) that the
     * stripped token {@code OBOPR} doesn't contain.
     *
     * <p>Translates the supplied glob into a case-insensitive regex
     * (preserving the {@code *} → any-run and {@code ?} → single-char
     * semantics) and returns the lexicographically-first match in
     * the directory. Other glob metacharacters ({@code [},
     * {@code ]}, {@code {}, {@code \}) are escaped — we don't use
     * them in the runner conventions.
     */
    private static Path findFirstByGlob(Path dir, String glob) throws IOException {
        if (!Files.isDirectory(dir) || glob == null || glob.isEmpty()) return null;
        StringBuilder pattern = new StringBuilder("(?i)");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> pattern.append(".*");
                case '?' -> pattern.append('.');
                // Regex metacharacters that may appear literally in the
                // file name — quote them.
                case '.', '\\', '(', ')', '[', ']', '{', '}', '+',
                     '|', '^', '$' -> pattern.append('\\').append(c);
                default -> pattern.append(c);
            }
        }
        java.util.regex.Pattern re = java.util.regex.Pattern.compile(pattern.toString());
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                if (re.matcher(p.getFileName().toString()).matches()) {
                    matches.add(p);
                }
            }
        }
        Collections.sort(matches, Comparator.comparing(p -> p.getFileName().toString()));
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Find the first regular file in {@code dir} whose name contains
     * {@code marker} (case-insensitive) AND ends with {@code suffix}.
     * Returns null when nothing matches.
     */
    private static Path findFirst(Path dir, String marker, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        String markerLower = marker == null ? "" : marker.toLowerCase();
        String suffixLower = suffix == null ? "" : suffix.toLowerCase();
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String n = p.getFileName().toString().toLowerCase();
                if (!n.endsWith(suffixLower)) continue;
                if (!markerLower.isEmpty() && !n.contains(markerLower)) continue;
                matches.add(p);
            }
        }
        Collections.sort(matches, Comparator.comparing(p -> p.getFileName().toString()));
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Read a CSV of plain numeric values into a 2D double[][]. Skips
     * empty lines + tolerates trailing commas. Numeric parse failures
     * fall through as 0.0 rather than throwing — better to surface a
     * partial surface than a 500.
     */
    private static double[][] readCsvNumeric(Path csv) throws IOException {
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader rdr = Files.newBufferedReader(csv, StandardCharsets.US_ASCII)) {
            String line;
            while ((line = rdr.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] tokens = line.split(",");
                double[] vals = new double[tokens.length];
                int valid = 0;
                for (int i = 0; i < tokens.length; i++) {
                    String t = tokens[i].trim();
                    if (t.isEmpty()) continue;
                    try {
                        vals[i] = Double.parseDouble(t);
                        valid++;
                    } catch (NumberFormatException nfe) {
                        vals[i] = 0.0;
                    }
                }
                if (valid > 0) rows.add(vals);
            }
        }
        return rows.toArray(new double[0][]);
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
