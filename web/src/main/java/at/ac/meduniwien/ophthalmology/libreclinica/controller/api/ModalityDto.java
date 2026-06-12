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
 * Phase E.6 study-nurse polish — modality wire shape.
 *
 * <p>One row per institution-wide modality (BCVA-LogMAR, IOP, etc.).
 * The Modality Admin SPA renders this on the catalog view + uses it
 * as the request body for create/update. The Per-Eye Baselines + Patient
 * Measurement Series endpoints embed it as the per-modality header on
 * their responses.
 *
 * <p>{@code itemOidOd} / {@code itemOidOs} are nullable per-side because
 * some modalities are single-eye. At least one of the two must be
 * populated; the controller enforces "≥1 OID set" on write.
 *
 * <p>{@code dataType} ∈ {@code "numeric" | "categorical"}. Numeric
 * modalities are rendered with sparklines + baselines; categorical
 * modalities are rendered as label chips.
 *
 * <p>{@code unit} is a display hint (e.g. {@code "mmHg"}, {@code "logMAR"},
 * {@code "dpt"}) — null for categorical modalities + numeric ones with
 * no canonical unit.
 */
@Schema(name = "ModalityDto")
public record ModalityDto(
        int modalityId,
        String code,
        String labelEn,
        String labelDe,
        int ordinal,
        String itemOidOd,
        String itemOidOs,
        String dataType,
        String unit
) {}
