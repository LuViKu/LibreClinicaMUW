package at.ac.meduniwien.ophthalmology.libreclinica.service.export;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.GoldenFiles;

/**
 * The bytes of {@code manifest.json} inside an export bundle.
 *
 * <p>The manifest is a clinical-record deliverable that people diff and tools
 * parse, so its layout (pretty-printer whitespace, property order, number
 * forms) is pinned byte for byte rather than as a parsed tree.
 */
class BundleManifestGoldenTest {

    @Test
    void singleSubjectManifestIsByteStable() throws IOException {
        List<Map<String, Object>> acquisitions = new ArrayList<>();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("ingestItemId", 4711L);
        a.put("kind", "oct");
        a.put("sourceKind", "e2e");
        a.put("device", "Heidelberg Spectralis");
        a.put("laterality", "OD");
        a.put("acquisitionDate", "2026-03-14");
        a.put("scanIndex", 2);
        a.put("sha256", "ab".repeat(32));
        a.put("studyEventId", 12);
        a.put("eventCrfId", 34);
        acquisitions.add(a);

        List<Map<String, Object>> crfFiles = new ArrayList<>();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("itemDataId", 99);
        f.put("itemOid", "I_FILE");
        f.put("eventCrfId", 34);
        crfFiles.add(f);

        List<Map<String, Object>> inference = new ArrayList<>();
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("jobId", 5L);
        i.put("task", "fluid");
        i.put("primaryMetricValue", 0.125);
        i.put("files", List.of("a.npz", "b.csv"));
        i.put("note", null);
        inference.add(i);

        Map<String, Object> manifest = BundleExportWriter.manifest(
                "S-001", "S_STUDY1", "kuchernig", new BundleExportWriter.Policy(true),
                acquisitions, crfFiles, inference,
                List.of(new BundleExportWriter.Omission("inference/5/fluid.npz", "ai-withheld"),
                        new BundleExportWriter.Omission("acq/9", "file \"missing\" on disk \uD83D\uDE00 a/b")),
                7, 123_456_789_012L);
        // The only clock-dependent value; the key keeps its position.
        manifest.put("generatedAt", "2026-10-07T10:00:00Z");

        GoldenFiles.assertMatches("json/bundle-manifest.json", BundleExportWriter.manifestBytes(manifest));
    }
}
