/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

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
