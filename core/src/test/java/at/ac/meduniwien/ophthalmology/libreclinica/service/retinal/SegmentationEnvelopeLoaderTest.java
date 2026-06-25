/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.SegmentationEnvelopeLoader.SegmentationEnvelope;

public class SegmentationEnvelopeLoaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Write a CSV at {@code dir/name} with one B-scan per row, comma-separated floats. */
    private Path writeCsv(Path dir, String name, double[][] rows) throws IOException {
        Path csv = dir.resolve(name);
        StringBuilder sb = new StringBuilder();
        for (double[] row : rows) {
            for (int x = 0; x < row.length; x++) {
                if (x > 0) sb.append(',');
                sb.append(row[x]);
            }
            sb.append('\n');
        }
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
        return csv;
    }

    /**
     * IOWA's 11 canonical surfaces with their full converter filename
     * shape: {@code NNN-Long description (SHORT_LABEL).csv}. The short
     * label is what we surface to the SPA.
     */
    private static final String[][] IOWA_FILES = {
            {"001-ILM (ILM).csv", "ILM"},
            {"002-RNFL-GCL (RNFL-GCL).csv", "RNFL-GCL"},
            {"003-GCL-IPL (GCL-IPL).csv", "GCL-IPL"},
            {"004-IPL-INL (IPL-INL).csv", "IPL-INL"},
            {"005-INL-OPL (INL-OPL).csv", "INL-OPL"},
            {"006-OPL-Henle's fiber layer (OPL-HFL).csv", "OPL-HFL"},
            {"007-Boundary of myoid and ellipsoid of inner segments (BMEIS).csv", "BMEIS"},
            {"008-IS#OS junction (IS#OSJ).csv", "IS#OSJ"},
            {"009-Inner boundary of OPR (IB_OPR).csv", "IB_OPR"},
            {"010-Inner boundary of RPE (IB_RPE).csv", "IB_RPE"},
            {"011-Outer boundary of RPE (OB_RPE).csv", "OB_RPE"},
    };

    @Test
    public void testLoadLayersStack_emitsAll11InNumericOrder() throws Exception {
        Path dir = tmp.getRoot().toPath();
        // 3 B-scans, 4 A-scans per scan. Each surface gets a distinguishing
        // base value so we can verify ordering + float-pack correctness.
        int z = 3;
        int cols = 4;
        for (int i = 0; i < IOWA_FILES.length; i++) {
            double base = (i + 1) * 100.0;
            double[][] rows = new double[z][cols];
            for (int r = 0; r < z; r++) {
                for (int c = 0; c < cols; c++) {
                    rows[r][c] = base + r * 10 + c;
                }
            }
            writeCsv(dir, IOWA_FILES[i][0], rows);
        }

        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);

        assertNotNull("layers loader returned null", env);
        assertEquals("surface_y", env.kind());
        assertEquals("float32", env.dtype());
        assertEquals("layers", env.task());
        assertArrayEquals(new int[]{11, z, cols}, env.shape());
        String[] expectedShortLabels = new String[IOWA_FILES.length];
        for (int i = 0; i < IOWA_FILES.length; i++) expectedShortLabels[i] = IOWA_FILES[i][1];
        assertEquals(List.of(expectedShortLabels), env.labels());

        // Float-pack: surface-major, little-endian, 4 bytes per value.
        assertEquals(11 * z * cols * 4, env.data().length);
        ByteBuffer bb = ByteBuffer.wrap(env.data()).order(ByteOrder.LITTLE_ENDIAN);
        for (int s = 0; s < 11; s++) {
            double base = (s + 1) * 100.0;
            for (int r = 0; r < z; r++) {
                for (int c = 0; c < cols; c++) {
                    float v = bb.getFloat();
                    assertEquals(
                            "surface " + s + " r=" + r + " c=" + c + " mismatch",
                            (float) (base + r * 10 + c), v, 1e-6f);
                }
            }
        }
    }

    @Test
    public void testLoadLayersStack_includesBmModelAs12thSurface() throws Exception {
        // IOWA's slot 011 is "Outer boundary of RPE" (OB_RPE), NOT
        // Bruch's membrane. The standalone bm task's CSV genuinely adds
        // a 12th surface — the BM that sits below RPE. Sorting puts
        // IOWA's 001..011 first, then BM at index 11.
        Path dir = tmp.getRoot().toPath();
        int z = 2;
        int cols = 3;
        for (String[] iowa : IOWA_FILES) {
            writeCsv(dir, iowa[0], new double[z][cols]);
        }
        writeCsv(dir, "001-Bruch's membrane (BM).csv", new double[][]{{1, 2, 3}, {4, 5, 6}});

        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);

        assertNotNull(env);
        assertEquals("11 IOWA surfaces + 1 BM = 12", 12, env.shape()[0]);
        assertEquals(12, env.labels().size());
        // BM lands at the end of the stack, after the 11 IOWA slots.
        assertEquals("ILM", env.labels().get(0));
        assertEquals("OB_RPE", env.labels().get(10));
        assertEquals("BM", env.labels().get(11));
    }

    @Test
    public void testLoadLayersStack_returnsNullWhenNoMatchingCsvs() throws Exception {
        Path dir = tmp.getRoot().toPath();
        // A CSV that doesn't match the NNN-...(LABEL).csv shape — e.g.
        // the ga task's 001-RPEL.csv (no parenthesised short label).
        writeCsv(dir, "001-RPEL.csv", new double[][]{{1, 2}});
        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);
        assertNull(env);
    }

    @Test
    public void testLoadLayersStack_packsLittleEndian() throws Exception {
        Path dir = tmp.getRoot().toPath();
        writeCsv(dir, "001-ILM (ILM).csv", new double[][]{{42.5}});
        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);

        assertNotNull(env);
        assertEquals(1, env.shape()[0]);
        assertArrayEquals(new int[]{1, 1, 1}, env.shape());
        assertEquals(4, env.data().length);
        // little-endian float32 of 42.5 = 0x42 2A 00 00, byte-reversed = 00 00 2A 42
        assertEquals((byte) 0x00, env.data()[0]);
        assertEquals((byte) 0x00, env.data()[1]);
        assertEquals((byte) 0x2A, env.data()[2]);
        assertEquals((byte) 0x42, env.data()[3]);
    }

    @Test
    public void testLoadLayersStack_dispatchedFromLoad() throws Exception {
        Path dir = tmp.getRoot().toPath();
        writeCsv(dir, "001-ILM (ILM).csv", new double[][]{{1, 2, 3}, {4, 5, 6}});
        writeCsv(dir, "002-RNFL-GCL (RNFL-GCL).csv", new double[][]{{7, 8, 9}, {10, 11, 12}});

        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);

        assertNotNull(env);
        assertEquals("layers", env.task());
        assertEquals("surface_y", env.kind());
        assertArrayEquals(new int[]{2, 2, 3}, env.shape());
        assertEquals(List.of("ILM", "RNFL-GCL"), env.labels());
    }

    @Test
    public void testLoad_unknownTaskReturnsNull() throws Exception {
        Path dir = tmp.getRoot().toPath();
        assertNull(SegmentationEnvelopeLoader.load("nonsense", dir));
    }

    @Test
    public void testLoad_nullInputsReturnNull() throws Exception {
        assertNull(SegmentationEnvelopeLoader.load(null, tmp.getRoot().toPath()));
        assertNull(SegmentationEnvelopeLoader.load("layers", null));
    }

    @Test
    public void testLoadLayersStack_acceptsPunctuationInShortLabel() throws Exception {
        // The IS#OS junction surface lands as "IS#OSJ" with a # in the
        // short label. The OPL-HFL surface's filename has an apostrophe
        // in the long description ("OPL-Henle's fiber layer"). Both
        // must round-trip cleanly.
        Path dir = tmp.getRoot().toPath();
        writeCsv(dir, "008-IS#OS junction (IS#OSJ).csv", new double[][]{{1.0}});
        writeCsv(dir, "006-OPL-Henle's fiber layer (OPL-HFL).csv", new double[][]{{2.0}});
        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);
        assertNotNull(env);
        assertEquals(List.of("OPL-HFL", "IS#OSJ"), env.labels());
    }
}
