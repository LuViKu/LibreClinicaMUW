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
 * @param locked       Phase E.6 unlock-user — true iff
 *                     {@code user_account.account_non_locked = false}
 *                     (i.e. login attempts crossed the failed-login
 *                     threshold and SpringSecurity is refusing the
 *                     account). Only meaningful for local users; SSO
 *                     and LDAP users authenticate against the IdP /
 *                     directory so the SPA hides the Unlock affordance
 *                     for them.
 * @param firstName    user_account.first_name; null when blank. Like the
 *                     fields below, what the legacy View User page shows
 *                     and the SPA's edit dialog and access review need.
 * @param lastName     user_account.last_name; null when blank
 * @param phone        user_account.phone; null when blank
 * @param institutionalAffiliation user_account.institutional_affiliation;
 *                     null when blank
 * @param userType     {@code USER}, {@code SYSADMIN} (business
 *                     administrator) or {@code TECHADMIN} (technical
 *                     administrator), the vocabulary the create and edit
 *                     endpoints take
 * @param createdDate  ISO {@code yyyy-MM-dd} of user_account.date_created
 * @param ownerUsername username of the account that created this one
 * @param updatedDate  ISO {@code yyyy-MM-dd} of user_account.date_updated;
 *                     null when never updated
 * @param updaterUsername username of the account that last updated it
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
        boolean active,
        boolean locked,
        String firstName,
        String lastName,
        String phone,
        String institutionalAffiliation,
        String userType,
        String createdDate,
        String ownerUsername,
        String updatedDate,
        String updaterUsername
) {}
