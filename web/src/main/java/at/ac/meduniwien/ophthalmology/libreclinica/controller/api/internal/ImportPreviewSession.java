/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api.internal;

import java.time.Instant;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.ImportCrfPreviewDto;

/**
 * Phase E.6 {@code bulk-import} — parked container for a multi-step
 * ODM import.
 *
 * <p>Mirrors RX.2's session-attribute strategy: the upload endpoint
 * parses + validates the ODM payload, allocates a token, and parks
 * this container against the operator's HTTP session under a key
 * derived from the token. The follow-up commit endpoint pulls the
 * container back out keyed by the token. Two-attribute strategy
 * (container + expiry) so an expired token can short-circuit before
 * touching any DAO.
 *
 * <p>Rows are pre-projected at upload time so the paginated rows
 * endpoint ({@code GET /import/{token}/rows}) can window into the
 * same list without re-parsing the ODM payload on every request.
 *
 * <p>This is a thin DTO-like container; the controller owns the
 * session-attribute lifecycle.
 */
public final class ImportPreviewSession {

    private final ODMContainer odmContainer;
    private final List<ImportCrfPreviewDto.PreviewRowDto> allRows;
    private final ImportCrfPreviewDto previewSummary;
    private final String originalFilename;
    private final Instant uploadedAt;
    private final Instant expiresAt;

    public ImportPreviewSession(ODMContainer odmContainer,
                                List<ImportCrfPreviewDto.PreviewRowDto> allRows,
                                ImportCrfPreviewDto previewSummary,
                                String originalFilename,
                                Instant uploadedAt,
                                Instant expiresAt) {
        this.odmContainer = odmContainer;
        this.allRows = allRows;
        this.previewSummary = previewSummary;
        this.originalFilename = originalFilename;
        this.uploadedAt = uploadedAt;
        this.expiresAt = expiresAt;
    }

    public ODMContainer odmContainer() { return odmContainer; }
    public List<ImportCrfPreviewDto.PreviewRowDto> allRows() { return allRows; }
    public ImportCrfPreviewDto previewSummary() { return previewSummary; }
    public String originalFilename() { return originalFilename; }
    public Instant uploadedAt() { return uploadedAt; }
    public Instant expiresAt() { return expiresAt; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
