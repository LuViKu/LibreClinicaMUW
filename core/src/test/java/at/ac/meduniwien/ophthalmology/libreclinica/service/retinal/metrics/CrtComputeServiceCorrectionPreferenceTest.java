/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 2026-06-27 — Verifies that {@link CrtComputeService#locateSurfaceCsv}
 * picks the operator-corrected CSV when present, falling back to the
 * AI's original CSV otherwise. Together with the SegmentationEnvelopeLoader
 * change shipped in PR-1 this means CST (Central Subfield Thickness)
 * computed by {@code /crt-timeline} automatically follows operator
 * corrections — every per-request recompute reads the corrected ILM /
 * BM rows from {@code <bscan_masks_dir>/corrections/} before it falls
 * back to the AI output.
 */
public class CrtComputeServiceCorrectionPreferenceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void picksCorrectedFile_whenCorrectionsSubfolderHasMatchingName() throws IOException {
        Path dir = tmp.newFolder("masks").toPath();
        Files.writeString(dir.resolve("001-Internal Limiting Membrane (ILM).csv"),
                "AI ILM\n", StandardCharsets.UTF_8);
        Path corrections = Files.createDirectory(dir.resolve("corrections"));
        Path corrected = corrections.resolve("001-Internal Limiting Membrane (ILM).csv");
        Files.writeString(corrected, "OPERATOR ILM\n", StandardCharsets.UTF_8);

        Optional<Path> resolved = CrtComputeService.locateSurfaceCsv(dir.toString(), "ilm");

        assertTrue("locateSurfaceCsv must return a CSV when a match exists", resolved.isPresent());
        assertEquals("corrections/<name> must win over the AI's original",
                corrected.toAbsolutePath(), resolved.get().toAbsolutePath());
    }

    @Test
    public void fallsBackToOriginal_whenCorrectionsSubfolderAbsent() throws IOException {
        Path dir = tmp.newFolder("masks").toPath();
        Path original = dir.resolve("011-Bruch's Membrane (BM).csv");
        Files.writeString(original, "AI BM\n", StandardCharsets.UTF_8);

        Optional<Path> resolved = CrtComputeService.locateSurfaceCsv(dir.toString(), "bm");

        assertTrue(resolved.isPresent());
        assertEquals(original.toAbsolutePath(), resolved.get().toAbsolutePath());
    }

    @Test
    public void fallsBackToOriginal_whenCorrectionsSubfolderEmpty() throws IOException {
        Path dir = tmp.newFolder("masks").toPath();
        Path original = dir.resolve("001-Internal Limiting Membrane (ILM).csv");
        Files.writeString(original, "AI ILM\n", StandardCharsets.UTF_8);
        Files.createDirectory(dir.resolve("corrections")); // empty

        Optional<Path> resolved = CrtComputeService.locateSurfaceCsv(dir.toString(), "ilm");

        assertTrue(resolved.isPresent());
        assertEquals(original.toAbsolutePath(), resolved.get().toAbsolutePath());
    }

    @Test
    public void picksCorrectedBmAndOriginalIlm_independentlyPerNeedle() throws IOException {
        // Operator corrected BM, didn't touch ILM. Both should resolve.
        Path dir = tmp.newFolder("masks").toPath();
        Path originalIlm = dir.resolve("001-Internal Limiting Membrane (ILM).csv");
        Path originalBm = dir.resolve("011-Bruch's Membrane (BM).csv");
        Files.writeString(originalIlm, "AI ILM\n", StandardCharsets.UTF_8);
        Files.writeString(originalBm, "AI BM\n", StandardCharsets.UTF_8);
        Path corrections = Files.createDirectory(dir.resolve("corrections"));
        Path correctedBm = corrections.resolve("011-Bruch's Membrane (BM).csv");
        Files.writeString(correctedBm, "OPERATOR BM\n", StandardCharsets.UTF_8);

        Optional<Path> ilm = CrtComputeService.locateSurfaceCsv(dir.toString(), "ilm");
        Optional<Path> bm = CrtComputeService.locateSurfaceCsv(dir.toString(), "bm");

        assertTrue(ilm.isPresent());
        assertEquals(originalIlm.toAbsolutePath(), ilm.get().toAbsolutePath());
        assertTrue(bm.isPresent());
        assertEquals(correctedBm.toAbsolutePath(), bm.get().toAbsolutePath());
    }

    @Test
    public void returnsEmpty_whenNeitherDirHasMatch() throws IOException {
        Path dir = tmp.newFolder("masks").toPath();
        Files.writeString(dir.resolve("002-Retinal Pigment Epithelium (RPE).csv"),
                "AI RPE\n", StandardCharsets.UTF_8);
        Files.createDirectory(dir.resolve("corrections"));

        Optional<Path> resolved = CrtComputeService.locateSurfaceCsv(dir.toString(), "ilm");

        assertFalse(resolved.isPresent());
    }
}
