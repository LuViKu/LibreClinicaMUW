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
 * Phase E.4 M1 — current-user DTO.
 *
 * <p>Wire shape consumed by the SPA `AuthenticatedUser` TS type
 * (web/src/spa/src/types/auth.ts). The `role` field is mapped from
 * the legacy `Role.id` taxonomy into the SPA's 5-value union
 * (Investigator / Monitor / Data Manager / Administrator / CRC).
 *
 * <p>`activeStudy` is null when the user hasn't picked a study yet
 * (legacy SecureController binds it from `ub.getActiveStudyId()` on
 * first authenticated request — see DAO comment).
 *
 * <p>`mfaSatisfied` reflects the user's bound 2FA state from
 * `UserAccountBean.getRunWebservices()` -- legacy convention -- but
 * always returns true for users authenticated via SSO (the IdP is
 * authoritative per DR-014). For first-cut this just returns true.
 *
 * <p>Phase E.5 B1 — {@code locale} + {@code timezone} added so the
 * SPA's first-login wizard can both read the user's persisted
 * preferences and PUT updates back via
 * {@code PUT /pages/api/v1/me/profile}. Both fields are nullable;
 * the SPA's {@code AuthenticatedUser} type carries them as
 * {@code string | null} so a freshly-provisioned user with no
 * persisted locale still serialises cleanly.
 *
 * <p>Phase E.6 — {@code mustChangePassword} + {@code
 * passwordChangeReason} added to restore parity with the legacy
 * {@code SecureController.passwdTimeOut()} +
 * {@code MainMenuServlet} forced-rotation gates. The SPA's auth
 * store reads {@code mustChangePassword} on every {@code /me}
 * response and, when {@code true}, the router pushes the user to
 * {@code /change-password}. {@code passwordChangeReason} is purely
 * a UX hint that lets the change-password view show a localised
 * banner ("first login" vs "password expired"); it is null when
 * {@code mustChangePassword} is false. SSO-bound and LDAP users
 * always serialise {@code mustChangePassword=false} — their
 * upstream identity provider owns the credential lifecycle per
 * DR-014.
 */
@Schema(name = "MeDto")
public record MeDto(
        String username,
        String displayName,
        String email,
        String role,
        String siteLabel,
        String source,
        boolean mfaSatisfied,
        boolean profileComplete,
        String locale,
        String timezone,
        boolean mustChangePassword,
        String passwordChangeReason,
        ActiveStudyDto activeStudy
) {

    /** Allowed values of the {@code passwordChangeReason} field. */
    public static final String REASON_FIRST_LOGIN = "first-login";
    public static final String REASON_ROTATION = "rotation";

    /**
     * Minimal study summary embedded into MeDto.
     *
     * <p>{@code id} is exposed because the legacy session-bound APIs (e.g.
     * {@code POST /api/v1/users} body) accept {@code studyId} integers, and
     * the SPA needs to dispatch those calls against the active study without
     * a separate {@code /studies?oid=} lookup.
     */
    @Schema(name = "ActiveStudyDto")
    public record ActiveStudyDto(int id, String oid, String name, boolean isSite) {}
}
