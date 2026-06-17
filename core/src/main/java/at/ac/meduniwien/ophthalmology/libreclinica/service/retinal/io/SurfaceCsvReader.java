/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * DR-022 — Java-side mirror of the Python {@code _read_surface_csv}
 * helper used by the ONL / GA / BMEIS runners.
 *
 * <p>The CSV layout is:
 * <pre>
 *   line 1: size header — three integers (e.g. ``1024,97,496``) declaring
 *           [nAscans, nBscans, depth]. The depth slot is informational.
 *   line 2..N: one B-scan per line; comma-separated per-A-scan Y values.
 * </pre>
 *
 * <p>GA's {@code 001-RPEL.csv} emits TWO concatenated blocks per row —
 * the first ``nAscans`` cells carry the binary mask (0/1), the next
 * ``nAscans`` carry a 0–100 percentage scale. We honour the header here
 * and truncate to the first block; the percentage block is redundant
 * for the area metric.
 *
 * <p>Tokens that are empty, {@code U}, {@code u}, or case-insensitive
 * {@code nan} decode to {@link Double#NaN}; everything else goes
 * through {@link Double#parseDouble}.
 */
public final class SurfaceCsvReader {

    private SurfaceCsvReader() { }

    public static SurfaceGrid read(Path csv) throws IOException {
        List<double[]> rows = new ArrayList<>();
        int declaredAscans = -1;
        int truncatedAscans = -1;
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null) {
                throw new IOException("empty CSV: " + csv);
            }
            declaredAscans = parseHeaderAscans(header);
            String line;
            int rowIdx = 0;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] toks = line.split(",", -1);
                int rowAscans = (declaredAscans > 0 && toks.length > declaredAscans
                        && toks.length % declaredAscans == 0)
                        ? declaredAscans
                        : toks.length;
                if (truncatedAscans < 0) {
                    truncatedAscans = rowAscans;
                } else if (rowAscans != truncatedAscans) {
                    throw new IllegalArgumentException(
                            "row " + rowIdx + " has " + toks.length
                                    + " columns (effective " + rowAscans
                                    + "), expected " + truncatedAscans);
                }
                double[] vals = new double[rowAscans];
                for (int i = 0; i < rowAscans; i++) {
                    vals[i] = parseToken(toks[i]);
                }
                rows.add(vals);
                rowIdx++;
            }
        }
        double[][] grid = rows.toArray(new double[0][]);
        return new SurfaceGrid(grid.length, truncatedAscans < 0 ? 0 : truncatedAscans, grid);
    }

    /** Parse the leading {@code nAscans,nBscans,depth} header; returns -1
     *  when the header isn't three comma-separated positive integers. */
    private static int parseHeaderAscans(String header) {
        String[] parts = header.split(",");
        if (parts.length < 1) return -1;
        try {
            int n = Integer.parseInt(parts[0].trim());
            return n > 0 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double parseToken(String raw) {
        String t = raw.trim();
        if (t.isEmpty() || t.equals("U") || t.equals("u") || t.equalsIgnoreCase("nan")) {
            return Double.NaN;
        }
        return Double.parseDouble(t);
    }
}
