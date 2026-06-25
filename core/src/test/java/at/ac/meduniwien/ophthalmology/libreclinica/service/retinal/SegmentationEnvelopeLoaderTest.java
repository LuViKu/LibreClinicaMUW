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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    /** IOWA's 11 canonical layer labels, in numeric / anatomical order. */
    private static final String[] IOWA_LABELS = {
            "ILM", "NFL", "GCL-IPL", "INL", "OPL",
            "ONL", "ELM", "IS-OS", "OPR", "RPE", "BM",
    };

    @Test
    public void testLoadLayersStack_emitsAll11InNumericOrder() throws Exception {
        Path dir = tmp.getRoot().toPath();
        // 3 B-scans, 4 A-scans per scan. Each surface gets a distinguishing
        // base value so we can verify ordering + float-pack correctness.
        int z = 3;
        int cols = 4;
        for (int i = 0; i < IOWA_LABELS.length; i++) {
            double base = (i + 1) * 100.0;
            double[][] rows = new double[z][cols];
            for (int r = 0; r < z; r++) {
                for (int c = 0; c < cols; c++) {
                    rows[r][c] = base + r * 10 + c;
                }
            }
            String name = String.format("%03d-%s.csv", i + 1, IOWA_LABELS[i]);
            writeCsv(dir, name, rows);
        }

        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);

        assertNotNull("layers loader returned null", env);
        assertEquals("surface_y", env.kind());
        assertEquals("float32", env.dtype());
        assertEquals("layers", env.task());
        assertArrayEquals(new int[]{11, z, cols}, env.shape());
        assertEquals(List.of(IOWA_LABELS), env.labels());

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
    public void testLoadLayersStack_excludesBmModelOutput() throws Exception {
        // The standalone bm task's CSV ("001-Bruch's membrane (BM).csv")
        // sits in the same artifact dir but MUST be excluded — IOWA's
        // slot 11 already provides BM, and the IOWA-pattern filter
        // (NNN-LABEL.csv with no whitespace/parens) leaves it out.
        Path dir = tmp.getRoot().toPath();
        int z = 2;
        int cols = 3;
        for (int i = 0; i < IOWA_LABELS.length; i++) {
            double[][] rows = new double[z][cols];
            String name = String.format("%03d-%s.csv", i + 1, IOWA_LABELS[i]);
            writeCsv(dir, name, rows);
        }
        // The intruder.
        writeCsv(dir, "001-Bruch's membrane (BM).csv",
                new double[][]{{1, 2, 3}, {4, 5, 6}});

        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);

        assertNotNull(env);
        assertEquals("exactly 11 IOWA surfaces", 11, env.shape()[0]);
        assertEquals(11, env.labels().size());
        for (String label : env.labels()) {
            assertFalse(
                    "label " + label + " must not contain spaces/parens",
                    label.contains(" ") || label.contains("(") || label.contains(")"));
        }
    }

    @Test
    public void testLoadLayersStack_returnsNullWhenNoIowaCsvs() throws Exception {
        Path dir = tmp.getRoot().toPath();
        // Only the BM model's output present — not an IOWA layer stack.
        writeCsv(dir, "001-Bruch's membrane (BM).csv", new double[][]{{1, 2}});
        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);
        assertNull("expected null when no IOWA-pattern CSVs are present", env);
    }

    @Test
    public void testLoadLayersStack_packsLittleEndian() throws Exception {
        Path dir = tmp.getRoot().toPath();
        // Single-surface fixture (well-formed prefix, 1 row x 1 col, value = 42.5).
        writeCsv(dir, "001-ILM.csv", new double[][]{{42.5}});
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
        // Verify the public `load(task, dir)` switch routes "layers"
        // to loadLayersStack and not anywhere else.
        Path dir = tmp.getRoot().toPath();
        writeCsv(dir, "001-ILM.csv", new double[][]{{1, 2, 3}, {4, 5, 6}});
        writeCsv(dir, "002-RPE.csv", new double[][]{{7, 8, 9}, {10, 11, 12}});

        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);

        assertNotNull(env);
        assertEquals("layers", env.task());
        assertEquals("surface_y", env.kind());
        assertArrayEquals(new int[]{2, 2, 3}, env.shape());
        assertEquals(List.of("ILM", "RPE"), env.labels());
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
    public void testLoadLayersStack_acceptsKebabAndSlashLabels() throws Exception {
        // IOWA's actual layer names include hyphenated tokens like
        // "GCL-IPL" and the boundary "IS-OS" (sometimes written
        // "IS/OS" in clinical contexts). The filename regex must
        // accept both punctuation forms.
        Path dir = tmp.getRoot().toPath();
        writeCsv(dir, "001-GCL-IPL.csv", new double[][]{{1.0}});
        writeCsv(dir, "002-IS-OS.csv", new double[][]{{2.0}});
        SegmentationEnvelope env = SegmentationEnvelopeLoader.load("layers", dir);
        assertNotNull(env);
        assertTrue(env.labels().contains("GCL-IPL"));
        assertTrue(env.labels().contains("IS-OS"));
    }
}
