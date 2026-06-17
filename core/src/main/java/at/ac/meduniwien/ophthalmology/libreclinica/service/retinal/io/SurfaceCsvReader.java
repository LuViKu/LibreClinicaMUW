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
 *   line 1: size header (e.g. ``1024,97,496``) — skipped.
 *   line 2..N: one B-scan per line; comma-separated per-A-scan Y values.
 * </pre>
 *
 * <p>Tokens that are empty, {@code U}, {@code u}, or case-insensitive
 * {@code nan} decode to {@link Double#NaN}; everything else goes
 * through {@link Double#parseDouble}.
 */
public final class SurfaceCsvReader {

    private SurfaceCsvReader() { }

    public static SurfaceGrid read(Path csv) throws IOException {
        List<double[]> rows = new ArrayList<>();
        int nAscans = -1;
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null) {
                throw new IOException("empty CSV: " + csv);
            }
            String line;
            int rowIdx = 0;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] toks = line.split(",", -1);
                if (nAscans < 0) {
                    nAscans = toks.length;
                } else if (toks.length != nAscans) {
                    throw new IllegalArgumentException(
                            "row " + rowIdx + " has " + toks.length
                                    + " columns, expected " + nAscans);
                }
                double[] vals = new double[toks.length];
                for (int i = 0; i < toks.length; i++) {
                    vals[i] = parseToken(toks[i]);
                }
                rows.add(vals);
                rowIdx++;
            }
        }
        double[][] grid = rows.toArray(new double[0][]);
        return new SurfaceGrid(grid.length, nAscans < 0 ? 0 : nAscans, grid);
    }

    private static double parseToken(String raw) {
        String t = raw.trim();
        if (t.isEmpty() || t.equals("U") || t.equals("u") || t.equalsIgnoreCase("nan")) {
            return Double.NaN;
        }
        return Double.parseDouble(t);
    }
}
