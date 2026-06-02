/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E A2 — body of {@code PUT /pages/api/v1/subjects/{oid}}.
 *
 * <p>Mirrors the subset of {@code AddSubjectRequest} that's editable
 * post-creation, per the legacy {@code AdministrativeEditingServlet}
 * scope: identifier fields ({@code id} / {@code enrolledOn}) cannot
 * be changed without breaking foreign-key references and study-event
 * scheduling invariants, so they're omitted here.
 *
 * <p>{@code groupLabel} is NOT in this slice because the create
 * endpoint also skips it (per its M4 scope note); when the SPA gains
 * a group-class picker we'll extend this DTO + endpoint.
 *
 * @param secondaryId optional secondary identifier. {@code null}
 *                    preserves the current value; empty string
 *                    explicitly clears it.
 * @param gender      required. One of {@code F | M | O | U}
 *                    (case-insensitive).
 * @param yearOfBirth optional. {@code null} preserves the current
 *                    value. Must be 1900..currentYear when present.
 */
@Schema(name = "UpdateSubjectRequest")
public record UpdateSubjectRequest(
        String secondaryId,
        String gender,
        Integer yearOfBirth
) {}
