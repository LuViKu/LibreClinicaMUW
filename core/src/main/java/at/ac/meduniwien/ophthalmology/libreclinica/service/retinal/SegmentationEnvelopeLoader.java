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
 *   <li><b>ga / onl / pr</b>: parse the runner's CSV layer/area
 *       artifacts into the matching surface_y / binary_2d envelope
 *       kinds for the SPA renderers.</li>
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
            byte[] data,
            /*
             * 2026-06-26 — surface indices (0-based, matching the order in
             * {@code shape[0]}) whose CSV was served from the
             * {@code corrections/} subfolder rather than the original AI
             * output. Surfaces with no operator correction are omitted.
             * Empty list = no corrections active for the job. The controller
             * surfaces this as the {@code X-MUW-Seg-Corrected} response
             * header.
             */
            List<Integer> correctedSurfaceIndices
    ) {
        /**
         * Back-compat ctor for tasks that don't support corrections (fluid,
         * ga, onl, pr). Defaults {@code correctedSurfaceIndices} to an
         * empty list.
         */
        public SegmentationEnvelope(String kind, String dtype, int[] shape,
                                    List<String> labels, String task, byte[] data) {
            this(kind, dtype, shape, labels, task, data, List.of());
        }
    }

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
            case "layers" -> {
                // 2026-06-25 — IOWA OCTLayerSeg stack: 11 layer
                // interfaces written by the cluster `_iowa_layers`
                // pipeline + flattened by the artifact collector into
                // {@code bscanMasksDir} as {@code NNN-LABEL.csv}
                // (e.g. {@code 001-ILM.csv} ... {@code 011-BM.csv}).
                // The dedicated {@code bm} task also emits
                // {@code 001-Bruch's membrane (BM).csv} into the same
                // dir, which we deliberately exclude — IOWA's slot 11
                // already provides BM, and we want exactly 11 polylines
                // matching the IOWA convention.
                return loadLayersStack(bscanMasksDir);
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
        //     · cols 0       .. cols-1    are SYMBOLIC LABELS (post-mask
        //       classification; 'U' = unaffected/outside-mask, '1' = GA)
        //     · cols cols-1+1.. 2*cols-1  are NUMERIC raw model output
        //       (0 = no GA signal, 100 = GA signal — broader than the
        //       symbolic label by ~1934 cells per per-cell cross-tab)
        //
        // The SYMBOLIC LABELS in the first half are the right binary mask
        // for clinical visualization: they're the model's GA-presence
        // decision AFTER the runner's scan-area mask + threshold post-
        // processing. The second half is the raw signal — broader, more
        // permissive, and includes ~75% extra cells outside the
        // post-processing's accepted zone.
        //
        // Per-cell cross-tab (97-slice volume, sample dataset):
        //   (U, 0)    → 94891 cells   — no GA, no signal
        //   (1, 100)  → 2503  cells   — both agree, definitive GA
        //   (U, 100)  → 1934  cells   — second half only (clipped out)
        //   (1, ≠100) → 0     cells   — never (1 always paired with 100)
        //
        // Earlier the loader fed the whole row to Double.parseDouble,
        // which choked on every 'U' → fell back to 0 → entire mask zeroed
        // out → no overlay rendered anywhere on B-scans or fundus.
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
     * consumed first to learn the canonical {@code cols} count. Each data
     * row is then read from the FIRST half (symbolic labels): the token
     * {@code "1"} marks GA-detected, everything else ({@code "U"},
     * {@code "0"}, blank) is treated as no GA. The second half of each
     * row (raw model signal) is intentionally discarded — it's broader
     * than the post-mask label and would surface false positives outside
     * the runner's accepted scan zone.
     *
     * <p>Returns a List<int[]> where each int[] has exactly {@code cols}
     * entries (0 = no GA, 1 = GA detected at that A-scan).
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
                        try { cols = Integer.parseInt(tokens[0].trim()); } catch (NumberFormatException ignore) { }
                        continue;
                    }
                    // No header — assume the row is symbol-only (cols wide).
                    cols = tokens.length;
                }
                if (cols <= 0) cols = tokens.length;
                // Always read the FIRST cols entries (symbolic label half).
                // For runners that emit only labels (no numeric pair), this
                // reads the whole row. For the 2 * cols layout it ignores
                // the trailing raw-signal half.
                int[] row = new int[cols];
                for (int x = 0; x < cols && x < tokens.length; x++) {
                    String t = tokens[x].trim();
                    // Symbolic GA flag: "1" (or any non-blank non-"U" non-"0"
                    // token, defensively) → 1. "U" / "0" / blank → 0.
                    if ("1".equals(t)) row[x] = 1;
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
     * 2026-06-25 — IOWA OCTLayerSeg layer stack loader.
     *
     * <p>Discovers CSV files in {@code bscanMasksDir} whose name matches
     * the IOWA converter convention {@code NNN-Long Name (SHORT).csv},
     * where {@code NNN} is a 3-digit numeric prefix, {@code Long Name}
     * is a free-form human-readable description (often with spaces or
     * an apostrophe — e.g. {@code OPL-Henle's fiber layer}), and
     * {@code SHORT} is a compact identifier in parentheses
     * (e.g. {@code ILM}, {@code IB_RPE}, {@code OPL-HFL}).
     *
     * <p>IOWA's 11 surfaces from the {@code _iowa_layers} pipeline land
     * with prefixes 001..011; the dedicated {@code bm} task's output
     * ({@code 001-Bruch's membrane (BM).csv}) is appended as a 12th
     * surface — the IOWA slot 011 is "Outer boundary of RPE", NOT
     * Bruch's membrane, so the BM model's output is genuinely
     * additional information (the clinically interesting CRT baseline).
     *
     * <p>Each CSV has the same shape as the ONL/PR surface pairs: rows
     * = B-scan index, columns = A-scan index, values = surface depth
     * in pixel rows. The dimensions-header convention from the
     * sese_pr/sese_onl runners doesn't apply to IOWA's
     * {@code local_IOWA_LayerSegV3_to_CSV} output, but we still tolerate
     * a narrow first row defensively (mirrors {@link #loadSurfacePair}).
     *
     * <p>Sort order: IOWA's 11 surfaces in 001..011 first, then
     * everything else lexically (puts the BM at index 11). The SHORT
     * label is what we surface via the {@code X-MUW-Seg-Labels}
     * header — it's compact, clinically familiar, and matches the
     * SPA palette index.
     */
    private static SegmentationEnvelope loadLayersStack(Path bscanMasksDir) throws IOException {
        // NNN-(long description) (SHORT_LABEL).csv — the IOWA converter's
        // and BM task's stable filename shape. The SHORT label is what
        // we hand to the SPA legend.
        java.util.regex.Pattern surfaceName = java.util.regex.Pattern.compile(
                "^(\\d{3})-.+\\(([^)]+)\\)\\.csv$"
        );
        record SurfaceCsv(int order, boolean iowa, String label, Path path, boolean corrected) {}
        List<SurfaceCsv> entries = new ArrayList<>();
        // 2026-06-26 — prefer a per-surface CSV under the corrections/
        // subfolder when present. The corrected file mirrors the original
        // basename ({@code 001-ILM.csv}) so a contributor can drop one
        // into the directory by hand for diagnostic purposes and the
        // loader picks it up without the controller endpoint involved.
        Path corrDir = bscanMasksDir.resolve("corrections");
        boolean hasCorrDir = Files.isDirectory(corrDir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(bscanMasksDir, "*.csv")) {
            for (Path p : ds) {
                java.util.regex.Matcher m = surfaceName.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int prefix = Integer.parseInt(m.group(1));
                String label = m.group(2).trim();
                // IOWA's 11 layer slots (prefix 001..011) come from
                // _iowa_layers and form the primary stack. Everything
                // else (BM task's 001- prefix) is appended after.
                boolean iowa = prefix >= 1 && prefix <= 11 && !"BM".equalsIgnoreCase(label);
                Path chosen = p;
                boolean corrected = false;
                if (hasCorrDir) {
                    Path candidate = corrDir.resolve(p.getFileName().toString());
                    if (Files.isRegularFile(candidate)) {
                        chosen = candidate;
                        corrected = true;
                    }
                }
                entries.add(new SurfaceCsv(prefix, iowa, label, chosen, corrected));
            }
        }
        if (entries.isEmpty()) {
            LOG.warn("layers envelope: no NNN-...(LABEL).csv files under {}", bscanMasksDir);
            return null;
        }
        // IOWA first by numeric prefix, then non-IOWA (BM) by label.
        entries.sort((a, b) -> {
            if (a.iowa() != b.iowa()) return a.iowa() ? -1 : 1;
            if (a.iowa()) return Integer.compare(a.order(), b.order());
            return a.label().compareTo(b.label());
        });

        List<double[][]> surfaces = new ArrayList<>(entries.size());
        List<String> labels = new ArrayList<>(entries.size());
        List<Integer> corrected = new ArrayList<>();
        int z = -1;
        int cols = -1;
        for (SurfaceCsv e : entries) {
            double[][] rows = readCsvNumeric(e.path());
            // The IOWA converter emits a 3-element dimensions header row
            // (cols, n_bscans, n_rows) followed by per-B-scan rows that
            // are PADDED to a width wider than `cols` — the trailing
            // padding holds a fixed sentinel (100). Honour the header's
            // cols so we only surface real data columns to the SPA;
            // without this, the BscanViewer renders polylines normally
            // for the real region and then sees them jump to a flat
            // horizontal line at the padding value (looks like a flat
            // line in the second half of the canvas).
            int headerCols = -1;
            if (rows.length >= 2 && rows[0].length == 3 && rows[0].length < rows[1].length) {
                headerCols = (int) rows[0][0];
                double[][] trimmed = new double[rows.length - 1][];
                for (int i = 0; i < rows.length - 1; i++) {
                    double[] src = rows[i + 1];
                    if (headerCols > 0 && headerCols < src.length) {
                        double[] cut = new double[headerCols];
                        System.arraycopy(src, 0, cut, 0, headerCols);
                        trimmed[i] = cut;
                    } else {
                        trimmed[i] = src;
                    }
                }
                rows = trimmed;
            }
            if (rows.length == 0) {
                LOG.warn("layers envelope: {} produced 0 data rows; skipping", e.path().getFileName());
                continue;
            }
            int zRows = rows.length;
            int cs = rows[0].length;
            if (z < 0) z = zRows;
            if (cols < 0) cols = cs;
            if (zRows != z) {
                LOG.warn("layers envelope: surface {} row count {} differs from baseline {} — clamping",
                        e.label(), zRows, z);
                if (zRows < z) z = zRows;
            }
            if (cs != cols) {
                LOG.warn("layers envelope: surface {} col count {} differs from baseline {} — clamping",
                        e.label(), cs, cols);
                if (cs < cols) cols = cs;
            }
            if (e.corrected()) corrected.add(surfaces.size());
            surfaces.add(rows);
            labels.add(e.label());
        }
        if (surfaces.isEmpty() || z <= 0 || cols <= 0) return null;

        // Pack as float32 little-endian, surface-major.
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
                List.copyOf(labels),
                "layers",
                bb.array(),
                List.copyOf(corrected)
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
