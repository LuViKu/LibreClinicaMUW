package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.CrfVersionAuthoringRequest.Item.Autocomplete.Fill;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.DatasetsApiController.DatasetFilterDto;
import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.GoldenFiles;

/**
 * JSON the application stores in the database, pinned byte for byte.
 *
 * <p>These strings are written once and read for years, by this code and by
 * whatever replaces it. A library upgrade that reorders properties or changes
 * how a number is printed changes what an old row looks like next to a new one
 * without any test noticing, because the parsed values are equal. The fixtures
 * under {@code src/test/resources/golden/json} were produced by the Jackson 2
 * code before the Jackson 3 move.
 */
class StoredJsonGoldenTest {

    @Test
    void crfFillMapColumn() throws IOException {
        List<Fill> fills = List.of(new Fill("code", "I_CODE"), new Fill("display", null));
        GoldenFiles.assertMatches("json/crf-fill-map.json", CrfsApiController.fillMapJson(fills));
    }

    @Test
    void fillMapReadsBackWhatItWrote() {
        List<Fill> fills = List.of(new Fill("code", "I_CODE"), new Fill("display", null));
        assertEquals(fills, CrfsApiController.parseFillMap(CrfsApiController.fillMapJson(fills)));
    }

    @Test
    void fillMapReadIsStrictAboutUnknownPropertiesAndRecoversEmpty() {
        // This reader was a plain ObjectMapper: unknown properties fail it and
        // the item falls back to "system only". Kept as it was.
        assertEquals(List.of(), CrfsApiController.parseFillMap(
                "[{\"fromProperty\":\"a\",\"toKey\":\"b\",\"extra\":1}]"));
        assertEquals(List.of(), CrfsApiController.parseFillMap("not json"));
        assertEquals(List.of(), CrfsApiController.parseFillMap("  "));
        assertEquals(List.of(), CrfsApiController.parseFillMap(null));
        assertEquals(List.of(new Fill("a", "b")), CrfsApiController.parseFillMap(
                "[{\"fromProperty\":\"a\",\"toKey\":\"b\"}]"));
    }

    @Test
    void datasetFilterColumn() throws IOException {
        StringBuilder out = new StringBuilder();
        for (DatasetFilterDto f : List.of(
                new DatasetFilterDto("I_VISUS", "gte", "0.5", null),
                new DatasetFilterDto("I_SEX", "in", null, List.of("m", "f")),
                new DatasetFilterDto("I_NOTE", "contains", "Müller \"x\"", List.of()))) {
            out.append(DatasetsApiController.filterJson(f)).append('\n');
        }
        GoldenFiles.assertMatches("json/dataset-filter.jsonl", out.toString());
    }

    @Test
    void retinalOutputPayloadColumn() throws IOException {
        Map<String, Object> etdrs = new LinkedHashMap<>();
        etdrs.put("center", 0.0123);
        etdrs.put("inner_superior", 1.0E-4);
        etdrs.put("inner_nasal", 12345678.9);
        etdrs.put("outer_temporal", 100.0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("biomarkers", List.of("IRF", "SRF", "PED"));
        payload.put("etdrs_mm3", etdrs);
        payload.put("etdrs_center", 0.0123);
        payload.put("voxel_volume_mm3", 0.1 + 0.2);
        payload.put("per_bscan_mm2", new double[] {0.5, 0.25, 1.0});
        payload.put("per_bscan_list", new ArrayList<>(List.of(1, 2.5, 3L)));
        payload.put("segmentation_file", "fluid.npz");
        payload.put("laterality", null);
        payload.put("valid_ascans", 512);
        payload.put("total_ascans", 9_007_199_254_740_993L);
        payload.put("axial_mm_per_px", new BigDecimal("0.00387"));
        payload.put("thickness_um", new BigDecimal("250.50"));
        payload.put("exp", new BigDecimal("1E+3"));
        payload.put("flag", true);
        payload.put("surface_csvs", List.of("upper.csv", "lower.csv"));
        payload.put("note", "µm — <ok> & \"q\" \uD83D\uDE00 a/b");
        payload.put("nan", Double.NaN);
        GoldenFiles.assertMatches("json/retinal-output-payload.json",
                RetinalInferenceApiController.payloadJson(payload));
    }
}
