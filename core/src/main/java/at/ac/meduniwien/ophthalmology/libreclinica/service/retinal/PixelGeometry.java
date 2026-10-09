/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;

/**
 * DR-022 — pixel geometry the app-VM /preprocess sidecar reports back in 6
 * response headers per E2E it converts. The Java client parses these into a
 * single carrier and pins them on {@link RemoteRunResult} so the controller
 * (and, later, the SPA via the GET /retinal/scans endpoint) can render the
 * scan-pattern overlay without re-reading the .e2e.
 *
 * <p>All values are in mm (per pixel) for the three spacing axes and raw
 * voxel counts for the three dimensions. {@link #from(HttpHeaders)} throws
 * {@link IllegalStateException} when any of the 6 numeric headers is
 * missing — callers that want a soft-fail can catch + null-out themselves.
 */
public record PixelGeometry(double axialMm,
                            double lateralMm,
                            double sliceMm,
                            int dimZ,
                            int dimY,
                            int dimX) {

    public static final String HEADER_AXIAL_MM = "X-MUW-Pixel-Axial-Mm";
    public static final String HEADER_LATERAL_MM = "X-MUW-Pixel-Lateral-Mm";
    public static final String HEADER_SLICE_MM = "X-MUW-Pixel-Slice-Mm";
    public static final String HEADER_DIM_Z = "X-MUW-Bscan-Dim-Z";
    public static final String HEADER_DIM_Y = "X-MUW-Bscan-Dim-Y";
    public static final String HEADER_DIM_X = "X-MUW-Bscan-Dim-X";
    public static final String HEADER_E2E_UUID = "X-MUW-E2E-Uuid";
    /**
     * 2026-06-23 — original OCT acquisition date pulled from the .e2e
     * header by the retinal-preprocess sidecar. ISO {@code YYYY-MM-DD}.
     * Optional: not every .e2e device populates the field.
     */
    public static final String HEADER_ACQUISITION_DATE = "X-MUW-Acquisition-Date";

    public static PixelGeometry from(HttpHeaders headers) {
        if (headers == null) {
            throw new IllegalStateException("Cannot parse PixelGeometry from null headers");
        }
        return new PixelGeometry(
                requireDouble(headers, HEADER_AXIAL_MM),
                requireDouble(headers, HEADER_LATERAL_MM),
                requireDouble(headers, HEADER_SLICE_MM),
                requireInt(headers, HEADER_DIM_Z),
                requireInt(headers, HEADER_DIM_Y),
                requireInt(headers, HEADER_DIM_X)
        );
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * DR-039 — the same geometry from the {@code geometry.json} the sidecar
     * stored next to {@code bscan.dcm}: its {@code bscan} block, which is the
     * same for an {@code .e2e} and a DICOM source.
     *
     * @return null when the block is missing or any of its six values is
     *         absent or not positive — a geometry with a hole in it would put
     *         a wrong scale on every metric
     */
    public static PixelGeometry fromGeometryJson(JsonNode root) {
        if (root == null) return null;
        JsonNode b = root.path("bscan");
        if (!b.isObject()) return null;
        double axial = b.path("pixel_axial_mm").asDouble(0);
        double lateral = b.path("pixel_lateral_mm").asDouble(0);
        double slice = b.path("pixel_slice_mm").asDouble(0);
        int z = b.path("dim_z_bscans").asInt(0);
        int y = b.path("dim_y_rows").asInt(0);
        int x = b.path("dim_x_ascans").asInt(0);
        if (axial <= 0 || lateral <= 0 || slice <= 0 || z <= 0 || y <= 0 || x <= 0) return null;
        return new PixelGeometry(axial, lateral, slice, z, y, x);
    }

    /** {@link #fromGeometryJson(JsonNode)} of a file; IOException when it cannot be read or parsed. */
    public static PixelGeometry fromGeometryJson(Path file) throws IOException {
        return fromGeometryJson(JSON.readTree(file.toFile()));
    }

    private static double requireDouble(HttpHeaders h, String name) {
        String raw = h.getFirst(name);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Missing geometry header: " + name);
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Geometry header " + name + " is not a double: " + raw, e);
        }
    }

    private static int requireInt(HttpHeaders h, String name) {
        String raw = h.getFirst(name);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Missing geometry header: " + name);
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Geometry header " + name + " is not an int: " + raw, e);
        }
    }
}
