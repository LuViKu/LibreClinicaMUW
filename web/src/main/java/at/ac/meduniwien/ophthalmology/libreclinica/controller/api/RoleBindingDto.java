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
 * Phase E A7.5 — one row per (user, study) role binding.
 *
 * <p>Wire shape consumed by the SPA users-roles UI. Includes the
 * legacy {@code study_user_role} primary keys ({@code studyId}) plus
 * the SPA-friendly study identity ({@code studyOid}, {@code studyName})
 * + {@code siteLabel} for sites under a parent.
 *
 * <p>{@code role} is the SPA's UserRole union string (resolved via
 * {@link RoleMapper}); {@code active} reflects {@code status_id ==
 * AVAILABLE}.
 */
@Schema(name = "RoleBindingDto")
public record RoleBindingDto(
        Integer studyId,
        String studyOid,
        String studyName,
        String siteLabel,
        String role,
        boolean active
) {}
