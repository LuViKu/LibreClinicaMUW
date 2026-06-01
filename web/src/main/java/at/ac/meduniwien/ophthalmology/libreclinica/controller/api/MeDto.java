/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

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
 */
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
        ActiveStudyDto activeStudy
) {

    /** Minimal study summary embedded into MeDto. */
    public record ActiveStudyDto(String oid, String name, boolean isSite) {}
}
