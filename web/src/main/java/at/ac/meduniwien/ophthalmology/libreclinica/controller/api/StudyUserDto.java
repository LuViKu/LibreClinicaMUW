/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.4 M12 — wire-shape for {@code GET /pages/api/v1/users}.
 *
 * <p>Mirrors the Vue SPA's {@code StudyUser} TS interface in
 * {@code web/src/spa/src/types/user.ts} byte-for-byte.
 *
 * @param id           user_account_id as a string
 * @param username     user_account.user_name
 * @param displayName  "first last" or username fallback
 * @param email        user_account.email; null when blank
 * @param role         SPA UserRole — translated from legacy
 *                     {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role}
 *                     via {@link RoleMapper}
 * @param siteLabel    StudyBean.name when role is site-scoped; null
 *                     for study-wide roles (Data Manager, etc.)
 * @param auth         {@code sso | local | ldap | pending-invite}
 *                     — derived from external_id_provider / authtype
 *                     / lastVisitDate
 * @param lastLoginAt  ISO instant of last_visit_date, null when null
 * @param active       Status == AVAILABLE
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "StudyUserDto")
public record StudyUserDto(
        String id,
        String username,
        String displayName,
        String email,
        String role,
        String siteLabel,
        String auth,
        String lastLoginAt,
        boolean active
) {}
